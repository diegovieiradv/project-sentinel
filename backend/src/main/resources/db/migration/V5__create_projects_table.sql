-- Prompt 06 — Módulo Projects: projetos de uma organização (tenant).
--
-- IDs gerados PELA APLICAÇÃO (@PrePersist com UUID.randomUUID(), mesmo padrão
-- de Organization/Membership em V4) — por isso a coluna id NÃO tem DEFAULT:
-- o preenchimento é sempre responsabilidade da entidade JPA. (Mesmo critério
-- documentado em V4; ddl-auto=validate não valida DEFAULT — apenas nome, tipo
-- e nullability de coluna.)
--
-- updated_at é mantido pela aplicação (@PreUpdate) — sem trigger, mesmo
-- critério das migrations V2/V3/V4 (evita duplicidade de responsabilidade).
--
-- name: CHECK espelha a validação da API (nome vazio ou só espaços não existe
-- nem no banco nem no service), mesmo racional do organizations_name_not_blank.
--
-- name_normalized: cópia normalizada de name calculada PELA APLICAÇÃO
-- (trim + lowercase com Locale.ROOT) e persistida — é a chave de unicidade e o
-- alvo da busca por nome (case-insensitive sem funções de coluna na query).
-- Coluna derivada, mas materializada de propósito: uma restrição UNIQUE não
-- seria expressível sobre expressão normalizada em cada linha sem índice
-- funcional, e a aplicação precisaria recalcular tudo em toda leitura.
--
-- UNIQUE (organization_id, name_normalized): mesmo nome normalizado não pode
-- existir DUAS VEZES na mesma organização — ativo OU inativo. Organizações
-- diferentes podem repetir o nome. É a última linha de defesa contra a corrida
-- entre o pré-check do service e o INSERT (DataIntegrityViolationException →
-- 409 na aplicação, mesmo padrão de memberships_user_org_unique).
--
-- active BOOLEAN NOT NULL DEFAULT true: todo projeto nasce ativo; a
-- desativação é soft-delete (não existe DELETE na API deste prompt).
--
-- Índice EXTRA em organization_id: NÃO criado — o índice único já tem
-- organization_id como coluna de PREFIXO e cobre todas as leituras por tenant
-- (listagem, filtro por active e busca por nome são igualmente por tenant).
-- Diferente de memberships, cujo unique é (user_id, organization_id) e por isso
-- NÃO cobre o predicado por organização (daí idx_memberships_organization_id).
--
-- ON DELETE CASCADE: excluir a organização remove seus projetos — mesmo padrão
-- da FK de memberships em V4.
CREATE TABLE projects (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
  name VARCHAR(120) NOT NULL,
  name_normalized VARCHAR(120) NOT NULL,
  description VARCHAR(1000),
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT projects_org_name_normalized_unique UNIQUE (organization_id, name_normalized),
  CONSTRAINT projects_name_not_blank CHECK (trim(name) <> '')
);
