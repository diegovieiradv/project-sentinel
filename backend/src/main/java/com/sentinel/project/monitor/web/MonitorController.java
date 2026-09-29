package com.sentinel.project.monitor.web;

import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.web.TenantScoped;
import com.sentinel.project.monitor.dto.MonitorActivationRequest;
import com.sentinel.project.monitor.dto.MonitorCreateRequest;
import com.sentinel.project.monitor.dto.MonitorResponse;
import com.sentinel.project.monitor.dto.MonitorUpdateRequest;
import com.sentinel.project.monitor.model.MonitorMethod;
import com.sentinel.project.monitor.service.MonitorService;

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
 * Endpoints de monitor (Prompt 08) — sub-recurso de serviço:
 * {@code /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/monitors}.
 *
 * <p>Todos os handlers são {@code @TenantScoped}: o
 * {@link com.sentinel.organization.web.TenantInterceptor} valida a membership
 * ANTES do handler e devolve 404 genérico a não-membros. O handler não
 * recebe organização, projeto, serviço nem papel como domínio: a organização
 * já está no {@link com.sentinel.organization.context.TenantContext} e a
 * cadeia Project → Service → Monitor é resolvida no service com lookup
 * tenant-scoped. Os DTOs nem têm campos de escopo — impossível criar monitor
 * "em outro serviço" por payload.</p>
 *
 * <p><b>Sem {@code @AuthenticationPrincipal}</b>: todo handler passa pelo
 * interceptor, que já exige um principal {@code User} e responde {@code 401}
 * antes da resolução de argumentos (mesmo arranjo de
 * {@code ServiceController}).</p>
 *
 * <p><b>{@code @Valid} só no POST</b> (igual ao de serviço): o create tem
 * defaults e campos obrigatórios fixos, então a validação de bean roda antes
 * do service. No PATCH o domínio é quem distingue "ausente" de "em branco" —
 * um {@code @Valid} aqui marcaria ausência como erro. Na prática a ordem do
 * create é: bean validation do controller (nome) → role → cadeia → pais →
 * SSRF → payload → unicidade.</p>
 *
 * <p><b>{@code method} como query param tipado:</b> valor fora de
 * {@code GET}/{@code HEAD} vira {@code MethodArgumentTypeMismatchException} e
 * o handler global responde o {@code 400} genérico
 * ("Invalid request parameter"), nunca 500.</p>
 *
 * <p><b>Sem DELETE</b> (nem handler, nem soft-delete implícito): a remoção
 * não existe neste prompt — desativação é o {@code .../activation}. Um DELETE
 * na rota cai naturalmente no contrato global de {@code 405}.</p>
 */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/monitors")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /** POST {name, url, method?, intervalSeconds?, timeoutMillis?} → 201. */
    @PostMapping
    @TenantScoped
    public ResponseEntity<MonitorResponse> create(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @Valid @RequestBody MonitorCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(monitorService.create(parseId(projectId), parseId(serviceId), request));
    }

    /**
     * GET → 200 página no formato Spring Page (qualquer papel; pais inativos
     * continuam legíveis).
     *
     * @param page   0-based, default {@code 0}
     * @param size   itens por página, default {@code 20}, máximo {@code 100}
     * @param sort   allowlist {@code createdAt|name|id} + direção, default
     *               {@code createdAt,asc}; o desempate {@code id asc} é fixo
     * @param active filtro opcional por participação no catálogo
     * @param method filtro opcional por método (valor fora do enum → 400,
     *               nunca 500)
     * @param name   busca opcional por nome (case-insensitive, subcadeia)
     */
    @GetMapping
    @TenantScoped
    public Page<MonitorResponse> list(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "method", required = false) MonitorMethod method,
            @RequestParam(name = "name", required = false) String name) {
        return monitorService.list(
                parseId(projectId), parseId(serviceId), page, size, sort, active, method, name);
    }

    /** GET /{monitorId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @GetMapping("/{monitorId}")
    @TenantScoped
    public MonitorResponse get(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String monitorId) {
        return monitorService.get(parseId(projectId), parseId(serviceId), parseId(monitorId));
    }

    /** PATCH {name?, url?, method?, intervalSeconds?, timeoutMillis?} → 200. */
    @PatchMapping("/{monitorId}")
    @TenantScoped
    public MonitorResponse update(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String monitorId,
            @RequestBody MonitorUpdateRequest request) {
        return monitorService.update(
                parseId(projectId), parseId(serviceId), parseId(monitorId), request);
    }

    /**
     * PATCH {active: boolean} → 200, idempotente (ADMIN/OPERATOR). Ativar
     * revalida o alvo pelo SSRF guard; desativar nunca exige pais ativos.
     */
    @PatchMapping("/{monitorId}/activation")
    @TenantScoped
    public MonitorResponse activation(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String monitorId,
            @Valid @RequestBody MonitorActivationRequest request) {
        return monitorService.activation(
                parseId(projectId), parseId(serviceId), parseId(monitorId), request);
    }

    /**
     * ID malformado (project, service ou monitor) não pode cair em
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
