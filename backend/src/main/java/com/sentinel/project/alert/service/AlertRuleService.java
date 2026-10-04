package com.sentinel.project.alert.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.sentinel.alerting.AlertLifecycleService;
import com.sentinel.error.ApiException;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.model.Role;
import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertResolutionReason;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.alert.repository.AlertRuleRepository;
import com.sentinel.project.alert.validation.AlertRuleValidationMatrix;
import com.sentinel.project.alert.web.dto.AlertRuleActivationRequest;
import com.sentinel.project.alert.web.dto.AlertRuleCreateRequest;
import com.sentinel.project.alert.web.dto.AlertRuleResponse;
import com.sentinel.project.alert.web.dto.AlertRuleUpdateRequest;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.Service;
import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.repository.MonitorRepository;
import com.sentinel.project.repository.ProjectRepository;
import com.sentinel.project.repository.ServiceRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de alerta (Prompt 12) — quarto nível da hierarquia
 * Organization → Project → Service → AlertRule, com escopo opcional de um
 * Monitor do MESMO serviço.
 *
 * <p><b>Resolução do conflito {@code AlertRule} × {@code @Service}:</b> o
 * MESMO arranjo do {@code MonitorService} — a entidade
 * {@link com.sentinel.project.model.Service} (pai da regra, presente em toda
 * assinatura de escopo) é importada normalmente e o estereótipo do Spring
 * aparece UMA vez, com nome completo.</p>
 *
 * <p><b>Ordem das checagens (contrato público, ver docs/alert-rules.md):</b></p>
 * <ol>
 *   <li>role — mutação exige {@code ADMIN}/{@code OPERATOR} ({@code 403});</li>
 *   <li>projeto no tenant ({@code 404}) → serviço no projeto ({@code 404}),
 *       nessa ordem, para valer a cadeia Organization → Project → Service →
 *       AlertRule sem consulta global;</li>
 *   <li>pais ATIVOS apenas em mutação ({@code 409 PROJECT_INACTIVE} antes de
 *       {@code 409 SERVICE_INACTIVE}) — <b>desativar é fail-safe</b>: a rota
 *       de ativação não exige pais ativos quando o alvo é {@code active=false},
 *       e a leitura (list/get) é sempre permitida, mesmo com pais inativos;</li>
 *   <li>regra ({@code 404} genérico — inexistente, de outro serviço ou de
 *       outra organização responde igual, anti-enumeração);</li>
 *   <li>validação de domínio ({@code 400 VALIDATION_ERROR}) — matriz
 *       type × operator, faixa do threshold e exclusividade numérico × status
 *       via {@link AlertRuleValidationMatrix}; no update os campos AUSENTES
 *       são resolvidos para os valores EFETIVOS antes de validar (create e
 *       update passam pela mesma regra, sem bypass);</li>
 *   <li>unicidade ({@code 409 ALERT_RULE_NAME_DUPLICATE});</li>
 *   <li>ownership de monitor quando {@code monitorId} está no payload
 *       ({@code 404} — deve pertencer ao MESMO serviço).</li>
 * </ol>
 * <p><b>Salvo no create:</b> o bean validation do {@code @Valid} do POST roda
 * no CONTROLLER, antes de qualquer coisa desta classe — um name em branco no
 * create responde 400 antes do service. A ordem acima é a do service.</p>
 *
 * <p><b>Sem SSRF e sem payload de URL:</b> alert rule não aponta para
 * endereço externo — o único "alvo" é um monitor opcional já cadastrado, e
 * seu ownership é provado com {@code MonitorRepository.findByServiceIdAndId}
 * (o monitor precisa EXISTIR no serviço; não precisa estar ativo — decidir
 * elegibilidade de monitor inativo é papel do futuro Alert Engine).</p>
 *
 * <p><b>Idempotência:</b> ativação/desativação é no-op quando a regra já está
 * no estado pedido — devolve o estado atual SEM escrita (o {@code updatedAt}
 * não avança).</p>
 *
 * <p><b>Unicidade por serviço:</b> pré-check ({@code 409} imediato) + corrida
 * absorvida pelo {@code UNIQUE (service_id, name_normalized)} da migration V9
 * → a MESMA {@code DataIntegrityViolationException} vira o MESMO {@code 409},
 * nunca SQL vazando. Nomes comparados normalizados (trim + lowercase
 * {@link java.util.Locale#ROOT}). Desativar não libera o nome.</p>
 */
// Arranjo do conflito de nomes: entidade Service importada acima, estereótipo
// do Spring com FQN aqui (única ocorrência deste módulo).
@org.springframework.stereotype.Service
public class AlertRuleService {

    /** Mensagens do contrato público da API. */
    public static final String DUPLICATE_NAME_MESSAGE =
            "Alert rule with this name already exists in this service";

    public static final String PROJECT_INACTIVE_MESSAGE =
            "Cannot create or modify alert rules in an inactive project";

    public static final String SERVICE_INACTIVE_MESSAGE =
            "Cannot create or modify alert rules in an inactive service";

    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_PAGE_SIZE = 100;

    /** Campos ordenáveis: allowlist explícita (nunca propriedade do cliente sem checagem). */
    private static final List<String> SORT_FIELDS = List.of("createdAt", "name", "id");

    private final AlertRuleRepository alertRuleRepository;
    private final MonitorRepository monitorRepository;
    private final ServiceRepository serviceRepository;
    private final ProjectRepository projectRepository;
    private final AlertLifecycleService alertLifecycleService;

    public AlertRuleService(
            AlertRuleRepository alertRuleRepository,
            MonitorRepository monitorRepository,
            ServiceRepository serviceRepository,
            ProjectRepository projectRepository,
            AlertLifecycleService alertLifecycleService) {
        this.alertRuleRepository = alertRuleRepository;
        this.monitorRepository = monitorRepository;
        this.serviceRepository = serviceRepository;
        this.projectRepository = projectRepository;
        this.alertLifecycleService = alertLifecycleService;
    }

    // --- POST .../alert-rules ------------------------------------------------

    /**
     * Cria a regra já vinculada ao serviço do path (o DTO nem tem campos de
     * escopo) e nasce {@code active=true} — o servidor define, payload não
     * opina. Conição validada pela matriz ANTES de qualquer escrita; escopo de
     * monitor (quando presente) é provado por último, na MESMA cadeia do
     * detalhe (Service → Monitor). A flag {@code autoCreateIncident} (Prompt
     * 15) É do payload (é configuração editável, ao contrário de
     * {@code active}): ausente = {@code false} = mesmo comportamento do
     * Prompt 12.
     */
    @Transactional
    public AlertRuleResponse create(UUID projectId, UUID serviceId, AlertRuleCreateRequest request) {
        requireMutationRole();
        Service service = requireActiveParents(projectId, serviceId);

        AlertRuleValidationMatrix.validate(
                request.type(),
                request.operator(),
                request.thresholdNumeric(),
                request.thresholdStatus());
        String name = validateName(request.name());

        String normalized = AlertRule.normalizeName(name);
        if (alertRuleRepository.existsByServiceIdAndNameNormalized(service.getId(), normalized)) {
            throw duplicateName();
        }
        Monitor monitor = findMonitorIfPresent(service, request.monitorId());

        AlertRule rule = new AlertRule(
                service,
                name,
                monitor,
                request.type(),
                request.operator(),
                request.thresholdNumeric(),
                request.thresholdStatus(),
                request.window(),
                request.severity());
        // ausente (null) → false = default V12 = comportamento do Prompt 12
        rule.setAutoCreateIncident(Boolean.TRUE.equals(request.autoCreateIncident()));
        try {
            rule = alertRuleRepository.saveAndFlush(rule);
        }
        catch (DataIntegrityViolationException ex) {
            // corrida entre o existsBy acima e o insert: o unique vence → 409
            throw duplicateName();
        }
        return AlertRuleResponse.from(rule, TenantContext.role());
    }

    // --- GET .../alert-rules -------------------------------------------------

    /**
     * Página de regras do serviço com os cinco filtros opcionais (active,
     * type, severity, monitorId, nome) e ordenação determinística. Qualquer
     * papel pode ler — inclusive quando projeto/serviço estão inativos (a
     * configuração segue consultável; regra inativa também, filtrável por
     * {@code active=false}).
     *
     * @param monitorId presente → restringe àquele escopo; ausente → todos os
     *                   escopos (Service-level e Monitor-level)
     */
    @Transactional(readOnly = true)
    public Page<AlertRuleResponse> list(
            UUID projectId,
            UUID serviceId,
            int page,
            int size,
            String sort,
            Boolean active,
            AlertRuleType type,
            AlertSeverity severity,
            UUID monitorId,
            String name) {
        Service service = requireParents(projectId, serviceId);
        Pageable pageable = PageRequest.of(validatePage(page), validateSize(size), parseSort(sort));
        String pattern = searchPattern(name);

        Page<AlertRule> rules =
                alertRuleRepository.search(service.getId(), active, type, severity, monitorId, pattern, pageable);
        Role role = TenantContext.role();
        return rules.map(rule -> AlertRuleResponse.from(rule, role));
    }

    /** GET /{ruleId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @Transactional(readOnly = true)
    public AlertRuleResponse get(UUID projectId, UUID serviceId, UUID ruleId) {
        Service service = requireParents(projectId, serviceId);
        return AlertRuleResponse.from(findRule(service, ruleId), TenantContext.role());
    }

    // --- PATCH .../alert-rules/{ruleId} --------------------------------------

    /**
     * Atualização parcial de name/type/operator/thresholds/window/severity/
     * monitorId/autoCreateIncident. Campo ausente ({@code null}) não altera.
     * A condição
     * EFETIVA (valores novos + persistidos) é revalidada pela matriz por
     * completo — mudar um campo invalida a combinação inteira de propósito.
     *
     * <h3>Resolução dos thresholds (par numérico × status)</h3>
     * <p>Ao mudar de tipo o par inteiro é reavaliado:</p>
     * <ul>
     *   <li>{@code thresholdNumeric} presente → usado (e validado: trazê-lo
     *       para {@code SERVICE_STATUS} é o erro do critério #74); ausente →
     *       herdado do persistido, EXCETO quando o tipo efetivo passou a ser
     *       {@code SERVICE_STATUS} (aí vira {@code null}, senão a regra
     *       antiga numérica contaminaria a nova regra de status);</li>
     *   <li>{@code thresholdStatus} presente → usado; ausente → herdado do
     *       persistido, EXCETO quando o tipo efetivo NÃO é
     *       {@code SERVICE_STATUS} (aí vira {@code null}, simétrico);</li>
     * </ul>
     * <p>Resultado: {@code {type: SERVICE_STATUS, thresholdStatus: UNAVAILABLE}}
     * converte uma regra numérica limpando o número automaticamente, e
     * {@code {type: AVERAGE_LATENCY, thresholdNumeric: 800}} converte uma
     * regra de status limpando o status — sem o cliente precisar mandar
     * {@code null}s redundantes.</p>
     *
     * <p>{@code active}, {@code id} e {@code serviceId} não existem no DTO:
     * servidor-owned, e a ativação tem rota própria. {@code autoCreateIncident}
     * (Prompt 15) EXISTE e é parcial como os outros: presente liga/desliga a
     * automação dos próximos disparos; ausente (ou {@code null} explícito)
     * não altera. Desligar nunca mexe em alerta/incidente já existente — o
     * efeito é somente prospectivo.</p>
     */
    @Transactional
    public AlertRuleResponse update(
            UUID projectId, UUID serviceId, UUID ruleId, AlertRuleUpdateRequest request) {
        requireMutationRole();
        Service service = requireActiveParents(projectId, serviceId);
        AlertRule rule = findRule(service, ruleId);

        // valores efetivos (campo ausente = persistido) validados por inteiro
        AlertRuleType type = request.getType() != null ? request.getType() : rule.getType();
        AlertOperator operator =
                request.getOperator() != null ? request.getOperator() : rule.getOperator();
        BigDecimal thresholdNumeric = effectiveThresholdNumeric(request, rule, type);
        ServiceStatus thresholdStatus = effectiveThresholdStatus(request, rule, type);
        AlertRuleValidationMatrix.validate(type, operator, thresholdNumeric, thresholdStatus);

        String name = null;
        boolean rename = false;
        if (request.getName() != null) {
            name = validateName(request.getName());
            rename = !name.equals(rule.getName());
        }
        EvaluationWindow window = request.getWindow() != null ? request.getWindow() : rule.getWindow();
        AlertSeverity severity =
                request.getSeverity() != null ? request.getSeverity() : rule.getSeverity();

        // unicidade DEPOIS do payload: renomear não pode colidir consigo mesmo
        if (rename) {
            String normalized = AlertRule.normalizeName(name);
            if (!normalized.equals(rule.getNameNormalized())
                    && alertRuleRepository.existsByServiceIdAndNameNormalizedAndIdNot(
                            service.getId(), normalized, ruleId)) {
                throw duplicateName();
            }
            rule.rename(name);
        }
        rule.setType(type);
        rule.setOperator(operator);
        rule.setThresholdNumeric(thresholdNumeric);
        rule.setThresholdStatus(thresholdStatus);
        rule.setWindow(window);
        rule.setSeverity(severity);

        // monitorId: presente (valor) revalida ownership; null explícito limpa
        if (request.isMonitorIdSet()) {
            rule.setMonitor(findMonitorIfPresent(service, request.getMonitorId()));
        }

        // opt-in de automação (Prompt 15): presente liga/desliga; ausente
        // não altera (Boolean wrapper — ver AlertRuleUpdateRequest)
        if (request.getAutoCreateIncident() != null) {
            rule.setAutoCreateIncident(request.getAutoCreateIncident());
        }

        try {
            rule = alertRuleRepository.saveAndFlush(rule);
        }
        catch (DataIntegrityViolationException ex) {
            // corrida entre o existsBy acima e o update: o unique vence → 409
            throw duplicateName();
        }
        return AlertRuleResponse.from(rule, TenantContext.role());
    }

    // --- PATCH .../alert-rules/{ruleId}/activation ---------------------------

    /**
     * Ativação/desativação <b>idempotente e fail-safe</b>: já no estado
     * pedido → devolve o estado atual SEM escrita; {@code false → true} exige
     * pais ativos; {@code true → false} é sempre permitida — inclusive com
     * pais inativos, porque desligar é a direção segura. Regra inativa segue
     * persistida e consultável (listagem com {@code active=false}).
     */
    @Transactional
    public AlertRuleResponse activation(
            UUID projectId, UUID serviceId, UUID ruleId, AlertRuleActivationRequest request) {
        requireMutationRole();
        Project project = findProjectInTenant(projectId);
        Service service = findService(project, serviceId);

        boolean target = request.active();
        if (target) {
            requireActiveParents(project, service);
        }

        AlertRule rule = findRule(service, ruleId);
        if (rule.isActive() == target) {
            return AlertRuleResponse.from(rule, TenantContext.role()); // no-op, sem escrita
        }
        rule.setActive(target);
        AlertRule saved = alertRuleRepository.saveAndFlush(rule);
        if (!target) {
            // Regra desligada com alerta ainda aberto: fecha com o motivo
            // dedicado — a mensagem do FIRING fica intacta (Prompt 13).
            alertLifecycleService.resolveOpenForRule(
                    ruleId, AlertResolutionReason.RULE_DEACTIVATED);
        }
        return AlertRuleResponse.from(saved, TenantContext.role());
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

    // --- cadeia Organization → Project → Service → AlertRule -----------------

    /** Projeto dentro do tenant corrente — id alheio/inexistente → 404 genérico. */
    private Project findProjectInTenant(UUID projectId) {
        return projectRepository
                .findByOrganizationIdAndId(TenantContext.organizationId(), projectId)
                .orElseThrow(AlertRuleService::notFound);
    }

    /** Projeto + serviço, na ORDEM: 404 genérico em qualquer nível ausente. */
    private Service findService(Project project, UUID serviceId) {
        return serviceRepository
                .findByProjectIdAndId(project.getId(), serviceId)
                .orElseThrow(AlertRuleService::notFound);
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
        requireActiveParents(project, service);
        return service;
    }

    /** Checagem dos pais compartilhada pela mutação e pela ativação. */
    private static void requireActiveParents(Project project, Service service) {
        if (!project.isActive()) {
            throw new ApiException("PROJECT_INACTIVE", PROJECT_INACTIVE_MESSAGE, HttpStatus.CONFLICT);
        }
        if (!service.isActive()) {
            throw new ApiException("SERVICE_INACTIVE", SERVICE_INACTIVE_MESSAGE, HttpStatus.CONFLICT);
        }
    }

    /** Regra dentro do serviço já resolvido no tenant → 404 genérico. */
    private AlertRule findRule(Service service, UUID ruleId) {
        return alertRuleRepository
                .findByServiceIdAndId(service.getId(), ruleId)
                .orElseThrow(AlertRuleService::notFound);
    }

    /**
     * Escopo de monitor do payload: ausente/{@code null} → Service-level
     * ({@code null}); com valor → o monitor precisa pertencer ao MESMO
     * serviço ({@code 404} genérico — inexistente, de outro serviço ou de
     * outro tenant responde igual). Não exige monitor ativo: a elegibilidade
     * de avaliação é decisão do futuro Alert Engine.
     */
    private Monitor findMonitorIfPresent(Service service, UUID monitorId) {
        if (monitorId == null) {
            return null;
        }
        return monitorRepository
                .findByServiceIdAndId(service.getId(), monitorId)
                .orElseThrow(AlertRuleService::notFound);
    }

    // --- thresholds efetivos (update) ---------------------------------------

    /** Numérico: presente → payload; ausente → herdado, exceto se virou SERVICE_STATUS. */
    private static BigDecimal effectiveThresholdNumeric(
            AlertRuleUpdateRequest request, AlertRule rule, AlertRuleType type) {
        if (request.getThresholdNumeric() != null) {
            return request.getThresholdNumeric();
        }
        return type == AlertRuleType.SERVICE_STATUS ? null : rule.getThresholdNumeric();
    }

    /** Status: presente → payload; ausente → herdado, exceto se saiu de SERVICE_STATUS. */
    private static ServiceStatus effectiveThresholdStatus(
            AlertRuleUpdateRequest request, AlertRule rule, AlertRuleType type) {
        if (request.getThresholdStatus() != null) {
            return request.getThresholdStatus();
        }
        return type == AlertRuleType.SERVICE_STATUS ? rule.getThresholdStatus() : null;
    }

    // --- validação ----------------------------------------------------------

    /** Nome: trim + checagens de domínio (mesma regra do MonitorService). */
    private static String validateName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new ApiException("VALIDATION_ERROR", "name: must not be blank", HttpStatus.BAD_REQUEST);
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "name: must be at most " + MAX_NAME_LENGTH + " characters",
                    HttpStatus.BAD_REQUEST);
        }
        return name;
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

    /**
     * Ordenação do cliente com allowlist (createdAt | name | id) e desempate
     * fixo em {@code id asc} — a ordem é sempre determinística. Default:
     * {@code createdAt,asc}. (Mesmo arranjo do {@code MonitorService}, sem
     * tocá-lo.)
     */
    private static Sort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultSort();
        }
        String[] parts = raw.split(",");
        String field = parts[0].trim();
        if (!SORT_FIELDS.contains(field)) {
            throw sortNotAllowed();
        }
        Sort.Direction direction;
        if (parts.length == 1 || parts[1].isBlank()) {
            direction = Sort.Direction.ASC;
        }
        else if (parts[1].trim().equalsIgnoreCase("asc")) {
            direction = Sort.Direction.ASC;
        }
        else if (parts[1].trim().equalsIgnoreCase("desc")) {
            direction = Sort.Direction.DESC;
        }
        else {
            throw sortNotAllowed();
        }
        Sort sort = Sort.by(direction, field);
        return field.equals("id") ? sort : sort.and(Sort.by(Sort.Direction.ASC, "id"));
    }

    private static Sort defaultSort() {
        return Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));
    }

    private static ApiException sortNotAllowed() {
        return new ApiException(
                "VALIDATION_ERROR",
                "sort: must be one of createdAt, name, id",
                HttpStatus.BAD_REQUEST);
    }

    /**
     * Busca por nome: normalizada como a coluna (case-insensitive) e já com os
     * curingas {@code %...%} — a JPQL recebe o pattern pronto porque
     * {@code CONCAT} dentro de {@code LIKE} faz o Hibernate 7 bindear o
     * parâmetro como {@code bytea} e o PostgreSQL responde
     * {@code varchar ~~ bytea} (500). Vazia = sem filtro ({@code null}).
     */
    private static String searchPattern(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return "%" + AlertRule.normalizeName(raw) + "%";
    }

    // --- apoio --------------------------------------------------------------

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }

    private static ApiException duplicateName() {
        return new ApiException(
                "ALERT_RULE_NAME_DUPLICATE", DUPLICATE_NAME_MESSAGE, HttpStatus.CONFLICT);
    }
}
