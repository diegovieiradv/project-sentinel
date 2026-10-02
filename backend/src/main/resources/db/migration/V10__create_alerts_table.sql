-- Prompt 13 — Alert Engine: alertas (RESULTADO avaliado) + o eixo temporal do
-- estado do serviço de que a regra SERVICE_STATUS precisa.
--
-- IMPORTANTE: esta migration é ADITIVA e a ÚNICA do prompt. As V1..V9 não são
-- alteradas (o Flyway valida o checksum delas em todo startup). NÃO existe
-- tabela de incidente, notificação, ack, silêncio ou escalonamento aqui —
-- esses conceitos pertencem a prompts futuros e a fronteira é travada por
-- MonitoringEngineBoundaryTest.
--
-- IDs gerados PELA APLICAÇÃO (@PrePersist com UUID.randomUUID(), mesmo padrão
-- de Organization/Project/Service/Monitor/AlertRule) — por isso a coluna id
-- NÃO tem DEFAULT.
--
-- SNAPSHOT da configuração no momento do disparo: severity, rule_type,
-- rule_operator, rule_threshold_numeric, rule_threshold_status e rule_window
-- são copiados da regra QUANDO o alerta nasce. O alerta é HISTÓRICO: editar a
-- regra depois (PATCH) nunca reescreve o que já disparou, e a UI consegue
-- mostrar a condição que valia na época sem JOIN retroativo. NÃO existe
-- rule_severity separado de severity — a severidade DO ALERTA É o snapshot
-- (correção da revisão: um único eixo, sem redundância).
--
-- status: VARCHAR(10) + CHECK (FIRING, RESOLVED). A máquina de estados é:
--   FIRING → RESOLVED (nunca o contrário; reabrir é UM NOVO alerta).
--
-- resolution_reason: VARCHAR(20) + CHECK anulável (AUTO, RULE_DEACTIVATED,
--   PARENT_INACTIVE). É o mecanismo que permite resolver SEM sobrescrever a
--   mensagem original do disparo (correção da revisão): a mensagem continua
--   sendo a do FIRING em QUALQUER desfecho, e o motivo da resolução mora
--   aqui. `AUTO` = a condição voltou ao normal; `RULE_DEACTIVATED` = a regra
--   foi desativada; `PARENT_INACTIVE` = serviço ou projeto foi desativado.
--
-- Consistência dos campos de ciclo de vida amarrada por CHECK:
--   FIRING    → resolved_at NULL E resolution_reason NULL;
--   RESOLVED  → resolved_at NOT NULL E resolution_reason NOT NULL.
-- (Vigiar só a "metade escrita" aqui; quem garante a transição é a
--  AlertLifecycleService, sob o índice parcial abaixo.)
--
-- trigger_value: NUMERIC(19,4) anulável — valor que a condição via no momento
--   do disparo (percentual, ms ou contagem). Pode ser NULL quando o tipo não
--   produz número (SERVICE_STATUS) ou em leituras antigas.
--
-- Índices:
--   - ÍNDICE PARCIAL ÚNICO (alert_rule_id) WHERE status = 'FIRING': existe
--     EXATAMENTE UM alerta aberto por regra — é a garantia de deduplicação
--     sob concorrência (a aplicação faz o pré-check; a corrida entre dois
--     evaluators é absorvida aqui, nunca virando dois alertas);
--   - (service_id, status): listagem paginada por serviço filtrando status;
--   - (alert_rule_id, status): lookup do alerta aberto de uma regra;
--   - (started_at DESC): ordenação "mais recente primeiro" da listagem.
CREATE TABLE alerts (
  id UUID PRIMARY KEY,
  service_id UUID NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
  alert_rule_id UUID NOT NULL REFERENCES alert_rules (id) ON DELETE RESTRICT,
  monitor_id UUID REFERENCES monitors (id) ON DELETE RESTRICT,
  status VARCHAR(10) NOT NULL,
  severity VARCHAR(10) NOT NULL,
  resolution_reason VARCHAR(20),
  started_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ,
  last_evaluated_at TIMESTAMPTZ NOT NULL,
  trigger_value NUMERIC(19,4),
  message VARCHAR(500) NOT NULL,
  rule_type VARCHAR(30) NOT NULL,
  rule_operator VARCHAR(30) NOT NULL,
  rule_threshold_numeric NUMERIC(19,4),
  rule_threshold_status VARCHAR(20),
  rule_window VARCHAR(30) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT alerts_status_check CHECK (status IN ('FIRING','RESOLVED')),
  CONSTRAINT alerts_severity_check CHECK (severity IN ('SEV1','SEV2','SEV3','SEV4')),
  CONSTRAINT alerts_resolution_reason_check CHECK (
    resolution_reason IS NULL OR resolution_reason IN ('AUTO','RULE_DEACTIVATED','PARENT_INACTIVE')
  ),
  CONSTRAINT alerts_lifecycle_check CHECK (
    (status = 'FIRING' AND resolved_at IS NULL AND resolution_reason IS NULL) OR
    (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_reason IS NOT NULL)
  ),
  CONSTRAINT alerts_message_not_blank CHECK (trim(message) <> ''),
  CONSTRAINT alerts_rule_type_check CHECK (
    rule_type IN ('AVAILABILITY','SUCCESS_RATE','AVERAGE_LATENCY','TARGET_FAILURE_COUNT','SERVICE_STATUS')
  ),
  CONSTRAINT alerts_rule_operator_check CHECK (
    rule_operator IN ('LESS_THAN','LESS_THAN_OR_EQUAL','GREATER_THAN','GREATER_THAN_OR_EQUAL','EQUAL')
  ),
  CONSTRAINT alerts_rule_window_check CHECK (
    rule_window IN ('FIVE_MINUTES','FIFTEEN_MINUTES','ONE_HOUR','SIX_HOURS','TWENTY_FOUR_HOURS')
  ),
  CONSTRAINT alerts_rule_threshold_numeric_check CHECK (
    rule_type = 'SERVICE_STATUS' AND rule_threshold_numeric IS NULL OR
    rule_type <> 'SERVICE_STATUS'
  ),
  CONSTRAINT alerts_rule_threshold_status_check CHECK (
    rule_type = 'SERVICE_STATUS' OR rule_threshold_status IS NULL
  )
);

