-- Prompt 10 — Operational State: histórico de checagens (monitor_checks).
--
-- IMPORTANTE: esta migration é ADITIVA e a ÚNICA do prompt. As V1..V7 não são
-- alteradas (o Flyway valida o checksum delas em todo startup) e o catálogo
-- continua terminando na V7 — esta tabela guarda RESULTADO, não configuração.
--
-- IDs gerados PELA APLICAÇÃO (@PrePersist com UUID.randomUUID(), mesmo padrão
-- de Organization/Project/Service/Monitor) — por isso a coluna id NÃO tem
-- DEFAULT: o preenchimento é sempre responsabilidade da entidade JPA.
-- (ddl-auto=validate não valida DEFAULT — apenas nome, tipo e nullability.)
--
-- Log APPEND-ONLY: a linha nasce completa e nunca muda. Não existe coluna
-- updated_at (nem @PreUpdate na entidade): histórico não é editável.
-- Retenção/remoção é evolução futura (Prompt 11+) — NÃO existe job de delete
-- nesta versão, por decisão explícita de escopo.
--
-- SEM service_id redundante: o serviço é derivável pelo monitor
-- (monitor_checks.monitor_id → monitors.id → monitors.service_id). Guardar
-- também o serviço duplicaria dado e criaria duas fontes de verdade para a
-- mesma hierarquia.
--
-- SEM UNIQUE (monitor_id, started_at): DECISÃO DE DESIGN (orquestrador).
-- Testes controlam o relógio (Clock congelado) e duas execuções do mesmo
-- monitor poderiam ter startedAt idêntico — um UNIQUE silenciaria a segunda
-- linha e produziria falso-negativo em testes de sequência. A garantia de
-- "exatamente 1 check por execução normal" vem da ARQUITETURA (guarda de
-- in-flight do Prompt 09 + uma task por execução) e é provada por teste de
-- contagem. Também por isso NÃO existe executionId (YAGNI — reabriria o
-- contrato do Prompt 09).
--
-- started_at/finished_at TIMESTAMPTZ: instantes do relógio do Sentinel,
-- sempre com fuso — mesmo critério dos timestamps das V2..V7.
--
-- http_status INTEGER anulável: só existe quando a resposta foi RECEBIDA
-- (uma falha DNS/connect/timeout não tem status).
--
-- outcome VARCHAR(30) + CHECK: espelha o enum CheckOutcome (domínio de 4
-- valores, incluindo o refinamento 3xx = DEGRADED_SIGNAL deste prompt).
--
-- failure_category VARCHAR(30) + CHECK anulável: espelha o enum
-- CheckFailureCategory (espelho 1:1 do FailureCategory do Prompt 09). O
-- CHECK documenta o domínio e impede linha inválida escrita fora da API.
--
-- message VARCHAR(500) NOT NULL: mensagem de domínio SANITIZADA e truncada
-- pela aplicação. O banco não replica a sanitização — é regra de aplicação
-- (nunca persistir corpo, headers, tokens, cookies, query strings ou stack
-- traces); o tamanho máximo é a última barreira.
--
-- ON DELETE RESTRICT: excluir um monitor com histórico é recusado pelo banco
-- (mesmo racional de V6/V7 — a linha é HISTÓRICO do que aconteceu).
--
-- Índice (monitor_id, started_at DESC): cobre as DUAS leituras do prompt —
-- janela de avaliação (últimos N checks relevantes do monitor, mais recente
-- primeiro) e a API de histórico paginada. Sem ele, cada avaliação faria
-- sort de toda a história do monitor.
CREATE TABLE monitor_checks (
  id UUID PRIMARY KEY,
  monitor_id UUID NOT NULL REFERENCES monitors (id) ON DELETE RESTRICT,
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ NOT NULL,
  duration_millis BIGINT NOT NULL,
  http_status INTEGER,
  outcome VARCHAR(30) NOT NULL,
  failure_category VARCHAR(30),
  message VARCHAR(500) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT monitor_checks_outcome_check CHECK (outcome IN ('SUCCESS','DEGRADED_SIGNAL','TARGET_FAILURE','SENTINEL_INTERNAL_FAILURE')),
  CONSTRAINT monitor_checks_failure_category_check CHECK (failure_category IN ('DNS','CONNECT','TIMEOUT','TLS','HTTP_ERROR','SSRF_REVALIDATION','INTERNAL'))
);

CREATE INDEX idx_monitor_checks_monitor_started_desc ON monitor_checks (monitor_id, started_at DESC);
