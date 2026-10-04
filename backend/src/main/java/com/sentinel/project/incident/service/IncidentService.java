package com.sentinel.project.incident.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.monitoring.Clock;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.model.Role;
import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.repository.AlertRepository;
import com.sentinel.project.alert.web.dto.AlertResponse;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentEvent;
import com.sentinel.project.incident.model.IncidentEventType;
import com.sentinel.project.incident.model.IncidentStatus;
import com.sentinel.project.incident.repository.IncidentEventRepository;
import com.sentinel.project.incident.repository.IncidentRepository;
import com.sentinel.project.incident.web.dto.IncidentCreateRequest;
import com.sentinel.project.incident.web.dto.IncidentEventResponse;
import com.sentinel.project.incident.web.dto.IncidentResponse;
import com.sentinel.project.incident.web.dto.IncidentUpdateRequest;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.Service;
import com.sentinel.project.repository.ProjectRepository;
import com.sentinel.project.repository.ServiceRepository;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incidentes (Prompt 14) — quarto nível da hierarquia
 * Organization → Project → Service → Incident, com a timeline append-only do
 * ciclo de vida manual.
 *
 * <p><b>Nascido de um humano:</b> só existe {@code POST}; nada em
 * {@code com.sentinel.alerting} referencia este pacote (travado por
 * {@code MonitoringEngineBoundaryTest.alertingPackageHasNoIncidentReferences}),
 * não há trigger/listener/scheduler e nenhum alerta vira incidente sozinho —
 * a associação é opcional, invertida ({@code alerts.incident_id}) e feita
 * DEPOIS, por este service.</p>
 *
 * <p><b>Ordem das checagens (contrato público, ver docs/incidents.md):</b></p>
 * <ol>
 *   <li>role — mutação exige {@code ADMIN}/{@code OPERATOR} ({@code 403});</li>
 *   <li>projeto no tenant ({@code 404}) → serviço no projeto ({@code 404}),
 *       nessa ordem, para valer a cadeia sem consulta global;</li>
 *   <li>pais ATIVOS apenas em mutação ({@code 409 PROJECT_INACTIVE} antes de
 *       {@code 409 SERVICE_INACTIVE}) — a leitura (list/get/events/alerts)
 *       é sempre permitida, mesmo com pais inativos;</li>
 *   <li>incidente ({@code 404} genérico — inexistente, de outro serviço ou de
 *       outra organização responde igual, anti-enumeração);</li>
 *   <li>imutabilidade pós-{@code RESOLVED} ({@code 409 INCIDENT_RESOLVED})
 *       nas mutações de conteúdo e de associação; a rota de status é decidida
 *       pela máquina de estados ({@code 409 INCIDENT_STATUS_TRANSITION});</li>
 *   <li>validação de domínio ({@code 400 VALIDATION_ERROR}) — título e
 *       descrição com trim + limite da coluna, {@code startedAt} dentro da
 *       janela aceitável;</li>
 *   <li>cardinalidade do alerta ({@code 409 ALERT_ALREADY_LINKED}).</li>
 * </ol>
 * <p><b>Salvo no create:</b> o bean validation do {@code @Valid} do POST roda
 * no CONTROLLER, antes de qualquer coisa desta classe.</p>
 *
 * <p><b>Transação:</b> toda mutação grava a mudança E as linhas da timeline
 * na MESMA transação — não existe estado em que o incidente mudou sem o evento
 * que explica a mudança (ou o contrário).</p>
 *
 * <p><b>Concorrência:</b> {@code Incident} tem {@code @Version}; o JPA lança
 * {@link OptimisticLockingFailureException} quando dois PATCHs disputam a
 * mesma linha e o {@code GlobalExceptionHandler} traduz em {@code 409}. O
 * vínculo de alerta é protegido pela barreira da aplicação: UPDATE
 * condicional ({@code WHERE incident_id IS NULL}) + releitura/classificação
 * em {@code 0 linhas}; o índice {@code idx_alerts_incident} da V11 é
 * <b>não-único</b> e existe só para a listagem.</p>
 *
 * <p><b>Conflito de nomes</b> ({@code Service} × {@code @Service} do Spring):
 * mesmo arranjo do {@code AlertRuleService} — entidade importada, estereótipo
 * com nome completo.</p>
 */
