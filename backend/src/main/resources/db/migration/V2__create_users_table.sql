-- Prompt 02 — Identity: tabela de usuários.
-- UUID via gen_random_uuid(): built-in no PostgreSQL 13+ (sem extensão pgcrypto).
-- updated_at é mantido pela aplicação (@PreUpdate na entidade) — sem trigger
-- para evitar duplicidade de responsabilidade entre SQL e JPA.
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(255) NOT NULL,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT users_email_unique UNIQUE (email)
);

CREATE INDEX idx_users_email ON users (email);
