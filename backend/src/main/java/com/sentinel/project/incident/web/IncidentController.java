package com.sentinel.project.incident.web;

import java.util.List;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.web.TenantScoped;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.web.dto.AlertResponse;
import com.sentinel.project.incident.model.IncidentStatus;
import com.sentinel.project.incident.service.IncidentService;
import com.sentinel.project.incident.web.dto.IncidentCreateRequest;
import com.sentinel.project.incident.web.dto.IncidentEventResponse;
import com.sentinel.project.incident.web.dto.IncidentResponse;
import com.sentinel.project.incident.web.dto.IncidentStatusRequest;
import com.sentinel.project.incident.web.dto.IncidentUpdateRequest;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de incidente (Prompt 14) — sub-recurso de serviço:
 * {@code /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/incidents}.
 *
 * <p>Nove handlers, um para cada operação do contrato: criar, listar, detalhar,
 * editar conteúdo, mudar status, ler a timeline, listar/vincular/desvincular
 * alertas. <b>Não existe DELETE de incidente</b> — a remoção não existe neste
 * prompt (o histórico é imutável); um DELETE na rota do incidente cai
 * naturalmente no contrato global de {@code 405}. O único DELETE é o de
 * <i>associação</i> ({@code .../alerts/{alertId}}), que desvincula sem apagar
 * nada.</p>
 *
 * <p>Todos os handlers são {@code @TenantScoped}: o
 * {@link com.sentinel.organization.web.TenantInterceptor} valida a membership
 * ANTES do handler (404 genérico a não-membros). O handler não recebe
 * organização, papel nem escopo como domínio: a organização já está no
 * {@link com.sentinel.organization.context.TenantContext} e a cadeia
 * Project → Service → Incident é resolvida no service com lookup
 * tenant-scoped. Os DTOs nem têm campos de escopo — impossível criar incidente
 * "em outro serviço" por payload.</p>
 *
 * <p><b>{@code @Valid} só no POST e no /status</b> (mesmo arranjo de
 * {@code AlertRuleController}): o create tem campos obrigatórios fixos
 * (title/severity) e o status exige um destino não-nulo. No PATCH de conteúdo
 * o domínio é quem distingue "ausente" de "em branco" — um {@code @Valid}
 * aqui marcaria ausência como erro.</p>
 *
 * <p><b>Enums como query param tipado:</b> {@code status}/{@code severity}
 * fora do enum viram {@code MethodArgumentTypeMismatchException} → 400
 * genérico pelo handler global, nunca 500 e nunca erro de banco.</p>
 *
 * <p><b>Leitura livre para qualquer papel</b> — inclusive com projeto/serviço
 * inativos (a história continua consultável); mutação exige ADMIN/OPERATOR no
 * service.</p>
 */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    /** POST {title, description?, severity, startedAt?} → 201 (nasce OPEN). */
    @PostMapping
    @TenantScoped
    public ResponseEntity<IncidentResponse> create(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @Valid @RequestBody IncidentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(incidentService.create(parseId(projectId), parseId(serviceId), request));
    }

    /**
     * GET → 200 página no formato Spring Page, ordem fixa
     * {@code startedAt desc, id desc}.
     *
     * @param page     0-based, default {@code 0}
     * @param size     itens por página, default {@code 20}, máximo {@code 100}
     * @param status   filtro opcional por status (OPEN/INVESTIGATING/…)
     * @param severity filtro opcional por severidade (SEV1–SEV4)
     */
    @GetMapping
    @TenantScoped
    public Page<IncidentResponse> list(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) IncidentStatus status,
            @RequestParam(name = "severity", required = false) AlertSeverity severity) {
        return incidentService.list(parseId(projectId), parseId(serviceId), page, size, status, severity);
    }

    /** GET /{incidentId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @GetMapping("/{incidentId}")
    @TenantScoped
    public IncidentResponse get(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String incidentId) {
        return incidentService.get(parseId(projectId), parseId(serviceId), parseId(incidentId));
    }

    /**
     * PATCH {title?, description?, severity?} → 200. Campo ausente não altera;
     * {@code ""} na descrição limpa; campos server-owned ({@code id},
     * {@code serviceId}, {@code status}, {@code startedAt}, {@code resolvedAt},
     * {@code createdByUserId}, {@code version}) e chaves desconhecidas não
     * existem no DTO e são <b>ignorados</b> se enviados (convenção do projeto;
     * fixado por {@code IncidentApiTest.patchIgnoresServerOwnedFields}).
     */
    @PatchMapping("/{incidentId}")
    @TenantScoped
    public IncidentResponse update(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String incidentId,
            @RequestBody IncidentUpdateRequest request) {
        return incidentService.update(
                parseId(projectId), parseId(serviceId), parseId(incidentId), request);
    }

    /** PATCH {status} → 200 | 409 INCIDENT_STATUS_TRANSITION (não idempotente). */
    @PatchMapping("/{incidentId}/status")
    @TenantScoped
    public IncidentResponse changeStatus(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String incidentId,
            @Valid @RequestBody IncidentStatusRequest request) {
        return incidentService.changeStatus(
                parseId(projectId), parseId(serviceId), parseId(incidentId), request.status());
    }

    /**
     * GET → 200 timeline <b>append-only</b> na ordem
     * {@code createdAt asc, id asc}. Não existe PATCH/DELETE de evento: o log
     * é fato histórico.
     */
    @GetMapping("/{incidentId}/events")
    @TenantScoped
    public List<IncidentEventResponse> listEvents(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String incidentId) {
        return incidentService.listEvents(
                parseId(projectId), parseId(serviceId), parseId(incidentId));
    }

    /**
     * GET → 200 página de alertas vinculados ao incidente (mesmo
     * {@code page}/{@code size} do resto da API, ordem
     * {@code startedAt desc, id desc}).
     */
    @GetMapping("/{incidentId}/alerts")
    @TenantScoped
    public Page<AlertResponse> listAlerts(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String incidentId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return incidentService.listAlerts(
                parseId(projectId), parseId(serviceId), parseId(incidentId), page, size);
    }

    /**
     * PUT (associação, não "criação") → 200 com o incidente. Idempotente: já
     * vinculado a ESTE incidente devolve 200 sem evento novo;
     * {@code 409 ALERT_ALREADY_LINKED} quando o alerta é de OUTRO.
     */
    @PutMapping("/{incidentId}/alerts/{alertId}")
    @TenantScoped
    public IncidentResponse linkAlert(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String incidentId,
            @PathVariable String alertId) {
        return incidentService.linkAlert(
                parseId(projectId), parseId(serviceId), parseId(incidentId), parseId(alertId));
    }

    /**
     * DELETE da associação → 204 sem corpo (padrão de DELETE do projeto).
     * Idempotente: se o alerta não pertencia a ESTE incidente, nada muda e
     * nada é registrado. Nunca apaga o alerta — só o vínculo.
     */
    @DeleteMapping("/{incidentId}/alerts/{alertId}")
    @TenantScoped
    public ResponseEntity<Void> unlinkAlert(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String incidentId,
            @PathVariable String alertId) {
        incidentService.unlinkAlert(
                parseId(projectId), parseId(serviceId), parseId(incidentId), parseId(alertId));
        return ResponseEntity.noContent().build();
    }

    /**
     * ID malformado (project, service, incident ou alert) não pode cair em
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
