package com.sentinel.organization.web;

import java.util.List;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.organization.dto.AddMemberRequest;
import com.sentinel.organization.dto.MemberResponse;
import com.sentinel.organization.dto.UpdateMemberRoleRequest;
import com.sentinel.organization.service.MembershipService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestão de membros da organização — todos os endpoints são tenant-scoped
 * (path {@code organizationId}) e exigem ADMIN da própria organização
 * (service layer, papel lido do TenantContext; nunca de JWT).
 *
 * <p>Listagem é admin-only porque é o único payload da API que expõe
 * e-mails de outros usuários.</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/members")
public class OrganizationMemberController {

    private final MembershipService membershipService;

    public OrganizationMemberController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    /** GET → 200 lista de membros (ADMIN). */
    @GetMapping
    @TenantScoped
    public List<MemberResponse> list() {
        return membershipService.listMembers();
    }

    /** POST {email, role} → 201 MemberResponse (ADMIN). */
    @PostMapping
    @TenantScoped
    public ResponseEntity<MemberResponse> add(@Valid @RequestBody AddMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(membershipService.addMember(request));
    }

    /** PATCH {role} → 200 MemberResponse (ADMIN). */
    @PatchMapping("/{membershipId}")
    @TenantScoped
    public MemberResponse updateRole(
            @PathVariable String membershipId,
            @Valid @RequestBody UpdateMemberRoleRequest request) {
        return membershipService.updateRole(parseMembershipId(membershipId), request);
    }

    /** DELETE → 204 sem corpo (ADMIN). */
    @DeleteMapping("/{membershipId}")
    @TenantScoped
    public ResponseEntity<Void> remove(@PathVariable String membershipId) {
        membershipService.removeMember(parseMembershipId(membershipId));
        return ResponseEntity.noContent().build();
    }

    /**
     * ID malformado não pode cair em {@code MethodArgumentTypeMismatchException}
     * (que o handler genérico traduziria como 500): é o mesmo 404 genérico de
     * membership inexistente.
     */
    private static UUID parseMembershipId(String raw) {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
        }
    }
}
