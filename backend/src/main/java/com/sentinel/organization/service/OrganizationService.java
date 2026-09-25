package com.sentinel.organization.service;

import java.util.List;

import com.sentinel.error.ApiException;
import com.sentinel.identity.user.User;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.dto.OrganizationCreateRequest;
import com.sentinel.organization.dto.OrganizationResponse;
import com.sentinel.organization.model.Membership;
import com.sentinel.organization.model.Organization;
import com.sentinel.organization.model.Role;
import com.sentinel.organization.repository.MembershipRepository;
import com.sentinel.organization.repository.OrganizationRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de organização do Prompt 04.
 *
 * <p>Criação é <b>atômica</b>: organização + Membership ADMIN do criador são
 * persistidas na MESMA transação ({@code @Transactional} REQUIRED), de modo
 * que não existe cenário em que a organização exista sem um ADMIN — falha em
 * qualquer um dos dois passos desfaz o outro.</p>
 *
 * <p>Listagem é sempre <b>porMembership</b> (nunca global): a query parte das
 * memberships do usuário, então "ver tudo o que não é seu" é impossível por
 * construção, não por filtro.</p>
 */
@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            MembershipRepository membershipRepository) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * POST /api/v1/organizations — cria a organização e o vínculo ADMIN do
     * criador atomicamente (uma transação, dois saves).
     */
    @Transactional
    public OrganizationResponse create(User creator, OrganizationCreateRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            // guarda do service para chamadas fora do controller (bean validation
            // do @Valid já cobre o fluxo HTTP); mensagem no formato "campo: erro".
            throw new ApiException("VALIDATION_ERROR", "name: must not be blank", HttpStatus.BAD_REQUEST);
        }

        Organization organization = organizationRepository.saveAndFlush(new Organization(name));
        Membership adminMembership =
                membershipRepository.saveAndFlush(new Membership(creator, organization, Role.ADMIN));

        return OrganizationResponse.from(organization, adminMembership.getRole());
    }

    /** GET /api/v1/organizations — só as organizações em que o usuário é membro. */
    @Transactional(readOnly = true)
    public List<OrganizationResponse> listForUser(User user) {
        return membershipRepository.findAllByUserIdWithOrganization(user.getId()).stream()
                .map(membership -> OrganizationResponse.from(membership.getOrganization(), membership.getRole()))
                .toList();
    }

    /**
     * GET /api/v1/organizations/{organizationId} — o TenantInterceptor já
     * provou membership; aqui só se materializa a resposta com o papel do
     * usuário autenticado.
     */
    @Transactional(readOnly = true)
    public OrganizationResponse currentOrganization() {
        TenantContext.Context context = TenantContext.require();
        return OrganizationResponse.from(context.organization(), context.role());
    }
}
