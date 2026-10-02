package com.sentinel.project.alert.service;

import java.util.UUID;

import com.sentinel.alerting.AlertLifecycleService;
import com.sentinel.error.ApiException;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.model.Role;
import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.repository.AlertRepository;
import com.sentinel.project.alert.web.dto.AlertResponse;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.Service;
import com.sentinel.project.repository.ProjectRepository;
import com.sentinel.project.repository.ServiceRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura dos alertas disparados (Prompt 13) — API somente-GET do resultado
 * avaliado do Alert Engine.
 *
 * <p><b>Somente leitura, sem exceção:</b> nenhum método cria, edita ou
 * resolve alerta. A escrita é exclusiva do {@code AlertLifecycleService}
 * (chamado pelo engine e pelas rotas de desativação) — a API não tem
 * {@code POST}, {@code PATCH} nem {@code DELETE} de alerta, e o contrato de
 * {@code 405} é exercitado pelos testes.</p>
 *
 * <p><b>Anti-IDOR, mesma cadeia dos prompts anteriores:</b> projeto no tenant
 * → serviço no projeto ({@code 404} genérico, nessa ordem) e depois o alerta
 * DENTRO do serviço. Nada aqui lê {@code findById} avulso.</p>
 *
 * <p><b>Leitura livre e pais inativos legíveis:</b> qualquer papel vê os
 * alertas; projeto/serviço desligados continuam consultáveis (histórico é
 * histórico — inclusive para conferir o que ficou aberto antes do
 * congelamento).</p>
 *
 * <p><b>Ordem fixa "mais recente primeiro":</b> {@code startedAt DESC, id
 * DESC} — sem parâmetro {@code sort} (a lista de alertas não é ordenável pelo
 * cliente: a pergunta operacional é sempre "o que está/ficou disparado por
 * último"). O desempate em {@code id} torna a página determinística.</p>
 *
 * <p><b>Conflito de nomes</b> ({@code Service} × {@code @Service} do Spring):
 * mesmo arranjo do {@code AlertRuleService} — entidade importada, estereótipo
 * com nome completo.</p>
 */
// Arranjo do conflito de nomes: entidade Service importada acima, estereótipo
// do Spring com FQN aqui (única ocorrência deste módulo).
@org.springframework.stereotype.Service
public class AlertService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AlertRepository alertRepository;
    private final ServiceRepository serviceRepository;
    private final ProjectRepository projectRepository;

    public AlertService(
            AlertRepository alertRepository,
            ServiceRepository serviceRepository,
            ProjectRepository projectRepository) {
        this.alertRepository = alertRepository;
        this.serviceRepository = serviceRepository;
        this.projectRepository = projectRepository;
    }

    // --- GET .../alerts -----------------------------------------------------

    /**
     * Página de alertas do serviço com os filtros opcionais status/severity e
     * ordem fixa mais-recente-primeiro.
     *
     * @param status  filtro opcional por status (valor fora do enum → 400 no
     *                controller, antes daqui)
     * @param severity filtro opcional por severidade
     */
    @Transactional(readOnly = true)
    public Page<AlertResponse> list(
            UUID projectId,
            UUID serviceId,
            int page,
            int size,
            AlertStatus status,
            AlertSeverity severity) {
        Service service = requireParents(projectId, serviceId);
        Pageable pageable = PageRequest.of(
                validatePage(page), validateSize(size), Sort.by(Sort.Order.desc("startedAt"),
                        Sort.Order.desc("id")));

        Page<Alert> alerts = alertRepository.search(service.getId(), status, severity, pageable);
        Role role = TenantContext.role();
        return alerts.map(alert -> AlertResponse.from(alert, role));
    }

    /** GET /{alertId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @Transactional(readOnly = true)
    public AlertResponse get(UUID projectId, UUID serviceId, UUID alertId) {
        Service service = requireParents(projectId, serviceId);
        Alert alert = alertRepository
                .findByServiceIdAndId(service.getId(), alertId)
                .orElseThrow(AlertService::notFound);
        return AlertResponse.from(alert, TenantContext.role());
    }

    // --- cadeia Organization → Project → Service -----------------------------

    /** Projeto dentro do tenant corrente — id alheio/inexistente → 404 genérico. */
    private Project findProjectInTenant(UUID projectId) {
        return projectRepository
                .findByOrganizationIdAndId(TenantContext.organizationId(), projectId)
                .orElseThrow(AlertService::notFound);
    }

    /** Projeto + serviço, na ORDEM: 404 genérico em qualquer nível ausente. */
    private Service requireParents(UUID projectId, UUID serviceId) {
        Project project = findProjectInTenant(projectId);
        return serviceRepository
                .findByProjectIdAndId(project.getId(), serviceId)
                .orElseThrow(AlertService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }

    // --- paginação ----------------------------------------------------------

    /** {@code PageRequest.of} lançaria {@link IllegalArgumentException} → 500. */
    private static int validatePage(int page) {
        if (page < 0) {
            throw new ApiException("VALIDATION_ERROR", "page: must not be negative", HttpStatus.BAD_REQUEST);
        }
        return page;
    }

    private static int validateSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "size: must be between 1 and " + MAX_PAGE_SIZE,
                    HttpStatus.BAD_REQUEST);
        }
        return size;
    }
}
