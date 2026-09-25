package com.sentinel.organization.web;

import java.util.List;

import com.sentinel.error.ApiException;
import com.sentinel.identity.user.User;
import com.sentinel.organization.dto.OrganizationCreateRequest;
import com.sentinel.organization.dto.OrganizationResponse;
import com.sentinel.organization.service.OrganizationService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de organização (tenant) do Prompt 04.
 *
 * <p>Criar e listar são operações do próprio usuário (sem tenant em
 * resolução); o detalhe é tenant-scoped via path variable
 * {@code organizationId} — o {@link TenantInterceptor} valida a membership
 * antes do handler e devolve 404 genérico para quem não é membro.</p>
 *
 * <p>Papel nunca vem do JWT: no detalhe ele é lido do TenantContext; na
 * listagem, da própria linha de membership.</p>
 */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    /** POST → 201 OrganizationResponse com role=ADMIN do criador (transação única). */
    @PostMapping
    public ResponseEntity<OrganizationResponse> create(
            @Valid @RequestBody OrganizationCreateRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(organizationService.create(requireUser(user), request));
    }

    /** GET → 200 apenas as organizações do usuário autenticado (nunca globais). */
    @GetMapping
    public List<OrganizationResponse> list(@AuthenticationPrincipal User user) {
        return organizationService.listForUser(requireUser(user));
    }

    /** GET /{organizationId} → 200 membro / 404 genérico para não membro. */
    @GetMapping("/{organizationId}")
    @TenantScoped
    public OrganizationResponse get() {
        return organizationService.currentOrganization();
    }

    /**
     * O principal é a entidade User (converter JWT do Prompt 03). A rota é
     * authenticated na cadeia de segurança, mas null aqui não pode virar NPE
     * (500): responde o mesmo 401 do resto da API.
     */
    private static User requireUser(User user) {
        if (user == null) {
            throw new ApiException("UNAUTHORIZED", "Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return user;
    }
}
