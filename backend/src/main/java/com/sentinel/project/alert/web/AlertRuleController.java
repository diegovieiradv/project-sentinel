package com.sentinel.project.alert.web;

import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.web.TenantScoped;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.service.AlertRuleService;
import com.sentinel.project.alert.web.dto.AlertRuleActivationRequest;
import com.sentinel.project.alert.web.dto.AlertRuleCreateRequest;
import com.sentinel.project.alert.web.dto.AlertRuleResponse;
import com.sentinel.project.alert.web.dto.AlertRuleUpdateRequest;

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
 * Endpoints de alert rule (Prompt 12) — sub-recurso de serviço:
 * {@code /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/alert-rules}.
 *
 * <p>Todos os handlers são {@code @TenantScoped}: o
 * {@link com.sentinel.organization.web.TenantInterceptor} valida a membership
 * ANTES do handler e devolve 404 genérico a não-membros. O handler não
 * recebe organização, projeto, serviço nem papel como domínio: a organização
 * já está no {@link com.sentinel.organization.context.TenantContext} e a
 * cadeia Project → Service → AlertRule é resolvida no service com lookup
 * tenant-scoped. Os DTOs nem têm campos de escopo — impossível criar regra
 * "em outro serviço" por payload.</p>
 *
 * <p><b>{@code @Valid} só no POST</b> (mesmo arranjo de
 * {@code MonitorController}): o create tem campos obrigatórios fixos
 * (name/type/operator/window/severity), então a validação de bean roda antes
 * do service. No PATCH o domínio é quem distingue "ausente" de "em branco" —
 * um {@code @Valid} aqui marcaria ausência como erro.</p>
 *
 * <p><b>Enums como query param tipado:</b> valor fora de
 * {@code AlertRuleType}/{@code AlertSeverity} vira
 * {@code MethodArgumentTypeMismatchException} e o handler global responde o
 * {@code 400} genérico ("Invalid request parameter"), nunca 500. O mesmo
 * vale para {@code monitorId} malformado.</p>
 *
 * <p><b>Sem DELETE</b> (nem handler, nem soft-delete implícito): a remoção
 * não existe neste prompt — desativação é o {@code .../activation}. Um DELETE
 * na rota cai naturalmente no contrato global de {@code 405}.</p>
 *
 * <p><b>Sem avaliação:</b> nenhum endpoint deste controller executa, avalia
 * ou dispara a regra — CRUD de configuração é todo o alcance do Prompt 12.</p>
 */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/alert-rules")
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    public AlertRuleController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    /** POST {name, type, operator, threshold?, window, severity, monitorId?} → 201. */
    @PostMapping
    @TenantScoped
    public ResponseEntity<AlertRuleResponse> create(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @Valid @RequestBody AlertRuleCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(alertRuleService.create(parseId(projectId), parseId(serviceId), request));
    }

    /**
     * GET → 200 página no formato Spring Page (qualquer papel; pais inativos
     * e regras inativas continuam legíveis).
     *
     * @param page     0-based, default {@code 0}
     * @param size     itens por página, default {@code 20}, máximo {@code 100}
     * @param sort     allowlist {@code createdAt|name|id} + direção, default
     *                 {@code createdAt,asc}; o desempate {@code id asc} é fixo
     * @param active   filtro opcional por participação no catálogo
     * @param type     filtro opcional por tipo (valor fora do enum → 400)
     * @param severity filtro opcional por severidade (valor fora do enum → 400)
     * @param monitorId filtro opcional por escopo de monitor (presente →
     *                  restringe àquele monitor; ausente → todos os escopos)
     * @param name     busca opcional por nome (case-insensitive, subcadeia)
     */
    @GetMapping
    @TenantScoped
    public Page<AlertRuleResponse> list(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "type", required = false) AlertRuleType type,
            @RequestParam(name = "severity", required = false) AlertSeverity severity,
            @RequestParam(name = "monitorId", required = false) UUID monitorId,
            @RequestParam(name = "name", required = false) String name) {
        return alertRuleService.list(
                parseId(projectId),
                parseId(serviceId),
                page,
                size,
                sort,
                active,
                type,
                severity,
                monitorId,
                name);
    }

    /** GET /{ruleId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @GetMapping("/{ruleId}")
    @TenantScoped
    public AlertRuleResponse get(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String ruleId) {
        return alertRuleService.get(parseId(projectId), parseId(serviceId), parseId(ruleId));
    }

    /**
     * PATCH {name?, type?, operator?, thresholdNumeric?,
     * thresholdStatus?, window?, severity?, monitorId?} → 200. Campo ausente
     * não altera; {@code "monitorId": null} limpa o escopo (Service-level);
     * campos fora do contrato — server-owned ({@code id}, {@code serviceId},
     * {@code active}, timestamps) e chaves desconhecidas — não existem no DTO
     * e são <b>ignorados</b> se enviados (convenção do projeto, idem
     * {@code MonitorUpdateRequest}/{@code ServiceUpdateRequest}; fixado por
     * {@code AlertRulePatchTest.patchIgnoresServerOwnedFields}).
     */
    @PatchMapping("/{ruleId}")
    @TenantScoped
    public AlertRuleResponse update(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String ruleId,
            @RequestBody AlertRuleUpdateRequest request) {
        return alertRuleService.update(
                parseId(projectId), parseId(serviceId), parseId(ruleId), request);
    }

    /**
     * PATCH {active: boolean} → 200, idempotente (ADMIN/OPERATOR). Ativar
     * exige pais ativos; desativar nunca exige (fail-safe).
     */
    @PatchMapping("/{ruleId}/activation")
    @TenantScoped
    public AlertRuleResponse activation(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String ruleId,
            @Valid @RequestBody AlertRuleActivationRequest request) {
        return alertRuleService.activation(
                parseId(projectId), parseId(serviceId), parseId(ruleId), request);
    }

    /**
     * ID malformado (project, service ou rule) não pode cair em
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