// Arranjo do conflito de nomes: entidade Service importada acima, estereótipo
// do Spring com FQN aqui (única ocorrência deste módulo).
@org.springframework.stereotype.Service
public class IncidentService {

    /** Mensagens do contrato público da API. */
    public static final String PROJECT_INACTIVE_MESSAGE =
            "Cannot create or modify incidents in an inactive project";

    public static final String SERVICE_INACTIVE_MESSAGE =
            "Cannot create or modify incidents in an inactive service";

    public static final String INCIDENT_RESOLVED_MESSAGE =
            "Incident is resolved and can no longer be modified";

    public static final String ALERT_ALREADY_LINKED_MESSAGE =
            "Alert is already linked to another incident";

    public static final String CONFLICT_MESSAGE =
            "The resource was updated by another request";

    private static final int MAX_PAGE_SIZE = 100;

    /** Janela de {@code startedAt} aceita no create (relógio do domínio). */
    private static final Duration MAX_STARTED_AT_IN_FUTURE = Duration.ofMinutes(5);

    private static final Duration MAX_STARTED_AT_IN_PAST = Duration.ofDays(30);

    /** Folha mínima entre dois eventos do MESMO request (TIMESTAMPTZ guarda µs). */
    private static final Duration EVENT_STAMP_GAP = Duration.ofMillis(1);

