-- Prompt 04 — Fundação multi-tenant: organizações (tenants) e memberships.
--
-- IDs gerados PELA APLICAÇÃO (@PrePersist com UUID.randomUUID(), mesmo padrão
-- de User e RefreshToken) — por isso as colunas id NÃO têm DEFAULT: o
-- preenchimento é sempre responsabilidade da entidade JPA, fonte única de
-- geração de identificador. (Divergência consciente de V2/V3, que declaram
-- DEFAULT gen_random_uuid() como fallback defensivo para inserts manuais;
-- aqui o DDL do Prompt 04 mantém a geração só na aplicação. ddl-auto=validate
-- não valida DEFAULT — apenas nome, tipo e nullability de coluna.)
--
-- updated_at é mantido pela aplicação (@PreUpdate) — sem trigger, mesmo
-- critério das migrations V2/V3 (evita duplicidade de responsabilidade).
--
-- organizations.name: CHECK espelha o @NotBlank da API — não existe
-- organização com nome vazio ou só espaços, nem no banco nem no service.
-- Sem índice em organizations.name: não há busca por nome neste prompt
-- (indexação especulativa fica de fora).
--
-- memberships.role: CHECK é a última barreira do domínio de papéis — a
-- aplicação já valida via enum Role; o banco garante o mesmo conjunto mesmo
-- diante de insert manual.
--
-- UNIQUE (user_id, organization_id): um usuário tem no máximo UM membership
-- por organização. É a constraint que transforma a corrida de duplicidade em
-- 409 na aplicação (DataIntegrityViolationException capturado no service).
--
-- ON DELETE CASCADE: excluir usuário/organização remove seus memberships.
--
-- Índices: idx_memberships_organization_id é o caminho de toda leitura por
-- tenant (lista de membros e o lock de último ADMIN). idx_memberships_user_id
-- é mantido conforme o DDL aprovado (o prefixo do índice único já cobre o
-- mesmo predicado, mas o índice dedicado torna a intenção explícita e mantém
-- simetria com as demais tabelas de domínio).
CREATE TABLE organizations (
  id UUID PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT organizations_name_not_blank CHECK (trim(name) <> '')
);

CREATE TABLE memberships (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
  role VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT memberships_user_org_unique UNIQUE (user_id, organization_id),
  CONSTRAINT memberships_role_check CHECK (role IN ('ADMIN', 'OPERATOR', 'VIEWER'))
);

CREATE INDEX idx_memberships_user_id ON memberships (user_id);
CREATE INDEX idx_memberships_organization_id ON memberships (organization_id);
