package com.sentinel.organization.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.identity.user.User;
import com.sentinel.identity.user.UserRepository;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.dto.AddMemberRequest;
import com.sentinel.organization.dto.MemberResponse;
import com.sentinel.organization.dto.UpdateMemberRoleRequest;
import com.sentinel.organization.model.Membership;
import com.sentinel.organization.model.Organization;
import com.sentinel.organization.model.Role;
import com.sentinel.organization.repository.MembershipRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de membros do Prompt 04 — todos os endpoints de {@code /members}
 * exigem ADMIN da própria organização.
 *
 * <p><b>Autorização no service, papel do servidor:</b> o papel nunca vem de
 * claim de JWT (claims continuam mínimas: sub/iat/exp). Ele é lido do
 * {@link TenantContext}, populado pelo interceptor a partir do banco, na
 * requisição corrente.</p>
 *
 * <p><b>Regra de último ADMIN (D5):</b> {@code updateRole}/{@code removeMember}
 * primeiro travam TODOS os memberships da organização com
 * {@code SELECT ... FOR UPDATE} e só então contam ADMINs. Rebaixar/remover o
 * último ADMIN é rejeitado (409) dentro do lock — operações concorrentes sobre
 * o mesmo tenant são serializadas em vez de decidirem sobre uma contagem
 * potencialmente obsoleta.</p>
 */
@Service
public class MembershipService {

    /** Mensagem do conflito de último admin (contrato público da API). */
    public static final String LAST_ADMIN_MESSAGE = "Organization must keep at least one admin";

    private static final String ROLE_VALUES_MESSAGE = "role: must be one of ADMIN, OPERATOR, VIEWER";

    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;

    public MembershipService(MembershipRepository membershipRepository, UserRepository userRepository) {
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
    }

    // --- leitura ----------------------------------------------------------

    /** GET .../members — admin-only (protege e-mails dos membros). */
    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers() {
        requireAdmin();
        return membershipRepository.findAllByOrganizationId(TenantContext.organizationId()).stream()
                .map(MemberResponse::from)
                .toList();
    }

    // --- escrita ----------------------------------------------------------

    /**
     * POST .../members — adiciona usuário EXISTENTE com papel explícito.
     *
     * <p>E-mail não cadastrado → 404 genérico (a resposta é a mesma de
     * organização inexistente: não revela se a conta existe). Já é membro →
     * 409 (inclusive na corrida, via unique(user_id, organization_id)).</p>
     */
    @Transactional
    public MemberResponse addMember(AddMemberRequest request) {
        requireAdmin();
        Role role = parseRole(request.role());
        Organization organization = TenantContext.organization();

        User invited = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(MembershipService::notFound);
        if (membershipRepository.existsByUserIdAndOrganizationId(invited.getId(), organization.getId())) {
            throw membershipAlreadyExists();
        }

        Membership membership;
        try {
            membership = membershipRepository.saveAndFlush(new Membership(invited, organization, role));
        }
        catch (DataIntegrityViolationException ex) {
            // corrida entre o existsBy acima e o insert: o unique vence → 409
            throw membershipAlreadyExists();
        }
        return MemberResponse.from(membership);
    }

    /**
     * PATCH .../members/{membershipId} — troca o papel. Membership de outra
     * organização (ou inexistente) → 404 genérico; último ADMIN → 409.
     */
    @Transactional
    public MemberResponse updateRole(UUID membershipId, UpdateMemberRoleRequest request) {
        requireAdmin();
        Role newRole = parseRole(request.role());
        Organization organization = TenantContext.organization();

        List<Membership> locked = lockMembershipsOf(organization);
        Membership target = findIn(locked, membershipId);
        if (target.getRole() == Role.ADMIN && newRole != Role.ADMIN && countAdmins(locked) == 1) {
            throw lastAdminRequired();
        }

        target.setRole(newRole);
        return MemberResponse.from(membershipRepository.saveAndFlush(target));
    }

    /**
     * DELETE .../members/{membershipId} — 204 sem corpo. Membership de outra
     * organização (ou inexistente) → 404 genérico; remover o último ADMIN
     * (inclusive a si mesmo) → 409.
     */
    @Transactional
    public void removeMember(UUID membershipId) {
        requireAdmin();
        Organization organization = TenantContext.organization();

        List<Membership> locked = lockMembershipsOf(organization);
        Membership target = findIn(locked, membershipId);
        if (target.getRole() == Role.ADMIN && countAdmins(locked) == 1) {
            throw lastAdminRequired();
        }

        membershipRepository.delete(target);
    }

    // --- autorização ------------------------------------------------------

    /**
     * Papel lido do TenantContext (servidor) — nunca de token. Qualquer papel
     * diferente de ADMIN cai aqui com o MESMO 403 genérico, sem detalhar a
     * política de acesso.
     */
    private static void requireAdmin() {
        if (TenantContext.require().role() != Role.ADMIN) {
            throw new ApiException("FORBIDDEN", "Access denied", HttpStatus.FORBIDDEN);
        }
    }

    // --- último ADMIN (transacional) --------------------------------------

    private List<Membership> lockMembershipsOf(Organization organization) {
        return membershipRepository.findAllByOrganizationIdForUpdate(organization.getId());
    }

    private static Membership findIn(List<Membership> locked, UUID membershipId) {
        return locked.stream()
                .filter(membership -> membership.getId().equals(membershipId))
                .findFirst()
                // membership de OUTRA organização não está nesta lista → mesmo 404
                // de membership inexistente (não revela a que org ele pertence)
                .orElseThrow(MembershipService::notFound);
    }

    private static long countAdmins(List<Membership> memberships) {
        return memberships.stream().filter(membership -> membership.getRole() == Role.ADMIN).count();
    }

    // --- apoio ------------------------------------------------------------

    /**
     * Papel explícito do body, sem default. Valor fora do domínio → 400 com
     * mensagem de campo (em vez do 500 que a desserialização de enum
     * produziria no handler genérico).
     */
    private static Role parseRole(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new ApiException("VALIDATION_ERROR", "role: must not be blank", HttpStatus.BAD_REQUEST);
        }
        try {
            return Role.valueOf(value.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException("VALIDATION_ERROR", ROLE_VALUES_MESSAGE, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Mesma normalização (trim + lowercase) que identity aplica no registro —
     * aqui replicada porque {@code UserService.normalizeEmail} é package-private
     * no pacote identity e o escopo do Prompt 04 não permite alterá-lo.
     */
    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }

    private static ApiException membershipAlreadyExists() {
        return new ApiException("MEMBERSHIP_ALREADY_EXISTS", "Membership already exists", HttpStatus.CONFLICT);
    }

    private static ApiException lastAdminRequired() {
        return new ApiException("LAST_ADMIN_REQUIRED", LAST_ADMIN_MESSAGE, HttpStatus.CONFLICT);
    }
}
