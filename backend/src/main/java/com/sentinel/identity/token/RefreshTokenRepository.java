package com.sentinel.identity.token;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistência dos refresh tokens (Prompt 03).
 *
 * <p>Toda mutação é atômica via UPDATE condicional: a corrida entre dois
 * refreshes simultâneos é resolvida pelo número de linhas afetadas (1 = ganhou
 * a rotação; 0 = o token já não estava mais ativo), sem SELECT ... FOR UPDATE
 * e sem janela em que dois tokens ativos coexistam para o mesmo valor.</p>
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revoga o token de forma condicional (apenas se ainda ativo e não expirado).
     *
     * @return 1 quando esta transação foi a que revogou; 0 quando outro fluxo
     *         já havia revogado, o token expirou ou ele não existe mais.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revoked = true,
                   t.revokedAt = :now
             where t.tokenHash = :tokenHash
               and t.revoked = false
               and t.expiresAt > :now
            """)
    int revokeIfActive(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /**
     * Revoga TODOS os tokens ativos de um usuário — usado quando um token já
     * revogado é apresentado de novo (provável roubo/uso indevido).
     *
     * @return quantidade de tokens revogados nesta chamada.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revoked = true,
                   t.revokedAt = :now
             where t.user.id = :userId
               and t.revoked = false
            """)
    int revokeAllActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
