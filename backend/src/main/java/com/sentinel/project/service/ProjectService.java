package com.sentinel.project.service;

import java.util.List;
import java.util.UUID;

import com.sentinel.alerting.AlertLifecycleService;
import com.sentinel.error.ApiException;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.model.Organization;
import com.sentinel.organization.model.Role;
import com.sentinel.project.alert.model.AlertResolutionReason;
import com.sentinel.project.dto.ProjectCreateRequest;
import com.sentinel.project.dto.ProjectResponse;
import com.sentinel.project.dto.ProjectStatusRequest;
import com.sentinel.project.dto.ProjectUpdateRequest;
import com.sentinel.project.model.Project;
import com.sentinel.project.repository.ProjectRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de projeto do Prompt 06 — o primeiro recurso de domínio do tenant.
 *
 * <p><b>Autorização no service, papel do servidor:</b> leitura para qualquer
 * papel (o {@code TenantInterceptor} já provou a membership antes do handler);
 * mutação exige {@code ADMIN} ou {@code OPERATOR} via
 * {@link #requireMutationRole()}. O papel vem do {@link TenantContext},
 * populado do banco na requisição corrente — nunca de claim de JWT.</p>
 *
 * <p><b>Unicidade por organização (nome normalizado):</b> duas camadas. O
 * pré-check no repositório devolve o {@code 409} imediato com mensagem
 * amigável; a corrida entre o check e o INSERT é absorvida pelo
 * {@code UNIQUE (organization_id, name_normalized)} da migration V5, traduzido
 * em {@code DataIntegrityViolationException} → o MESMO 409 — nunca mensagem
 * crua de SQL para o cliente.</p>
 *
 * <p><b>Isolamento:</b> toda leitura passa por
 * {@code findByOrganizationIdAndId}, escopada pelo tenant do TenantContext —
 * projeto de outra organização responde o 404 genérico, sem revelar que existe
 * em algum outro lugar.</p>
 */
@Service
public class ProjectService {

    /** Mensagem do conflito de nome (contrato público da API). */
    public static final String DUPLICATE_NAME_MESSAGE =
            "Project with this name already exists in this organization";

    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_PAGE_SIZE = 100;

    /** Campos ordenáveis: allowlist explícita (nunca propriedade vinda do cliente sem checagem). */
    private static final List<String> SORT_FIELDS = List.of("createdAt", "name", "id");

    private final ProjectRepository projectRepository;
    private final AlertLifecycleService alertLifecycleService;

    public ProjectService(
            ProjectRepository projectRepository, AlertLifecycleService alertLifecycleService) {
        this.projectRepository = projectRepository;
        this.alertLifecycleService = alertLifecycleService;
    }

    // --- POST .../projects --------------------------------------------------

    /**
     * Cria o projeto já vinculado ao tenant do path (o DTO nem tem campo de
     * organização) e nasce {@code active=true}.
     */
    @Transactional
    public ProjectResponse create(ProjectCreateRequest request) {
        requireMutationRole();
        String name = validateName(request.name());
        String description = validateDescription(request.description());
        Organization organization = TenantContext.organization();

        String normalized = Project.normalizeName(name);
        if (projectRepository.existsByOrganizationIdAndNameNormalized(organization.getId(), normalized)) {
            throw duplicateName();
        }

        Project project;
        try {
            project = projectRepository.saveAndFlush(new Project(organization, name, description));
        }
        catch (DataIntegrityViolationException ex) {
            // corrida entre o existsBy acima e o insert: o unique vence → 409
            throw duplicateName();
        }
        return ProjectResponse.from(project, TenantContext.role());
    }

    // --- GET .../projects ---------------------------------------------------

    /**
     * Página de projetos do tenant com filtros opcionais (status e nome) e
     * ordenação determinística. Qualquer papel pode ler.
     */
    @Transactional(readOnly = true)
    public Page<ProjectResponse> list(int page, int size, String sort, Boolean active, String name) {
        Pageable pageable = PageRequest.of(validatePage(page), validateSize(size), parseSort(sort));
        String search = normalizeSearch(name);

        Page<Project> projects;
        boolean filterByActive = active != null;
        if (filterByActive && search != null) {
            projects = projectRepository.findByOrganizationIdAndActiveAndNameNormalizedContaining(
                    tenantId(), active, search, pageable);
        }
        else if (filterByActive) {
            projects = projectRepository.findByOrganizationIdAndActive(tenantId(), active, pageable);
        }
        else if (search != null) {
            projects = projectRepository.findByOrganizationIdAndNameNormalizedContaining(
                    tenantId(), search, pageable);
        }
        else {
            projects = projectRepository.findByOrganizationId(tenantId(), pageable);
        }
        Role role = TenantContext.role();
        return projects.map(project -> ProjectResponse.from(project, role));
    }

    /** GET .../projects/{projectId} → 200 ou 404 genérico (escopo do tenant). */
    @Transactional(readOnly = true)
    public ProjectResponse get(UUID projectId) {
        return ProjectResponse.from(findInTenant(projectId), TenantContext.role());
    }

    // --- PATCH .../projects/{projectId} -------------------------------------

    /**
     * Atualização parcial: campo ausente não altera. Renomear revalida a
     * unicidade contra TODOS os projetos do tenant, exceto o próprio
     * (projeto não pode colidir consigo mesmo).
     */
    @Transactional
    public ProjectResponse update(UUID projectId, ProjectUpdateRequest request) {
        requireMutationRole();
        Project project = findInTenant(projectId);
        UUID tenantId = tenantId();

        if (request.name() != null) {
            String name = validateName(request.name());
            if (!name.equals(project.getName())) {
                String normalized = Project.normalizeName(name);
                if (!normalized.equals(project.getNameNormalized())
                        && projectRepository.existsByOrganizationIdAndNameNormalizedAndIdNot(
                                tenantId, normalized, projectId)) {
                    throw duplicateName();
                }
                project.rename(name);
            }
        }
        if (request.description() != null) {
            project.setDescription(validateDescription(request.description()));
        }

        try {
            project = projectRepository.saveAndFlush(project);
        }
        catch (DataIntegrityViolationException ex) {
            // corrida entre o existsBy acima e o update: o unique vence → 409
            throw duplicateName();
        }
        return ProjectResponse.from(project, TenantContext.role());
    }

    // --- PATCH .../projects/{projectId}/status ------------------------------

    /**
     * Ativação/desativação <b>idempotente</b>: se o projeto já está no estado
     * pedido, devolve o estado atual SEM escrita (o {@code updatedAt} não
     * avança — nada mudou). Em transição real o {@code @PreUpdate} grava o novo
     * timestamp.
     */
    @Transactional
    public ProjectResponse changeStatus(UUID projectId, ProjectStatusRequest request) {
        requireMutationRole();
        Project project = findInTenant(projectId);
        Role role = TenantContext.role();

        boolean target = request.active();
        if (project.isActive() == target) {
            return ProjectResponse.from(project, role);
        }

        project.setActive(target);
        Project saved = projectRepository.saveAndFlush(project);
        if (!target) {
            // Desativação congela o projeto inteiro: fecha com PARENT_INACTIVE
            // os alertas abertos de TODOS os serviços (Prompt 13).
            alertLifecycleService.resolveOpenForProject(
                    projectId, AlertResolutionReason.PARENT_INACTIVE);
        }
        return ProjectResponse.from(saved, role);
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
     * fixo em {@code id asc} — a ordem é sempre determinística, mesmo com
     * timestamps iguais, e nenhuma propriedade arbitrária vira coluna de ORDER
     * BY. Default: {@code createdAt,asc}.
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
        return Project.normalizeName(raw);
    }

    // --- apoio --------------------------------------------------------------

    /**
     * Projeto dentro do tenant corrente. Id de OUTRA organização não resolve
     * aqui → mesmo 404 genérico de id inexistente (anti-enumeração).
     */
    private Project findInTenant(UUID projectId) {
        return projectRepository
                .findByOrganizationIdAndId(tenantId(), projectId)
                .orElseThrow(ProjectService::notFound);
    }

    private static UUID tenantId() {
        return TenantContext.organizationId();
    }

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }

    private static ApiException duplicateName() {
        return new ApiException("PROJECT_NAME_DUPLICATE", DUPLICATE_NAME_MESSAGE, HttpStatus.CONFLICT);
    }
}
