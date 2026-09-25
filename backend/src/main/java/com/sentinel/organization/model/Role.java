package com.sentinel.organization.model;

/**
 * Papéis de um usuário DENTRO de uma organização (tenant).
 *
 * <p>O papel é sempre escopo-tenant: o mesmo usuário pode ser ADMIN em uma
 * organização e VIEWER em outra. Por isso ele NUNCA mora em claim de JWT
 * (claims do access token permanecem mínimas: {@code sub}, {@code iat},
 * {@code exp}) — é lido do banco, na resolução do tenant, pelo servidor.</p>
 *
 * <p>Domínio idêntico ao CHECK {@code memberships_role_check} da migration V4:
 * a enumeração é a validação na aplicação, o banco é a última barreira.</p>
 */
public enum Role {

    /** Gestão completa da organização: membros e (futuros) recursos do tenant. */
    ADMIN,

    /** Operação dos recursos do tenant sem gestão de membros. */
    OPERATOR,

    /** Somente leitura. */
    VIEWER
}
