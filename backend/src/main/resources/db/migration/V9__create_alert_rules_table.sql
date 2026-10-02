-- Prompt 12 — Alert Rules: configuração de regras de alerta
-- (Organization → Project → Service → AlertRule, com escopo opcional de Monitor).
--
-- IMPORTANTE: esta migration é ADITIVA e a ÚNICA do prompt. As V1..V8 não são
-- alteradas (o Flyway valida o checksum delas em todo startup) e o catálogo
-- continua terminando na V8 — esta tabela guarda CONFIGURAÇÃO de condição,
-- nunca resultado avaliado (nenhuma linha de alerta é criada neste prompt).
--
-- IDs gerados PELA APLICAÇÃO (@PrePersist com UUID.randomUUID(), mesmo padrão
-- de Organization/Project/Service/Monitor) — por isso a coluna id NÃO tem
-- DEFAULT: o preenchimento é sempre responsabilidade da entidade JPA.
-- (ddl-auto=validate não valida DEFAULT — apenas nome, tipo e nullability.)
--
-- updated_at é mantido pela aplicação (@PreUpdate) — sem trigger, mesmo
-- critério das migrations V2..V7. Ao contrário de V8 (histórico append-only),
-- alert_rules é configuração EDITÁVEL (PATCH), então a coluna existe.
--
-- name / name_normalized: idênticos ao padrão de services/monitors (trim +
-- lowercase com Locale.ROOT calculado PELA APLICAÇÃO) — a chave de unicidade é
-- composta, então o MESMO nome de regra pode existir em serviços diferentes.
--
-- UNIQUE (service_id, name_normalized): mesmo nome normalizado não existe duas
-- vezes no mesmo serviço — ativo OU inativo (desativar não libera o nome).
-- É a última linha de defesa contra a corrida entre o pré-check do service e
-- o INSERT (DataIntegrityViolationException → 409 na aplicação, mesmo padrão
-- de services/monitors).
--
-- type: VARCHAR(30) + CHECK espelha o enum AlertRuleType (5 valores).
-- operator: VARCHAR(30) + CHECK espelha o enum AlertOperator (5 valores).
--   A MATRIZ type × operator (qual operador é permitido em qual tipo) é
--   validada pela aplicação (AlertRuleValidationMatrix) — o banco amarra o
--   domínio fechado dos enums, não a matriz cruzada.
--
-- threshold_numeric: NUMERIC(19,4) anulável — usado por AVAILABILITY,
--   SUCCESS_RATE, AVERAGE_LATENCY e TARGET_FAILURE_COUNT. A validação rica
--   (faixa 0..100 com escala ≤ 2, latência > 0, contagem inteira ≥ 0) é da
--   aplicação; os CHECKs condicionais abaixo são a última barreira para
--   estados obviamente impossíveis.
-- threshold_status: VARCHAR(20) anulável — usado APENAS por SERVICE_STATUS
--   (DEGRADED/UNAVAILABLE). Nunca se guarda 'UNAVAILABLE' dentro de um campo
--   decimal: os dois thresholds são MUTUAMENTE EXCLUSIVOS, também por CHECK.
--
-- window: VARCHAR(30) + CHECK espelha o enum EvaluationWindow (5 valores).
--   "window" é PALAVRA RESERVADA do PostgreSQL (funções de janela) — a coluna
--   é citada com aspas duplas no DDL; o nome lógico continua `window` (a
--   entidade JPA e o contrato JSON não mudam).
-- severity: VARCHAR(10) + CHECK espelha o enum AlertSeverity (SEV1..SEV4).
-- active: BOOLEAN NOT NULL DEFAULT true — toda regra nasce ativa; a
--   desativação é soft-delete via rota .../activation (não existe DELETE).
--
-- ON DELETE RESTRICT em ambas FKs: excluir um serviço/monitor com regras é
-- recusado pelo banco (a linha de alert_rules é HISTÓRICO da configuração,
-- mesmo racional de V6/V7).
--
-- Índices:
--   - (service_id) já coberto pelo prefixo do UNIQUE (service_id, name_normalized);
--   - (monitor_id) para lookup de regras por monitor (Service-level não usa);
--   - (active) para filtrar regras elegíveis ao futuro Alert Engine.
CREATE TABLE alert_rules (
  id UUID PRIMARY KEY,
  service_id UUID NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
  monitor_id UUID REFERENCES monitors (id) ON DELETE RESTRICT,
  name VARCHAR(120) NOT NULL,
  name_normalized VARCHAR(120) NOT NULL,
  type VARCHAR(30) NOT NULL,
  operator VARCHAR(30) NOT NULL,
  threshold_numeric NUMERIC(19,4),
  threshold_status VARCHAR(20),
  "window" VARCHAR(30) NOT NULL,
  severity VARCHAR(10) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT alert_rules_service_name_normalized_unique UNIQUE (service_id, name_normalized),
  CONSTRAINT alert_rules_name_not_blank CHECK (trim(name) <> ''),
  CONSTRAINT alert_rules_type_check CHECK (type IN ('AVAILABILITY','SUCCESS_RATE','AVERAGE_LATENCY','TARGET_FAILURE_COUNT','SERVICE_STATUS')),
  CONSTRAINT alert_rules_operator_check CHECK (operator IN ('LESS_THAN','LESS_THAN_OR_EQUAL','GREATER_THAN','GREATER_THAN_OR_EQUAL','EQUAL')),
  CONSTRAINT alert_rules_window_check CHECK ("window" IN ('FIVE_MINUTES','FIFTEEN_MINUTES','ONE_HOUR','SIX_HOURS','TWENTY_FOUR_HOURS')),
  CONSTRAINT alert_rules_severity_check CHECK (severity IN ('SEV1','SEV2','SEV3','SEV4')),
  CONSTRAINT alert_rules_threshold_numeric_check CHECK (
    (type IN ('AVAILABILITY','SUCCESS_RATE') AND threshold_numeric BETWEEN 0 AND 100) OR
    (type = 'AVERAGE_LATENCY' AND threshold_numeric > 0) OR
    (type = 'TARGET_FAILURE_COUNT' AND threshold_numeric >= 0 AND threshold_numeric = floor(threshold_numeric)) OR
    (type = 'SERVICE_STATUS' AND threshold_numeric IS NULL)
  ),
  CONSTRAINT alert_rules_threshold_status_check CHECK (
    (type = 'SERVICE_STATUS' AND threshold_status IN ('DEGRADED','UNAVAILABLE')) OR
    (type <> 'SERVICE_STATUS' AND threshold_status IS NULL)
  )
);

CREATE INDEX idx_alert_rules_monitor_id ON alert_rules (monitor_id);
CREATE INDEX idx_alert_rules_active ON alert_rules (active);
