package com.sentinel.project.web;

import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.web.TenantScoped;
import com.sentinel.project.dto.ProjectCreateRequest;
import com.sentinel.project.dto.ProjectResponse;
import com.sentinel.project.dto.ProjectStatusRequest;
import com.sentinel.project.dto.ProjectUpdateRequest;
import com.sentinel.project.service.ProjectService;

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
 * Endpoints de projeto (Prompt 06) — todos tenant-scoped via path
 * {@code organizationId}: o {@link com.sentinel.organization.web.TenantInterceptor}
 * valida a membership ANTES do handler e devolve 404 genérico a não-membros.
 *
 * <p>O handler não recebe a organização como parâmetro: o tenant já está no
 * {@link com.sentinel.organization.context.TenantContext} e o DTO de criação
 * nem tem o campo (impossível criar projeto "em outra org" por payload).</p>
 *
 * <p><b>Sem DELETE</b> (nem handler, nem soft-delete implícito): a remoção não
 * existe neste prompt — desativação é o {@code .../status}. Um DELETE na rota
 * cai naturalmente no contrato global de {@code 405}.</p>
 *
 * <p>Autenticado pela cadeia de segurança + interceptor (mesmo padrão de
 * {@code OrganizationMemberController}); papel/papel-Usuário vêm do
 * TenantContext no service — nenhum papel é lido de token.</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** POST {name, description?} → 201 ProjectResponse (ADMIN/OPERATOR). */
    @PostMapping
    @TenantScoped
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(request));
    }

    /**
     * GET → 200 página no formato Spring Page (qualquer papel).
     *
     * @param page   0-based, default {@code 0}
     * @param size   itens por página, default {@code 20}, máximo {@code 100}
     * @param sort   allowlist {@code createdAt|name|id} + direção, default
     *               {@code createdAt,asc}; o desempate {@code id asc} é fixo
     * @param active filtro opcional por status ({@code true|false})
     * @param name   busca opcional por nome (case-insensitive, subcadeia)
     */
    @GetMapping
    @TenantScoped
    public Page<ProjectResponse> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "name", required = false) String name) {
        return projectService.list(page, size, sort, active, name);
    }

    /** GET /{projectId} → 200 detalhe | 404 genérico (inexistente/outra org). */
    @GetMapping("/{projectId}")
    @TenantScoped
    public ProjectResponse get(@PathVariable String projectId) {
        return projectService.get(parseProjectId(projectId));
    }

    /** PATCH {name?, description?} → 200 (ADMIN/OPERATOR). DTO fechado. */
    @PatchMapping("/{projectId}")
    @TenantScoped
    public ProjectResponse update(
            @PathVariable String projectId, @RequestBody ProjectUpdateRequest request) {
        return projectService.update(parseProjectId(projectId), request);
    }

    /** PATCH {active: boolean} → 200, idempotente (ADMIN/OPERATOR). */
    @PatchMapping("/{projectId}/status")
    @TenantScoped
    public ProjectResponse changeStatus(
            @PathVariable String projectId, @Valid @RequestBody ProjectStatusRequest request) {
        return projectService.changeStatus(parseProjectId(projectId), request);
    }

    /**
     * ID malformado não pode cair em {@code MethodArgumentTypeMismatchException}
     * (que o handler genérico traduziria como 400/500): é o mesmo 404 genérico
     * de projeto inexistente — anti-enumeração, igual ao resto da API.
     */
    private static UUID parseProjectId(String raw) {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
        }
    }
}
