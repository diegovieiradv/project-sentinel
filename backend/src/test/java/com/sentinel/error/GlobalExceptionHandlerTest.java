package com.sentinel.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Testes diretos do GlobalExceptionHandler (sem banco, sem MockMvc):
 * ApiException → status/código corretos; validação → 400; corpo ilegível e
 * argument type mismatch → 400 genérico (Prompt 05); 404; 405; genérica → 500
 * com mensagem padrão e sem stack trace no corpo.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/v1/thing");
        return request;
    }

    @Test
    void apiExceptionMapsToItsHttpStatusAndErrorCode() {
        ApiException ex = new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);

        ResponseEntity<ErrorResponse> response = handler.handleApiException(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().getMessage()).isEqualTo("Resource not found");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/thing");
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }

    @Test
    void genericExceptionReturns500WithGenericMessageAndNoInternals() throws Exception {
        Exception ex = new IllegalStateException("secret internal detail: db password xyz");

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().getMessage()).isEqualTo("An internal error occurred");

        String json = objectMapper.writeValueAsString(response.getBody());
        assertThat(json)
                .doesNotContain("secret internal detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("stackTrace")
                .doesNotContain("exception");
    }

    @Test
    void validationExceptionReturns400WithAggregatedFieldErrors() throws Exception {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("samplePayload", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "samplePayload");
        bindingResult.addError(new FieldError(
                "samplePayload", "name", null, false, new String[]{"name"}, new Object[0],
                "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().getMessage()).contains("name: must not be blank");
    }

    @Test
    void noResourceFoundReturns404WithNotFoundCode() {
        NoResourceFoundException ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/v1/thing", "No static resource thing");

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().getMessage()).isEqualTo("Resource not found");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/thing");
    }

    @Test
    void noHandlerFoundReturns404WithNotFoundCode() {
        NoHandlerFoundException ex = new NoHandlerFoundException(
                "GET", "/api/v1/thing", new org.springframework.http.HttpHeaders());

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/thing");
    }

    @Test
    void methodNotAllowedReturns405WithMethodInMessageAndStandardShape() {
        // D7 (Prompt 04): sem este handler a exceção cairia no catch-all e
        // viraria 500 INTERNAL_ERROR
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE", java.util.List.of("GET"));

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotAllowed(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().getMessage()).isEqualTo("Method DELETE not allowed");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/thing");
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }

    @Test
    void unreadableBodyReturns400WithGenericMessageAndNoParserDetail() throws Exception {
        // JSON truncado no meio: entrada do cliente, não erro interno — sem
        // este handler viraria 500 no catch-all
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected end-of-input at [Source: (String); line: 1, column: 20]",
                null);

        ResponseEntity<ErrorResponse> response = handler.handleUnreadableBody(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/thing");

        String json = objectMapper.writeValueAsString(response.getBody());
        assertThat(json)
                .doesNotContain("JSON parse")
                .doesNotContain("end-of-input")
                .doesNotContain("Source")
                .doesNotContain("line:")
                .doesNotContain("stackTrace");
    }

    @Test
    void typeMismatchReturns400WithGenericMessageAndNoConversionDetail() throws Exception {
        // Hoje nenhum endpoint expõe parâmetro tipado (os IDs de tenant são
        // String + parse manual → 404 anti-enumeração), então o critério é
        // coberto aqui: o handler traduz o mismatch para 400 no shape padrão.
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("samplePayload", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "not-a-number", Integer.class, "page", parameter,
                new IllegalArgumentException("For input string: \"not-a-number\""));

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid request parameter");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/thing");

        String json = objectMapper.writeValueAsString(response.getBody());
        assertThat(json)
                .doesNotContain("not-a-number")
                .doesNotContain("IllegalArgumentException")
                .doesNotContain("stackTrace");
    }

    @SuppressWarnings("unused")
    private void samplePayload(String name) {
        // apenas assinatura para construir MethodParameter no teste de validação
    }
}
