package com.sentinel.project.service;

import java.util.List;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.model.Role;
import com.sentinel.project.dto.ServiceActivationRequest;
import com.sentinel.project.dto.ServiceCreateRequest;
import com.sentinel.project.dto.ServiceResponse;
import com.sentinel.project.dto.ServiceUpdateRequest;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.Service;
import com.sentinel.project.model.ServiceStatus;
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
 * Regras de serviço do Prompt 07 — sub-recurso de projeto
 * (Organization → Project → Service).
 *
 * <p><b>Resolução do conflito de nome {@code Service} × {@code @Service}:</b>
 * esta classe precisa da entidade {@link com.sentinel.project.model.Service}
 * (várias assinaturas) e do estereótipo do Spring. Como Java não tem alias de
 * import, a entidade é importada normalmente e o estereótipo aparece UMA vez,
 * com nome completo — {@code @org.springframework.stereotype.Service} na
 * declaração da classe. Assim nenhum outro arquivo do módulo precisa de FQN
 * ({@code ServiceRepository}, {@code ServiceResponse} e
 * {@code ServiceController} não usam o estereótipo).</p>
 *
 * <p><b>Autorização no service, papel do servidor:</b> leitura para qualquer
 * papel (o {@code TenantInterceptor} já provou a membership antes do handler);
 * mutação exige {@code ADMIN} ou {@code OPERATOR} via
 * {@link #requireMutationRole()}. O papel vem do {@link TenantContext},
 * populado do banco na requisição corrente — nunca de claim de JWT.</p>
 *
 * <p><b>Projeto inativo:</b> LEITURA (list/get) de serviços de um projeto
 * inativo é permitida ({@code 200}) — o catálogo continua consultável;
 * CREATE/UPDATE/ACTIVATION respondem {@code 409 PROJECT_INACTIVE}. Ordem das
 * checagens: role ({@code 403}) → projeto resolvido no tenant ({@code 404}) →
 * projeto ativo ({@code 409}) → payload ({@code 400}) → unicidade ({@code 409}).</p>
 *
 * <p><b>Unicidade por projeto (nome normalizado):</b> duas camadas — o
 * pré-check devolve o {@code 409} imediato; a corrida entre o check e o INSERT
 * é absorvida pelo {@code UNIQUE (project_id, name_normalized)} da migration
 * V6, traduzido em {@code DataIntegrityViolationException} → o MESMO
 * {@code 409}, nunca mensagem crua de SQL.</p>
 *
 * <p><b>Isolamento:</b> o projeto é resolvido por
 * {@code findByOrganizationIdAndId(tenant, projectId)} e o serviço por
 * {@code findByProjectIdAndId(projectId, serviceId)} — nunca um
 * {@code findById} avulso. Projeto/serviço de outra organização responde o
 * {@code 404} genérico, sem revelar que existe em algum outro lugar.</p>
 */
// Arranjo do conflito de nomes: entidade importada acima (@link Service),
// estereótipo do Spring com FQN aqui (única ocorrência no módulo).
@org.springframework.stereotype.Service
public class ServiceService {

    /** Mensagens do contrato público da API. */
    public static final String DUPLICATE_NAME_MESSAGE =
            "Service with this name already exists in this project";

    public static final String PROJECT_INACTIVE_MESSAGE =
            "Cannot create or modify services in an inactive project";

    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_PAGE_SIZE = 100;

    /** Campos ordenáveis: allowlist explícita (nunca propriedade vinda do cliente sem checagem). */
    private static final List<String> SORT_FIELDS = List.of("createdAt", "name", "id");

    private final ServiceRepository serviceRepository;
    private final ProjectRepository projectRepository;

    public ServiceService(ServiceRepository serviceRepository, ProjectRepository projectRepository) {
        this.serviceRepository = serviceRepository;
        this.projectRepository = projectRepository;
    }

    // --- POST .../services --------------------------------------------------

    /**
     * Cria o serviço já vinculado ao projeto do path (o DTO nem tem campos de
     * escopo) e nasce {@code active=true} + {@code status=UNKNOWN} — o servidor
     * define os dois, payload não opina.
     */
    @Transactional
    public ServiceResponse create(UUID projectId, ServiceCreateRequest request) {
        requireMutationRole();
        Project project = requireActiveProject(projectId);
        String name = validateName(request.name());
        String description = validateDescription(request.description());

        String normalized = Service.normalizeName(name);
        if (serviceRepository.existsByProjectIdAndNameNormalized(project.getId(), normalized)) {
            throw duplicateName();
        }

        Service service;
        try {
            service = serviceRepository.saveAndFlush(new Service(project, name, description));
        }
        catch (DataIntegrityViolationException ex) {
            // corrida entre o existsBy acima e o insert: o unique vence → 409
            throw duplicateName();
        }
        return ServiceResponse.from(service, TenantContext.role());
    }

    // --- GET .../services ---------------------------------------------------

    /**
     * Página de serviços do projeto com filtros opcionais (active, status e
     * nome) e ordenação determinística. Qualquer papel pode ler — inclusive
     * quando o projeto está inativo (o catálogo segue consultável).
     */
    @Transactional(readOnly = true)
    public Page<ServiceResponse> list(
            UUID projectId,
            int page,
            int size,
            String sort,
            Boolean active,
            ServiceStatus status,
            String name) {
        Project project = findProjectInTenant(projectId);
        Pageable pageable = PageRequest.of(validatePage(page), validateSize(size), parseSort(sort));
        String search = normalizeSearch(name);

        Page<Service> services = query(project.getId(), active, status, search, pageable);
        Role role = TenantContext.role();
        return services.map(service -> ServiceResponse.from(service, role));
    }

    /** GET .../services/{serviceId} → 200 ou 404 genérico (escopo do tenant). */
    @Transactional(readOnly = true)
    public ServiceResponse get(UUID projectId, UUID serviceId) {
        Project project = findProjectInTenant(projectId);
        return ServiceResponse.from(findService(project, serviceId), TenantContext.role());
    }

    // --- PATCH .../services/{serviceId} -------------------------------------

    /**
     * Atualização parcial de nome/descrição. Campo ausente não altera.
     * Renomear revalida a unicidade contra TODOS os serviços do projeto,
     * exceto o próprio (serviço não pode colidir consigo mesmo).
     */
    @Transactional
    public ServiceResponse update(UUID projectId, UUID serviceId, ServiceUpdateRequest request) {
        requireMutationRole();
        Project project = requireActiveProject(projectId);
        Service service = findService(project, serviceId);
        UUID scope = project.getId();

        if (request.name() != null) {
            String name = validateName(request.name());
            if (!name.equals(service.getName())) {
                String normalized = Service.normalizeName(name);
                if (!normalized.equals(service.getNameNormalized())
                        && serviceRepository.existsByProjectIdAndNameNormalizedAndIdNot(
                                scope, normalized, serviceId)) {
                    throw duplicateName();
                }
                service.rename(name);
            }
        }
        if (request.description() != null) {
            service.setDescription(validateDescription(request.description()));
        }

        try {
            service = serviceRepository.saveAndFlush(service);
        }
        catch (DataIntegrityViolationException ex) {
            // corrida entre o existsBy acima e o update: o unique vence → 409
            throw duplicateName();
        }
        return ServiceResponse.from(service, TenantContext.role());
    }

    // --- PATCH .../services/{serviceId}/activation --------------------------

    /**
     * Ativação/desativação <b>idempotente</b>: se o serviço já está no estado
     * pedido, devolve o estado atual SEM escrita (o {@code updatedAt} não
     * avança — nada mudou). Em transição real o {@code PreUpdate} grava o novo
     * timestamp. <b>Não toca em {@code status}</b>: {@code active=false} +
     * {@code status=UNKNOWN} é um estado perfeitamente válido.
     */
    @Transactional
    public ServiceResponse activation(
            UUID projectId, UUID serviceId, ServiceActivationRequest request) {
        requireMutationRole();
        Project project = requireActiveProject(projectId);
        Service service = findService(project, serviceId);
        Role role = TenantContext.role();

        boolean target = request.active();
        if (service.isActive() == target) {
            return ServiceResponse.from(service, role);
        }

        service.setActive(target);
        return ServiceResponse.from(serviceRepository.saveAndFlush(service), role);
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

    // --- projeto ------------------------------------------------------------

    /** Projeto dentro do tenant corrente — id alheio/inexistente → 404 genérico. */
    private Project findProjectInTenant(UUID projectId) {
        return projectRepository
                .findByOrganizationIdAndId(TenantContext.organizationId(), projectId)
                .orElseThrow(ServiceService::notFound);
    }

    /** Ordem das mutações: projeto (404) → ativo (409) → payload/unicidade. */
    private Project requireActiveProject(UUID projectId) {
        Project project = findProjectInTenant(projectId);
        if (!project.isActive()) {
            throw new ApiException("PROJECT_INACTIVE", PROJECT_INACTIVE_MESSAGE, HttpStatus.CONFLICT);
        }
        return project;
    }

    /**
     * Combina os filtros opcionais da listagem. As oito variantes existem como
     * derived queries (Spring Data não aceita predicado opcional em método
     * derivado) e todas recebem o mesmo {@link Pageable} já ordenado.
     */
    private Page<Service> query(
            UUID projectId, Boolean active, ServiceStatus status, String search, Pageable pageable) {
        boolean filterByActive = active != null;
        boolean filterByStatus = status != null;
        boolean filterByName = search != null;
        if (filterByActive && filterByStatus && filterByName) {
            return serviceRepository.findByProjectIdAndActiveAndStatusAndNameNormalizedContaining(
                    projectId, active, status, search, pageable);
        }
        if (filterByActive && filterByStatus) {
            return serviceRepository.findByProjectIdAndActiveAndStatus(
                    projectId, active, status, pageable);
        }
        if (filterByActive && filterByName) {
            return serviceRepository.findByProjectIdAndActiveAndNameNormalizedContaining(
                    projectId, active, search, pageable);
        }
        if (filterByStatus && filterByName) {
            return serviceRepository.findByProjectIdAndStatusAndNameNormalizedContaining(
                    projectId, status, search, pageable);
        }
        if (filterByActive) {
            return serviceRepository.findByProjectIdAndActive(projectId, active, pageable);
        }
        if (filterByStatus) {
            return serviceRepository.findByProjectIdAndStatus(projectId, status, pageable);
        }
        if (filterByName) {
            return serviceRepository.findByProjectIdAndNameNormalizedContaining(
                    projectId, search, pageable);
        }
        return serviceRepository.findByProjectId(projectId, pageable);
    }

    // --- validação ----------------------------------------------------------

    /**
     * Nome: trim + checagens de domínio (a mesma regra do create, aplicada
     * aqui porque o PATCH só valida campos presentes — o bean validation do
     * {@code @Valid} não cobre "ausente" de propósito).
     */
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

    /** Descrição: opcional; trim; vazia vira {@code null}; teto da coluna. */
    private static String validateDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String description = raw.trim();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "description: must be at most " + MAX_DESCRIPTION_LENGTH + " characters",
                    HttpStatus.BAD_REQUEST);
        }
        return description.isEmpty() ? null : description;
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
     * {@code createdAt,asc}. (Espelha o {@code ProjectService} sem tocá-lo:
     * cada módulo é autocontido neste prompt.)
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
        return Service.normalizeName(raw);
    }

    // --- apoio --------------------------------------------------------------

    /** Serviço dentro do projeto já resolvido no tenant → 404 genérico. */
    private Service findService(Project project, UUID serviceId) {
        return serviceRepository
                .findByProjectIdAndId(project.getId(), serviceId)
                .orElseThrow(ServiceService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }

    private static ApiException duplicateName() {
        return new ApiException("SERVICE_NAME_DUPLICATE", DUPLICATE_NAME_MESSAGE, HttpStatus.CONFLICT);
    }
}
