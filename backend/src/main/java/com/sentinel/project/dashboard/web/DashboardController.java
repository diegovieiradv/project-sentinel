package com.sentinel.project.dashboard.web;

import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.web.TenantScoped;
import com.sentinel.project.dashboard.dto.DashboardResponse;
import com.sentinel.project.dashboard.service.DashboardQueryService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint do painel operacional (Prompt 16):
 * {@code GET .../projects/{projectId}/dashboard?range=24h}.
 *
 * <p>Mesmo arranjo dos endpoints de leitura dos prompts anteriores: rota
 * {@code @TenantScoped} (a membership é validada pelo
 * {@code TenantInterceptor} ANTES do handler), id parseado à mão (UUID
 * malformado → o MESMO 404 genérico do resto da API) e somente GET — um
 * POST/PUT/PATCH na rota cai no contrato global de 405.</p>
 *
 * <p>Sem checagem de papel no handler: leitura é livre para qualquer papel
 * (ADMIN/OPERATOR/VIEWER) e o escopo de organização já saiu do caminho da
 * requisição.</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/dashboard")
public class DashboardController {

    private final DashboardQueryService dashboardQueryService;

    public DashboardController(DashboardQueryService dashboardQueryService) {
        this.dashboardQueryService = dashboardQueryService;
    }

    /**
     * GET → 200 resposta única do painel (saúde, disponibilidade, alertas,
     * incidentes, serviços em atenção e atividade recente).
     *
     * @param projectId id do projeto dentro do tenant
     * @param range     faixa opcional: {@code 1h | 6h | 24h | 7d | 30d}
     */
    @GetMapping
    @TenantScoped
    public DashboardResponse dashboard(
            @PathVariable String projectId,
            @RequestParam(name = "range", required = false) String range) {
        return dashboardQueryService.dashboard(parseId(projectId), range);
    }

    /** ID malformado → o MESMO 404 genérico do resto da API (anti-enumeração). */
    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
        }
    }
}