    private final IncidentRepository incidentRepository;
    private final IncidentEventRepository incidentEventRepository;
    private final AlertRepository alertRepository;
    private final ServiceRepository serviceRepository;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    public IncidentService(
            IncidentRepository incidentRepository,
            IncidentEventRepository incidentEventRepository,
            AlertRepository alertRepository,
            ServiceRepository serviceRepository,
            ProjectRepository projectRepository,
            Clock clock) {
        this.incidentRepository = incidentRepository;
        this.incidentEventRepository = incidentEventRepository;
        this.alertRepository = alertRepository;
        this.serviceRepository = serviceRepository;
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    // --- POST .../incidents -------------------------------------------------

    /**
     * Cria o incidente já vinculado ao serviço do path (o DTO nem tem campos
     * de escopo), nasce {@link IncidentStatus#OPEN} com {@code resolvedAt}
     * nulo e grava a linha {@code CREATED} da timeline na MESMA transação.
     *
     * <p>{@code startedAt} vem do payload ou do relógio do domínio; no primeiro
     * caso precisa caber numa janela razoável — um cliente com relógio
     * descalado não pode criar incidente "daqui a três anos" nem reescrever a
     * história com uma data de 1970.</p>
     */
    @Transactional
    public IncidentResponse create(UUID projectId, UUID serviceId, IncidentCreateRequest request) {
        requireMutationRole();
        Service service = requireActiveParents(projectId, serviceId);

        String title = validateTitle(request.title());
        String description = validateDescription(request.description());
        AlertSeverity severity = request.severity();
        if (severity == null) {
            throw new ApiException("VALIDATION_ERROR", "severity: must not be null", HttpStatus.BAD_REQUEST);
        }
        Instant startedAt = validateStartedAt(request.startedAt());

        UUID actor = TenantContext.userId();
        Incident incident = incidentRepository.saveAndFlush(new Incident(
                service, title, description, severity, startedAt, actor));
        emit(incident, IncidentEventType.CREATED, "Incident created", actor);
        return IncidentResponse.from(incident, TenantContext.role());
    }

    // --- GET .../incidents --------------------------------------------------

    /**
     * Página de incidentes do serviço com os dois filtros opcionais
     * (status/severity) e ordem fixa {@code startedAt DESC, id DESC}. Qualquer
     * papel pode ler — inclusive quando projeto/serviço estão inativos (a
     * história continua consultável depois do congelamento).
     */
    @Transactional(readOnly = true)
    public Page<IncidentResponse> list(
            UUID projectId,
            UUID serviceId,
            int page,
            int size,
            IncidentStatus status,
            AlertSeverity severity) {
        Service service = requireParents(projectId, serviceId);
        Pageable pageable = PageRequest.of(
                validatePage(page), validateSize(size), Sort.by(Sort.Order.desc("startedAt"),
                        Sort.Order.desc("id")));

        Page<Incident> incidents = incidentRepository.search(service.getId(), status, severity, pageable);
        Role role = TenantContext.role();
        return incidents.map(incident -> IncidentResponse.from(incident, role));
    }

    /** GET /{incidentId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @Transactional(readOnly = true)
    public IncidentResponse get(UUID projectId, UUID serviceId, UUID incidentId) {
        Service service = requireParents(projectId, serviceId);
        return IncidentResponse.from(findIncident(service, incidentId), TenantContext.role());
    }

    // --- PATCH .../incidents/{incidentId} -----------------------------------

    /**
     * Atualização parcial de title/description/severity. Campo ausente
     * ({@code null}) não altera; {@code ""} na descrição <b>limpa</b>.
     *
     * <p><b>Um evento POR campo efetivamente alterado</b>, na ordem fixa
     * {@code title → description → severity} (a ordem de avaliação do
     * serviço — o status tem rota própria). No-op não escreve nem gera linha:
     * nenhum campo alterado → o {@code updatedAt} também não avança.</p>
     *
     * <p>{@code id}, {@code serviceId}, {@code status}, {@code startedAt},
     * {@code resolvedAt}, {@code createdByUserId} e {@code version} não existem
     * no DTO: servidor-owned, e o estado evolui só pela rota {@code /status}.</p>
     */
    @Transactional
    public IncidentResponse update(
            UUID projectId, UUID serviceId, UUID incidentId, IncidentUpdateRequest request) {
        requireMutationRole();
        Service service = requireActiveParents(projectId, serviceId);
        Incident incident = findIncident(service, incidentId);
        requireMutable(incident);

        UUID actor = TenantContext.userId();
        List<IncidentEvent> events = new ArrayList<>();
        Instant stamp = null;

        if (request.title() != null) {
            String title = validateTitle(request.title());
            if (!title.equals(incident.getTitle())) {
                incident.setTitle(title);
                stamp = nextEventStamp(stamp);
                events.add(eventOf(incident, IncidentEventType.TITLE_CHANGED, "Title changed", actor, stamp));
            }
        }
        if (request.description() != null) {
            String description = validateDescription(request.description());
            if (!Objects.equals(description, incident.getDescription())) {
                incident.setDescription(description);
                stamp = nextEventStamp(stamp);
                events.add(eventOf(
                        incident, IncidentEventType.DESCRIPTION_CHANGED, "Description changed", actor, stamp));
            }
        }
        if (request.severity() != null) {
            AlertSeverity severity = request.severity();
            if (severity != incident.getSeverity()) {
                AlertSeverity from = incident.getSeverity();
                incident.setSeverity(severity);
                stamp = nextEventStamp(stamp);
                events.add(eventOf(incident, IncidentEventType.SEVERITY_CHANGED,
                        "Severity changed from " + from + " to " + severity, actor, stamp));
            }
        }

        if (events.isEmpty()) {
            return IncidentResponse.from(incident, TenantContext.role()); // no-op, sem escrita
        }
        incidentRepository.saveAndFlush(incident);
        incidentEventRepository.saveAll(events);
        return IncidentResponse.from(incident, TenantContext.role());
    }

    // --- PATCH .../incidents/{incidentId}/status ----------------------------

    /**
     * Único caminho de mudança de estado — a máquina de estados de
     * {@link IncidentStatus} é a autoridade (transição inválida, autotransição
     * e qualquer saída de {@code RESOLVED} respondem
     * {@code 409 INCIDENT_STATUS_TRANSITION}). A transição e o evento
     * {@code STATUS_CHANGED} acontecem na mesma transação; {@code resolvedAt}
     * é preenchido pela entidade exatamente quando o destino é
     * {@link IncidentStatus#RESOLVED}.
     *
     * <p><b>Não é idempotente:</b> pedir o status que já está vigente é erro
     * (critério 17), não um 200 silencioso.</p>
     */
    @Transactional
    public IncidentResponse changeStatus(
            UUID projectId, UUID serviceId, UUID incidentId, IncidentStatus target) {
        requireMutationRole();
        Service service = requireActiveParents(projectId, serviceId);
        Incident incident = findIncident(service, incidentId);

        IncidentStatus from = incident.getStatus();
        if (!from.canTransitionTo(target)) {
            throw statusTransition(from, target);
        }
        UUID actor = TenantContext.userId();
        incident.transitionTo(target, clock.instant());
        incidentRepository.saveAndFlush(incident);
        emit(incident, IncidentEventType.STATUS_CHANGED,
                "Status changed from " + from + " to " + target, actor);
        return IncidentResponse.from(incident, TenantContext.role());
    }

    // --- GET .../incidents/{incidentId}/events ------------------------------

    /**
     * Timeline INTEIRA do incidente na ordem de emissão
     * ({@code createdAt ASC, id ASC} — índice da V11). Somente leitura: não
     * existe PATCH/DELETE de evento em nenhum caminho, o log é fato
     * histórico. Qualquer papel pode ler, inclusive com pais inativos.
     */
    @Transactional(readOnly = true)
    public List<IncidentEventResponse> listEvents(
            UUID projectId, UUID serviceId, UUID incidentId) {
        Service service = requireParents(projectId, serviceId);
        Incident incident = findIncident(service, incidentId);
        return incidentEventRepository.findByIncidentId(incident.getId()).stream()
                .map(IncidentEventResponse::from)
                .toList();
    }

    // --- GET .../incidents/{incidentId}/alerts ------------------------------

    /**
     * Alertas vinculados ao incidente, mais recentes primeiro, paginado no
     * banco (mesmo {@code page}/{@code size} do resto da API). Qualquer papel
     * pode ler; pais inativos continuam legíveis.
     */
    @Transactional(readOnly = true)
    public Page<AlertResponse> listAlerts(
            UUID projectId, UUID serviceId, UUID incidentId, int page, int size) {
        Service service = requireParents(projectId, serviceId);
        Incident incident = findIncident(service, incidentId);
        Pageable pageable = PageRequest.of(
                validatePage(page), validateSize(size), Sort.by(Sort.Order.desc("startedAt"),
                        Sort.Order.desc("id")));

        Page<Alert> alerts = alertRepository.searchByIncidentId(incident.getId(), pageable);
        Role role = TenantContext.role();
        return alerts.map(alert -> AlertResponse.from(alert, role));
    }

    // --- PUT / DELETE .../incidents/{incidentId}/alerts/{alertId} -----------

    /**
     * Vincula um alerta do MESMO serviço ao incidente.
     *
     * <p><b>Cardinalidade em três camadas:</b> o pré-check lê o vínculo atual
     * (já é nosso → 200 idempotente <b>sem</b> evento novo; é de outro
     * incidente → {@code 409 ALERT_ALREADY_LINKED}); o UPDATE condicional
     * ({@code WHERE incident_id IS NULL}) é a barreira da aplicação sob
     * corrida — {@code 0 linhas} acionam a releitura/classificação, que
     * decide {@code 200 idempotente} (o vínculo já é deste incidente) ou
     * {@code 409 ALERT_ALREADY_LINKED} (é de outro). O índice
     * {@code idx_alerts_incident} (não-único) existe só para a listagem.
     * Duas requests disputando o MESMO alerta para DOIS incidentes terminam
     * em {200, 409} — nunca dois vínculos.</p>
     *
     * <p><b>Nenhum outro campo do alerta é tocado:</b> a escrita é um UPDATE
     * em SQL puro na coluna {@code incident_id} — mensagem, severidade,
     * status e timestamps do alerta ficam byte-idênticos.</p>
     */
    @Transactional
    public IncidentResponse linkAlert(
            UUID projectId, UUID serviceId, UUID incidentId, UUID alertId) {
        requireMutationRole();
        Service service = requireActiveParents(projectId, serviceId);
        Incident incident = findIncident(service, incidentId);
        requireMutable(incident);
        Alert alert = findAlert(service, alertId);

        Incident owner = alert.getIncident(); // proxy LAZY: id não inicializa a entidade
        if (owner != null) {
            if (owner.getId().equals(incidentId)) {
                return IncidentResponse.from(incident, TenantContext.role()); // já é nosso
            }
            throw alertAlreadyLinked();
        }

        if (alertRepository.linkIncident(alertId, incident) == 1) {
            emit(incident, IncidentEventType.ALERT_LINKED, "Alert " + alertId + " linked",
                    TenantContext.userId());
            return IncidentResponse.from(incident, TenantContext.role());
        }

        // 0 linhas com o pré-check livre: outra request venceu a corrida.
        // A releitura (o @Modifying limpou o contexto) é quem classifica.
        Alert current = alertRepository.findByServiceIdAndId(service.getId(), alertId)
                .orElseThrow(IncidentService::notFound);
        Incident raced = current.getIncident();
        if (raced == null) {
            // o vínculo caiu entre o UPDATE e este SELECT — o condicional é a
            // autoridade, então tenta de novo antes de desistir
            if (alertRepository.linkIncident(alertId, incident) == 1) {
                emit(incident, IncidentEventType.ALERT_LINKED, "Alert " + alertId + " linked",
                        TenantContext.userId());
                return IncidentResponse.from(incident, TenantContext.role());
            }
            current = alertRepository.findByServiceIdAndId(service.getId(), alertId)
                    .orElseThrow(IncidentService::notFound);
            raced = current.getIncident();
            if (raced == null) {
                throw conflict();
            }
        }
        if (!raced.getId().equals(incidentId)) {
            throw alertAlreadyLinked();
        }
        return IncidentResponse.from(incident, TenantContext.role()); // idempotente
    }

    /**
     * Desvincula um alerta do incidente — <b>só pré-{@code RESOLVED}</b>
     * (histórico de um incidente encerrado não é reescrito).
     *
     * <p><b>Idempotente:</b> o UPDATE condicional ({@code WHERE incident =
     * :incident}) só desassocia se o alerta ainda pertence AQUELE incidente.
     * 0 linhas = nada a fazer → {@code 204} sem evento (não importa se o
     * alerta estava livre ou vinculado a OUTRO incidente: para ESTE nada
     * muda). {@code 204} é o padrão de DELETE do projeto (mesmo arranjo de
     * {@code DELETE .../members/{id}}).</p>
     */
    @Transactional
    public void unlinkAlert(
            UUID projectId, UUID serviceId, UUID incidentId, UUID alertId) {
        requireMutationRole();
        Service service = requireActiveParents(projectId, serviceId);
        Incident incident = findIncident(service, incidentId);
        requireMutable(incident);
        Alert alert = findAlert(service, alertId);

        if (alertRepository.unlinkIncident(alert.getId(), incident) == 1) {
            emit(incident, IncidentEventType.ALERT_UNLINKED, "Alert " + alertId + " unlinked",
                    TenantContext.userId());
        }
    }

    // --- autorização --------------------------------------------------------

    /**
     * Leitura é livre para qualquer papel; mutação exige ADMIN ou OPERATOR.
     * Papel lido do TenantContext (servidor) — nunca de token. Qualquer papel
     * sem permissão cai aqui com o MESMO 403 genérico, sem detalhar a política.
     */
    private static void requireMutationRole() {
        Role role = TenantContext.require().role();
        if (role != Role.ADMIN && role != Role.OPERATOR) {
            throw new ApiException("FORBIDDEN", "Access denied", HttpStatus.FORBIDDEN);
        }
    }

    /** {@code RESOLVED} é terminal: o conteúdo e as associações não mudam mais. */
    private static void requireMutable(Incident incident) {
        if (incident.getStatus().isResolved()) {
            throw new ApiException("INCIDENT_RESOLVED", INCIDENT_RESOLVED_MESSAGE, HttpStatus.CONFLICT);
        }
    }

    // --- cadeia Organization → Project → Service → Incident -----------------

    /** Projeto dentro do tenant corrente — id alheio/inexistente → 404 genérico. */
    private Project findProjectInTenant(UUID projectId) {
        return projectRepository
                .findByOrganizationIdAndId(TenantContext.organizationId(), projectId)
                .orElseThrow(IncidentService::notFound);
    }

    /** Projeto + serviço, na ORDEM: 404 genérico em qualquer nível ausente. */
    private Service findService(Project project, UUID serviceId) {
        return serviceRepository
                .findByProjectIdAndId(project.getId(), serviceId)
                .orElseThrow(IncidentService::notFound);
    }

    /** Escopo de leitura: cadeia provada, sem exigir pais ativos. */
    private Service requireParents(UUID projectId, UUID serviceId) {
        return findService(findProjectInTenant(projectId), serviceId);
    }

    /**
     * Escopo de mutação: cadeia provada (404 → 404) e depois pais ativos
     * (409 → 409), PROJECT_INACTIVE <b>antes</b> de SERVICE_INACTIVE.
     */
    private Service requireActiveParents(UUID projectId, UUID serviceId) {
        Project project = findProjectInTenant(projectId);
        Service service = findService(project, serviceId);
        if (!project.isActive()) {
            throw new ApiException("PROJECT_INACTIVE", PROJECT_INACTIVE_MESSAGE, HttpStatus.CONFLICT);
        }
        if (!service.isActive()) {
            throw new ApiException("SERVICE_INACTIVE", SERVICE_INACTIVE_MESSAGE, HttpStatus.CONFLICT);
        }
        return service;
    }

    /** Incidente dentro do serviço já resolvido no tenant → 404 genérico. */
    private Incident findIncident(Service service, UUID incidentId) {
        return incidentRepository
                .findByServiceIdAndId(service.getId(), incidentId)
                .orElseThrow(IncidentService::notFound);
    }

    /**
     * Alerta dentro do MESMO serviço ({@code 404} genérico — inexistente, de
     * outro serviço ou de outro tenant responde igual). Provar o escopo aqui
     * é o que impede vincular um alerta alheio a um incidente próprio.
     */
    private Alert findAlert(Service service, UUID alertId) {
        return alertRepository
                .findByServiceIdAndId(service.getId(), alertId)
                .orElseThrow(IncidentService::notFound);
    }

    // --- validação ----------------------------------------------------------

    /** Título: strip de controle + trim + limites da coluna (1–200). */
    private static String validateTitle(String raw) {
        // sanitizeText com teto intencionalmente ilimitado: aqui o contrato é
        // REJEITAR título longo (400), não truncar em silêncio.
        String title = Incident.sanitizeText(raw, Integer.MAX_VALUE);
        if (title.isEmpty()) {
            throw new ApiException("VALIDATION_ERROR", "title: must not be blank", HttpStatus.BAD_REQUEST);
        }
        if (title.length() > Incident.MAX_TITLE_LENGTH) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "title: must be at most " + Incident.MAX_TITLE_LENGTH + " characters",
                    HttpStatus.BAD_REQUEST);
        }
        return title;
    }

    /** Descrição: strip de controle + trim; vazia vira {@code null}; ≤4000. */
    private static String validateDescription(String raw) {
        String description = Incident.sanitizeText(raw, Integer.MAX_VALUE);
        if (description.length() > Incident.MAX_DESCRIPTION_LENGTH) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "description: must be at most " + Incident.MAX_DESCRIPTION_LENGTH + " characters",
                    HttpStatus.BAD_REQUEST);
        }
        return description.isEmpty() ? null : description;
    }

    /**
     * {@code startedAt} ausente → agora (relógio do domínio). Presente →
     * dentro da janela aceitável: não mais que 5 minutos no futuro (relógio
     * do cliente adiantado) nem mais que 30 dias no passado (não se abre
     * incidente reescrevendo a história).
     */
    private Instant validateStartedAt(Instant requested) {
        Instant now = clock.instant();
        if (requested == null) {
            return now;
        }
        if (requested.isAfter(now.plus(MAX_STARTED_AT_IN_FUTURE))) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "startedAt: must not be more than " + MAX_STARTED_AT_IN_FUTURE.toMinutes()
                            + " minutes in the future",
                    HttpStatus.BAD_REQUEST);
        }
        if (requested.isBefore(now.minus(MAX_STARTED_AT_IN_PAST))) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "startedAt: must not be older than " + MAX_STARTED_AT_IN_PAST.toDays() + " days",
                    HttpStatus.BAD_REQUEST);
        }
        return requested;
    }

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

    // --- timeline -----------------------------------------------------------

    /**
     * Evento de domínio com instante fornecido — o service decide o carimbo
     * para manter a ordem de emissão mesmo quando vários eventos nascem no
     * MESMO request.
     */
    private static IncidentEvent eventOf(
            Incident incident,
            IncidentEventType type,
            String message,
            UUID actor,
            Instant createdAt) {
        return new IncidentEvent(incident.getId(), type, message, actor, createdAt);
    }

    /** Grava UMA linha na timeline, na mesma transação da mudança. */
    private void emit(Incident incident, IncidentEventType type, String message, UUID actor) {
        incidentEventRepository.save(eventOf(incident, type, message, actor, nextEventStamp(null)));
    }

    /**
     * Próximo carimbo de UMA operação: estritamente crescente, com folha de
     * 1&nbsp;ms — a resolução do {@code TIMESTAMPTZ} é de microssegundos, então
     * {@code Instant.now()} duas vezes seguidas cairia no MESMO tick e a
     * ordenação {@code createdAt ASC, id ASC} cairia no desempate por UUID
     * aleatório (a ordem "title → description → severity" viraria sorte).
     * A folha de 1&nbsp;ms é folga de ordem de grandeza, não precisão: três
     * eventos de um PATCH terminam 2&nbsp;ms no futuro, invisível na timeline
     * e irrelevante para qualquer asserção.
     *
     * <p>{@code previous == null} é o primeiro evento da operação (usa o
     * relógio real); de lá em diante só avança, nunca regride.</p>
     */
    private static Instant nextEventStamp(Instant previous) {
        Instant now = Instant.now();
        if (previous == null) {
            return now;
        }
        Instant floor = previous.plus(EVENT_STAMP_GAP);
        return now.isAfter(floor) ? now : floor;
    }

    // --- apoio --------------------------------------------------------------

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }

    private static ApiException alertAlreadyLinked() {
        return new ApiException(
                "ALERT_ALREADY_LINKED", ALERT_ALREADY_LINKED_MESSAGE, HttpStatus.CONFLICT);
    }

    private static ApiException statusTransition(IncidentStatus from, IncidentStatus to) {
        return new ApiException(
                "INCIDENT_STATUS_TRANSITION",
                "Cannot transition from " + from + " to " + to,
                HttpStatus.CONFLICT);
    }

    private static ApiException conflict() {
        return new ApiException("CONFLICT", CONFLICT_MESSAGE, HttpStatus.CONFLICT);
    }
}
