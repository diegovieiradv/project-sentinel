package com.sentinel.error;

import java.time.Instant;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduz exceções para o shape ErrorResponse padrão da API.
 * Nunca expõe stack trace ou detalhes internos ao cliente.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                ex.getErrorCode(),
                ex.getMessage(),
                Instant.now(),
                request.getRequestURI());
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Validation failed";
        }
        ErrorResponse body = new ErrorResponse("VALIDATION_ERROR", message, Instant.now(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Corpo ilegível (JSON sintaticamente inválido, EOF, tipo incompatível com
     * o DTO): é erro de ENTRADA do cliente, não erro interno — sem este
     * handler a exceção cairia no catch-all genérico e viraria 500.
     *
     * <p>A mensagem é genérica de propósito: a original do parser Jackson cita
     * sintaxe, fonte e classes internas — nada disso pode ir ao cliente.</p>
     *
     * <p>Não conflita com a política anti-enumeração de 404: IDs de tenant
     * malformados em path/header nunca chegam aqui — o
     * {@code TenantInterceptor.parseOrganizationId}/{@code parseMembershipId}
     * responde 404 <b>antes</b> (nenhum controller usa {@code @PathVariable
     * UUID} tipado).</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ErrorResponse body = new ErrorResponse(
                "VALIDATION_ERROR",
                "Malformed request body",
                Instant.now(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Parâmetro de requisição com tipo incompatível (ex.: valor não
     * convertível para o tipo do parâmetro) → 400, nunca 500. Mensagem
     * genérica: o valor/tipo aceito é do contrato do cliente, mas o detalhe
     * da conversão (e a pilha) ficam no servidor.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                "VALIDATION_ERROR",
                "Invalid request parameter",
                Instant.now(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Corrige o P2 do Prompt 01: rota/resource inexistente caía no handler
     * genérico (500). No Spring Framework 7 existem dois caminhos:
     * NoResourceFoundException (resource handler de estáticos em /**, o
     * habitual num app Boot) e NoHandlerFoundException (nenhum handler
     * mapeado). Ambos → 404 NOT_FOUND no shape padrão.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                "NOT_FOUND",
                "Resource not found",
                Instant.now(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * D7 (Prompt 04): método HTTP inválido para uma rota existente. Sem este
     * handler a exceção cai no catch-all genérico e vira 500 INTERNAL_ERROR —
     * agora responde 405 no shape padrão da API.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                "METHOD_NOT_ALLOWED",
                "Method " + ex.getMethod() + " not allowed",
                Instant.now(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ErrorResponse body = new ErrorResponse(
                "INTERNAL_ERROR",
                "An internal error occurred",
                Instant.now(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