-- Deduplicação: um (e só um) alerta ABERTO por regra. Índice parcial — as
-- linhas RESOLVED nunca colidem entre si.
CREATE UNIQUE INDEX uq_alerts_open_rule ON alerts (alert_rule_id) WHERE status = 'FIRING';
CREATE INDEX idx_alerts_service_status ON alerts (service_id, status);
CREATE INDEX idx_alerts_rule_status ON alerts (alert_rule_id, status);
CREATE INDEX idx_alerts_started_at_desc ON alerts (started_at DESC);

-- Eixo temporal do estado do serviço: QUANDO o status atual começou a valer.
-- Sem isto, a regra SERVICE_STATUS com janela de 5 minutos só poderia inferir
-- "está UNAVAILABLE" sem nunca saber há QUANTO tempo — o falso "UNAVAILABLE
-- há 5 minutos" é justamente o que este prompt precisa eliminar.
--
-- Escritor ÚNICO: OperationalStateEvaluator (já sob lock pessimista, e apenas
-- quando o status muda). Coluna anulável de propósito: "sem transição
-- registrada" é diferente de "mudou agora" — e uma Service recém-criada não
-- tem transição alguma. Backfill das linhas existentes usa updated_at (melhor
-- aproximado disponível; a coluna nova não pode ser NOT NULL antes disso).
ALTER TABLE services ADD COLUMN status_changed_at TIMESTAMPTZ;
UPDATE services SET status_changed_at = updated_at;
