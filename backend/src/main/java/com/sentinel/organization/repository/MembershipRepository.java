package com.sentinel.organization.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sentinel.organization.model.Membership;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Persistência de memberships — a tabela de autorização por tenant.
 *
 * <p>A consulta {@link #findAllByOrganizationIdForUpdate} é o coração da regra
 * de último ADMIN (D5): {@code SELECT ... FOR UPDATE} em todos os memberships
 * da organização, dentro da transação que rebaixa/remove, serializando
 * operações concorrentes sobre o mesmo conjunto e tornando a contagem de
 * ADMINs consistente.</p>
 */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    boolean existsByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    /**
     * memberships do usuário com a organização já carregada (EAGER), ordenados
     * pela criação da ORGANIZAÇÃO — ordem estável e cronológica (a listagem do
     * usuário nunca é global, sempre parte daqui).
     */
    @Query("""
            select m from Membership m
            join m.organization o
            where m.user.id = :userId
            order by o.createdAt asc, m.createdAt asc
            """)
    List<Membership> findAllByUserIdWithOrganization(@Param("userId") UUID userId);

    /** membros de uma organização em ordem estável (antigos primeiro). */
    @Query("""
            select m from Membership m
            join m.organization o
            where o.id = :organizationId
            order by m.createdAt asc, m.id asc
            """)
    List<Membership> findAllByOrganizationId(@Param("organizationId") UUID organizationId);

    /**
     * Lock pessimista (SELECT ... FOR UPDATE) de TODOS os memberships da
     * organização — usado por updateRole/removeMember para contar ADMINs com
     * segurança transacional (rebaixar/remover o último ADMIN é rejeitado
     * dentro do próprio lock, não por checagem prévia sujeita a corrida).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from Membership m
            join m.organization o
            where o.id = :organizationId
            """)
    List<Membership> findAllByOrganizationIdForUpdate(@Param("organizationId") UUID organizationId);
}
