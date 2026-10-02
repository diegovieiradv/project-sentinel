package com.sentinel.project.alert.web;

import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.web.TenantScoped;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.service.AlertService;
import com.sentinel.project.alert.web.dto.AlertResponse;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de alertas disparados (Prompt 13) — sub-recurso de serviço:
 * {@code /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/alerts}.
 *
 * <p><b>SOMENTE LEITURA:</b> os únicos handlers são GET (listagem e
 * detalhe). Não existe {@code POST} (alertas não são criados pela API — só
 * pelo Alert Engine), não existe {@code PATCH} (o ciclo de vida é exclusivo do
 * engine/lifecycle) e não existe {@code DELETE} (histórico é imutável). Os
 * três verbos caem naturalmente no contrato global de {@code 405} com o shape
 * {@code {code, message, timestamp, path}} — exercitado pelos testes.</p>
 *
 * <p>Todos os handlers são {@code @TenantScoped}: o
 * {@link com.sentinel.organization.web.TenantInterceptor} valida a membership
 * ANTES do handler (404 genérico a não-membros). A cadeia Project → Service →
 * Alert é resolvida no service com lookup tenant-scoped.</p>
 *
 * <p><b>Enums como query param tipado:</b> {@code status}/{@code severity}
 * fora do enum viram {@code MethodArgumentTypeMismatchException} → 400
 * genérico pelo handler global, nunca 500 e nunca erro de banco.</p>
 *
 * <p><b>Leitura livre para qualquer papel</b> — inclusive com projeto/serviço
 * inativos: alerta é histórico e a UI precisa mostrá-lo depois do
 * congelamento.</p>
 */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * GET → 200 página no formato Spring Page, ordem fixa
     * {@code startedAt desc, id desc}.
     *
     * @param page     0-based, default {@code 0}
     * @param size     itens por página, default {@code 20}, máximo {@code 100}
     * @param status   filtro opcional por status (FIRING/RESOLVED)
     * @param severity filtro opcional por severidade (SEV1–SEV4)
     */
    @GetMapping
    @TenantScoped
    public Page<AlertResponse> list(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) AlertStatus status,
            @RequestParam(name = "severity", required = false) AlertSeverity severity) {
        return alertService.list(parseId(projectId), parseId(serviceId), page, size, status, severity);
    }

    /** GET /{alertId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @GetMapping("/{alertId}")
    @TenantScoped
    public AlertResponse get(
            @PathVariable String projectId,
            @PathVariable String serviceId,
            @PathVariable String alertId) {
        return alertService.get(parseId(projectId), parseId(serviceId), parseId(alertId));
    }

    /**
     * ID malformado (project, service ou alert) não pode cair em
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
