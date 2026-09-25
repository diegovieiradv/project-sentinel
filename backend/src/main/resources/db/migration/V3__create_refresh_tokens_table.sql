-- Prompt 03 — Autenticação stateless: refresh tokens opacos.
--
-- Só o SHA-256 (hex, 64 chars) do token é persistido. O valor bruto existe
-- exclusivamente no cookie HttpOnly (Path=/api/v1/auth) — se o banco vazar,
-- nenhum refresh token é reutilizável.
--
-- UNIQUE (token_hash) é ao mesmo tempo a constraint de integridade e o índice
-- de lookup do endpoint /refresh (a coluna é o predicado de todas as buscas);
-- por isso NÃO existe índice secundário dedicado em token_hash.
--
-- ON DELETE CASCADE: excluir um usuário remove seus refresh tokens.
CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  -- VARCHAR (não CHAR): um hash SHA-256 hex é sempre exatamente 64 chars e o
  -- tipo alfanumérico padrão do Hibernate já valida como VARCHAR — CHAR(64)
  -- (bpchar) falharia no ddl-auto=validate com "wrong column type".
  token_hash VARCHAR(64) NOT NULL,
  issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT false,
  revoked_at TIMESTAMPTZ,
  CONSTRAINT refresh_tokens_token_hash_unique UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
