package com.sentinel.organization.repository;

import java.util.UUID;

import com.sentinel.organization.model.Organization;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de organizações. As leituras por usuário partem de
 * {@link MembershipRepository} (listagem = memberships do usuário), nunca de
 * uma varredura global de organizações — a API não tem endpoint "todas as
 * organizações".
 */
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
