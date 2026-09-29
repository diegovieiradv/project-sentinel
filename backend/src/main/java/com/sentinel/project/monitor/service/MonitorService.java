package com.sentinel.project.monitor.service;

import java.util.List;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.model.Role;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.Service;
import com.sentinel.project.monitor.dto.MonitorActivationRequest;
import com.sentinel.project.monitor.dto.MonitorCreateRequest;
import com.sentinel.project.monitor.dto.MonitorResponse;
import com.sentinel.project.monitor.dto.MonitorUpdateRequest;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorMethod;
import com.sentinel.project.monitor.repository.MonitorRepository;
import com.sentinel.project.repository.ProjectRepository;
import com.sentinel.project.repository.ServiceRepository;
import com.sentinel.project.security.SsrfGuard;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de monitor (Prompt 08) — quarto nível da hierarquia
 * Organization → Project → Service → Monitor.
 *
 * <p><b>Resolução do conflito {@code Service} × {@code @Service}:</b> o MESMO
 * arranjo do {@code ServiceService} — a entidade
 * {@link com.sentinel.project.model.Service} (pai do monitor, presente em
 * toda assinatura de escopo) é importada normalmente e o estereótipo do
 * Spring aparece UMA vez, com nome completo. Nada além desta classe
 * precisa de FQN.</p>
 *
 * <p><b>Ordem das checagens (contrato público, ver docs/monitors.md):</b></p>
 * <ol>
 *   <li>role — mutação exige {@code ADMIN}/{@code OPERATOR} ({@code 403});</li>
 *   <li>projeto no tenant ({@code 404}) → serviço no projeto ({@code 404}),
 *       nessa ordem, para valer a cadeia Organization → Project → Service →
 *       Monitor sem consulta global;</li>
 *   <li>pais ATIVOS apenas em mutação ({@code 409 PROJECT_INACTIVE} antes de
 *       {@code 409 SERVICE_INACTIVE}) — <b>desativar é fail-safe</b>: a
 *       rota de ativação não exige pais ativos quando o alvo é
 *       {@code active=false}, e a leitura (list/get) é sempre permitida,
 *       mesmo com pais inativos;</li>
 *   <li>monitor ({@code 404} genérico — inexistente, de outro serviço ou de
 *       outra organização responde igual, anti-enumeração);</li>
 *   <li>SSRF ({@code 400 MONITOR_TARGET_NOT_ALLOWED}) — quando há URL a
 *       validar: no create sempre, no update quando o campo está presente e
 *       na reativação {@code false → true} (revalida o alvo já persistido);</li>
 *   <li>payload ({@code 400 VALIDATION_ERROR});</li>
 *   <li>unicidade ({@code 409 MONITOR_NAME_DUPLICATE}).</li>
 * </ol>
 * <p><b>Salvo no create:</b> o bean validation do {@code @Valid} do POST roda
 * no CONTROLLER, antes de qualquer coisa desta classe — um name em branco no
 * create responde 400 antes do guard. A ordem acima é a do service; o guard
 * continua antes da validação de domínio de todos os outros caminhos.</p>
 *
 * <p><b>SSRF no cadastro:</b> o {@link SsrfGuard} roda em transação de
 * escrita e valida o alvo ANTES de persistir (create/update) ou de reativar
 * (ativação). Não há requisição a nenhum alvo aqui — exceto o próprio guard,
 * que resolve DNS apenas quando o host é um nome (para classificar os
 * endereços). Checagens em execução, redirects e revalidação por conexão
 * pertencem ao Prompt 09.</p>
 *
 * <p><b>Idempotência:</b> ativação/desativação é no-op quando o monitor já
 * está no estado pedido — devolve o estado atual SEM escrita (o
 * {@code updatedAt} não avança) e ANTES de qualquer revalidação SSRF.</p>
 *
 * <p><b>Unicidade por serviço:</b> pré-check ({@code 409} imediato) + corrida
 * absorvida pelo {@code UNIQUE (service_id, name_normalized)} da migration
 * V7 → a MESMA {@code DataIntegrityViolationException} vira o MESMO
 * {@code 409}, nunca SQL vazando. Nomes são comparados normalizados
 * (trim + lowercase {@link java.util.Locale#ROOT}).</p>
 */
// Arranjo do conflito de nomes: entidade Service importada acima, estereótipo
// do Spring com FQN aqui (única ocorrência deste módulo).
@org.springframework.stereotype.Service
public class MonitorService {

    /** Mensagens do contrato público da API. */
    public static final String DUPLICATE_NAME_MESSAGE =
            "Monitor with this name already exists in this service";

    public static final String PROJECT_INACTIVE_MESSAGE =
            "Cannot create or modify monitors in an inactive project";

    public static final String SERVICE_INACTIVE_MESSAGE =
            "Cannot create or modify monitors in an inactive service";

    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_PAGE_SIZE = 100;

    private static final int MIN_INTERVAL_SECONDS = 30;
    private static final int MAX_INTERVAL_SECONDS = 86400;
    private static final int MIN_TIMEOUT_MILLIS = 100;
    private static final int MAX_TIMEOUT_MILLIS = 30000;

    /** Defaults do servidor (payload pode omitir os três). */
    private static final int DEFAULT_INTERVAL_SECONDS = 60;
    private static final int DEFAULT_TIMEOUT_MILLIS = 5000;

    /** Campos ordenáveis: allowlist explícita (nunca propriedade do cliente sem checagem). */
    private static final List<String> SORT_FIELDS = List.of("createdAt", "name", "id");

    private final MonitorRepository monitorRepository;
    private final ServiceRepository serviceRepository;
    private final ProjectRepository projectRepository;
    private final SsrfGuard ssrfGuard;

    public MonitorService(
            MonitorRepository monitorRepository,
            ServiceRepository serviceRepository,
            ProjectRepository projectRepository,
            SsrfGuard ssrfGuard) {
        this.monitorRepository = monitorRepository;
        this.serviceRepository = serviceRepository;
        this.projectRepository = projectRepository;
        this.ssrfGuard = ssrfGuard;
    }

    // --- POST .../monitors --------------------------------------------------

    /**
     * Cria o monitor já vinculado ao serviço do path (o DTO nem tem campos de
     * escopo) e nasce {@code active=true} + {@code type=HTTP} — o servidor
     * define os dois, payload não opina. URL validada pelo guard ANTES da
     * validação de domínio e antes de qualquer escrita.
     */
    @Transactional
    public MonitorResponse create(UUID projectId, UUID serviceId, MonitorCreateRequest request) {
        requireMutationRole();
        Service service = requireActiveParents(projectId, serviceId);

        ssrfGuard.validate(request.url());

        String name = validateName(request.name());
        MonitorMethod method = parseMethodOnCreate(request.method());
        int intervalSeconds = validateInterval(request.intervalSeconds(), DEFAULT_INTERVAL_SECONDS);
        int timeoutMillis = validateTimeout(request.timeoutMillis(), DEFAULT_TIMEOUT_MILLIS);
        validateCoherence(intervalSeconds, timeoutMillis);

        String normalized = Monitor.normalizeName(name);
        if (monitorRepository.existsByServiceIdAndNameNormalized(service.getId(), normalized)) {
            throw duplicateName();
        }

        Monitor monitor;
        try {
            monitor = monitorRepository.saveAndFlush(
                    new Monitor(service, name, request.url(), method, intervalSeconds, timeoutMillis));
        }
        catch (DataIntegrityViolationException ex) {
            // corrida entre o existsBy acima e o insert: o unique vence → 409
            throw duplicateName();
        }
        return MonitorResponse.from(monitor, TenantContext.role());
    }

    // --- GET .../monitors ---------------------------------------------------

    /**
     * Página de monitores do serviço com filtros opcionais (active, method e
     * nome) e ordenação determinística. Qualquer papel pode ler — inclusive
     * quando projeto/serviço estão inativos (o catálogo segue consultável).
     */
    @Transactional(readOnly = true)
    public Page<MonitorResponse> list(
            UUID projectId,
            UUID serviceId,
            int page,
            int size,
            String sort,
            Boolean active,
            MonitorMethod method,
            String name) {
        Service service = requireParents(projectId, serviceId);
        Pageable pageable = PageRequest.of(validatePage(page), validateSize(size), parseSort(sort));
        String search = normalizeSearch(name);

        Page<Monitor> monitors = query(service.getId(), active, method, search, pageable);
        Role role = TenantContext.role();
        return monitors.map(monitor -> MonitorResponse.from(monitor, role));
    }

    /** GET /{monitorId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @Transactional(readOnly = true)
    public MonitorResponse get(UUID projectId, UUID serviceId, UUID monitorId) {
        Service service = requireParents(projectId, serviceId);
        return MonitorResponse.from(findMonitor(service, monitorId), TenantContext.role());
    }

    // --- PATCH .../monitors/{monitorId} -------------------------------------

    /**
     * Atualização parcial de name/url/method/intervalSeconds/timeoutMillis.
     * Campo ausente ({@code null}) não altera. A URL presente é revalidada
     * pelo guard ANTES do payload; a coerência timeout × intervalo é sempre
     * recalculada sobre os valores EFETIVOS (novo ou persistido).
     */
    @Transactional
    public MonitorResponse update(
            UUID projectId, UUID serviceId, UUID monitorId, MonitorUpdateRequest request) {
        requireMutationRole();
        Service service = requireActiveParents(projectId, serviceId);
        Monitor monitor = findMonitor(service, monitorId);

        if (request.url() != null) {
            ssrfGuard.validate(request.url());
        }

        String name = null;
        boolean rename = false;
        if (request.name() != null) {
            name = validateName(request.name());
            rename = !name.equals(monitor.getName());
        }
        MonitorMethod method = parseMethodOnUpdate(request.method(), monitor.getMethod());
        int intervalSeconds = validateInterval(request.intervalSeconds(), monitor.getIntervalSeconds());
        int timeoutMillis = validateTimeout(request.timeoutMillis(), monitor.getTimeoutMillis());
        validateCoherence(intervalSeconds, timeoutMillis);

        // unicidade DEPOIS do payload: renomear não pode colidir consigo mesmo
        if (rename) {
            String normalized = Monitor.normalizeName(name);
            if (!normalized.equals(monitor.getNameNormalized())
                    && monitorRepository.existsByServiceIdAndNameNormalizedAndIdNot(
                            service.getId(), normalized, monitorId)) {
                throw duplicateName();
            }
            monitor.rename(name);
        }
        if (request.url() != null) {
            monitor.setUrl(request.url());
        }
        monitor.setMethod(method);
        monitor.setIntervalSeconds(intervalSeconds);
        monitor.setTimeoutMillis(timeoutMillis);

        try {
            monitor = monitorRepository.saveAndFlush(monitor);
        }
        catch (DataIntegrityViolationException ex) {
            // corrida entre o existsBy acima e o update: o unique vence → 409
            throw duplicateName();
        }
        return MonitorResponse.from(monitor, TenantContext.role());
    }

    // --- PATCH .../monitors/{monitorId}/activation --------------------------

    /**
     * Ativação/desativação <b>idempotente e fail-safe</b>: já no estado
     * pedido → devolve o estado atual SEM escrita; {@code false → true} exige
     * pais ativos e REVALIDA a URL pelo guard (o alvo pode ter sido
     * reclassificado como interno); {@code true → false} é sempre permitida —
     * inclusive com pais inativos, porque desligar é a direção segura.
     */
    @Transactional
    public MonitorResponse activation(
            UUID projectId, UUID serviceId, UUID monitorId, MonitorActivationRequest request) {
        requireMutationRole();
        Project project = findProjectInTenant(projectId);
        Service service = findService(project, serviceId);

        boolean target = request.active();
        if (target) {
            requireActiveParents(project, service);
        }

        Monitor monitor = findMonitor(service, monitorId);
        if (monitor.isActive() == target) {
            return MonitorResponse.from(monitor, TenantContext.role()); // no-op, sem escrita
        }
        if (target) {
            ssrfGuard.validate(monitor.getUrl());
        }

        monitor.setActive(target);
        return MonitorResponse.from(monitorRepository.saveAndFlush(monitor), TenantContext.role());
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

    // --- cadeia Organization → Project → Service → Monitor ------------------

    /** Projeto dentro do tenant corrente — id alheio/inexistente → 404 genérico. */
    private Project findProjectInTenant(UUID projectId) {
        return projectRepository
                .findByOrganizationIdAndId(TenantContext.organizationId(), projectId)
                .orElseThrow(MonitorService::notFound);
    }

    /** Projeto + serviço, na ORDEM: 404 genérico em qualquer nível ausente. */
    private Service findService(Project project, UUID serviceId) {
        return serviceRepository
                .findByProjectIdAndId(project.getId(), serviceId)
                .orElseThrow(MonitorService::notFound);
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

    /** Monitor dentro do serviço já resolvido no tenant → 404 genérico. */
    private Monitor findMonitor(Service service, UUID monitorId) {
        return monitorRepository
                .findByServiceIdAndId(service.getId(), monitorId)
                .orElseThrow(MonitorService::notFound);
    }

    // --- listagem -----------------------------------------------------------

    /**
     * Combina os filtros opcionais da listagem. As oito variantes existem como
     * derived queries (Spring Data não aceita predicado opcional em método
     * derivado) e todas recebem o mesmo {@link Pageable} já ordenado.
     */
    private Page<Monitor> query(
            UUID serviceId, Boolean active, MonitorMethod method, String search, Pageable pageable) {
        boolean filterByActive = active != null;
        boolean filterByMethod = method != null;
        boolean filterByName = search != null;
        if (filterByActive && filterByMethod && filterByName) {
            return monitorRepository.findByServiceIdAndActiveAndMethodAndNameNormalizedContaining(
                    serviceId, active, method, search, pageable);
        }
        if (filterByActive && filterByMethod) {
            return monitorRepository.findByServiceIdAndActiveAndMethod(
                    serviceId, active, method, pageable);
        }
        if (filterByActive && filterByName) {
            return monitorRepository.findByServiceIdAndActiveAndNameNormalizedContaining(
                    serviceId, active, search, pageable);
        }
        if (filterByMethod && filterByName) {
            return monitorRepository.findByServiceIdAndMethodAndNameNormalizedContaining(
                    serviceId, method, search, pageable);
        }
        if (filterByActive) {
            return monitorRepository.findByServiceIdAndActive(serviceId, active, pageable);
        }
        if (filterByMethod) {
            return monitorRepository.findByServiceIdAndMethod(serviceId, method, pageable);
        }
        if (filterByName) {
            return monitorRepository.findByServiceIdAndNameNormalizedContaining(
                    serviceId, search, pageable);
        }
        return monitorRepository.findByServiceId(serviceId, pageable);
    }

    // --- validação ----------------------------------------------------------

    /** Nome: trim + checagens de domínio (mesma regra do create, no update). */
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

    /**
     * Método no CREATE: ausente/em branco → default {@code GET}; presente
     * deve ser exatamente {@code GET} ou {@code HEAD} (o contrato publica
     * esses dois literais).
     */
    private static MonitorMethod parseMethodOnCreate(String raw) {
        if (raw == null || raw.isBlank()) {
            return MonitorMethod.GET;
        }
        return requireMethod(raw);
    }

    /**
     * Método no UPDATE: ausente → mantém o atual; presente (inclusive em
     * branco) deve ser válido — "em branco" não é um método e não limpa o
     * campo.
     */
    private static MonitorMethod parseMethodOnUpdate(String raw, MonitorMethod current) {
        if (raw == null) {
            return current;
        }
        return requireMethod(raw);
    }

    private static MonitorMethod requireMethod(String raw) {
        String value = raw.trim();
        if ("GET".equals(value)) {
            return MonitorMethod.GET;
        }
        if ("HEAD".equals(value)) {
            return MonitorMethod.HEAD;
        }
        throw new ApiException(
                "VALIDATION_ERROR", "method: must be GET or HEAD", HttpStatus.BAD_REQUEST);
    }

    /** Intervalo: ausente → {@code fallback}; fora de 30..86400 → 400. */
    private static int validateInterval(Integer raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw < MIN_INTERVAL_SECONDS || raw > MAX_INTERVAL_SECONDS) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "intervalSeconds: must be between "
                            + MIN_INTERVAL_SECONDS
                            + " and "
                            + MAX_INTERVAL_SECONDS,
                    HttpStatus.BAD_REQUEST);
        }
        return raw;
    }

    /** Timeout: ausente → {@code fallback}; fora de 100..30000 → 400. */
    private static int validateTimeout(Integer raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw < MIN_TIMEOUT_MILLIS || raw > MAX_TIMEOUT_MILLIS) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "timeoutMillis: must be between "
                            + MIN_TIMEOUT_MILLIS
                            + " and "
                            + MAX_TIMEOUT_MILLIS,
                    HttpStatus.BAD_REQUEST);
        }
        return raw;
    }

    /**
     * Coerência: o timeout de uma checagem nunca pode engolir o próprio
     * intervalo (timeout ≥ intervalo × 1000 = checagens sobrepostas).
     * Recalculada sobre os valores EFETIVOS, então um PATCH que só muda um
     * dos dois também passa por aqui. (Espelha o CHECK
     * {@code monitors_timeout_below_interval_check} da migration V7.)
     */
    private static void validateCoherence(int intervalSeconds, int timeoutMillis) {
        if (timeoutMillis >= intervalSeconds * 1000L) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "timeoutMillis: must be less than intervalSeconds * 1000",
                    HttpStatus.BAD_REQUEST);
        }
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
     * {@code createdAt,asc}. (Espelha o {@code ServiceService} sem tocá-lo.)
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

    /** Busca por nome: normalizada como a coluna (case-insensitive); vazia = sem filtro. */
    private static String normalizeSearch(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Monitor.normalizeName(raw);
    }

    // --- apoio --------------------------------------------------------------

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }

    private static ApiException duplicateName() {
        return new ApiException("MONITOR_NAME_DUPLICATE", DUPLICATE_NAME_MESSAGE, HttpStatus.CONFLICT);
    }
}
