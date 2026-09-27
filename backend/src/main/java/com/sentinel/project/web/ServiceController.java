package com.sentinel.project.web;

import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.web.TenantScoped;
import com.sentinel.project.dto.ServiceActivationRequest;
import com.sentinel.project.dto.ServiceCreateRequest;
import com.sentinel.project.dto.ServiceResponse;
import com.sentinel.project.dto.ServiceUpdateRequest;
import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.service.ServiceService;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de serviço (Prompt 07) — sub-recurso de projeto:
 * {@code /api/v1/organizations/{organizationId}/projects/{projectId}/services}.
 *
 * <p>Todos os handlers são {@code @TenantScoped}: o
 * {@link com.sentinel.organization.web.TenantInterceptor} valida a membership
 * ANTES do handler e devolve 404 genérico a não-membros. O handler não
 * recebe a organização nem o projeto como domínio: a organização já está no
 * {@link com.sentinel.organization.context.TenantContext} e o projeto é
 * resolvido no service com lookup tenant-scoped. Os DTOs nem têm campos de
 * escopo — impossível criar serviço "em outro projeto" por payload.</p>
 *
 * <p><b>Sem {@code @AuthenticationPrincipal}</b> (diferente do
 * {@code OrganizationController}): todo handler passa pelo interceptor, que já
 * exige um principal {@code User} e responde {@code 401} antes da resolução de
 * argumentos — um parâmetro de guarda aqui seria código morto. É o mesmo
 * arranjo do {@code ProjectController}.</p>
 *
 * <p><b>Sem DELETE</b> (nem handler, nem soft-delete implícito): a remoção não
 * existe neste prompt — desativação é o {@code .../activation}. Um DELETE na
 * rota cai naturalmente no contrato global de {@code 405}.</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/services")
public class ServiceController {

    private final ServiceService serviceService;

    public ServiceController(ServiceService serviceService) {
        this.serviceService = serviceService;
    }

    /** POST {name, description?} → 201 ServiceResponse (ADMIN/OPERATOR). */
    @PostMapping
    @TenantScoped
    public ResponseEntity<ServiceResponse> create(
            @PathVariable String projectId, @Valid @RequestBody ServiceCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(serviceService.create(parseId(projectId), request));
    }

    /**
     * GET → 200 página no formato Spring Page (qualquer papel; projeto
     * inativo continua legível).
     *
     * @param page   0-based, default {@code 0}
     * @param size   itens por página, default {@code 20}, máximo {@code 100}
     * @param sort   allowlist {@code createdAt|name|id} + direção, default
     *               {@code createdAt,asc}; o desempate {@code id asc} é fixo
     * @param active filtro opcional por participação no catálogo
     * @param status filtro opcional por estado operacional (valor fora do
     *               enum → 400, nunca 500)
     * @param name   busca opcional por nome (case-insensitive, subcadeia)
     */
    @GetMapping
    @TenantScoped
    public Page<ServiceResponse> list(
            @PathVariable String projectId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "status", required = false) ServiceStatus status,
            @RequestParam(name = "name", required = false) String name) {
        return serviceService.list(parseId(projectId), page, size, sort, active, status, name);
    }

    /** GET /{serviceId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @GetMapping("/{serviceId}")
    @TenantScoped
    public ServiceResponse get(@PathVariable String projectId, @PathVariable String serviceId) {
        return serviceService.get(parseId(projectId), parseId(serviceId));
    }

    /** PATCH {name?, description?} → 200 (ADMIN/OPERATOR). DTO fechado. */
    @PatchMapping("/{serviceId}")
    @TenantScoped
    public ServiceResponse update(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @RequestBody ServiceUpdateRequest request) {
        return serviceService.update(parseId(projectId), parseId(serviceId), request);
    }

    /**
     * PATCH {active: boolean} → 200, idempotente (ADMIN/OPERATOR). Rota
     * chamada {@code activation} — e não {@code status} — porque o corpo
     * liga/desliga {@code active}; o status operacional ({@code ServiceStatus})
     * não é alterável por nenhum endpoint deste prompt.
     */
    @PatchMapping("/{serviceId}/activation")
    @TenantScoped
    public ServiceResponse activation(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @Valid @RequestBody ServiceActivationRequest request) {
        return serviceService.activation(
                parseId(projectId), parseId(serviceId), request);
    }

    /**
     * ID malformado (de project ou de service) não pode cair em
     * {@code MethodArgumentTypeMismatchException} (que o handler genérico
     * traduziria como 400): é o mesmo 404 genérico de recurso inexistente —
     * anti-enumeração, igual ao resto da API.
     */
    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
        }
    }
}
