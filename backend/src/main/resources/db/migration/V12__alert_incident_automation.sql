-- Prompt 15 — Alert → Incident Automation: a FLAG opt-in por regra, a ORIGEM
-- do incidente e a trava de correlação "UM incidente automático aberto por
-- serviço".
--
-- IMPORTANTE: esta migration é ADITIVA e a ÚNICA do prompt. As V1..V11 não são
-- alteradas (o Flyway valida o checksum delas em todo startup) e NÃO existe
-- V13. As mudanças são:
--
--   1. alert_rules.auto_create_incident — CONFIGURAÇÃO da regra (default
--      FALSE: o comportamento do Prompt 12/13 não muda para quem não pediu);
--   2. incidents.source — quem nasceu o incidente (MANUAL | ALERT_AUTOMATION);
--      as linhas existentes recebem 'MANUAL' pelo DEFAULT da coluna;
--   3. incidents.created_by_user_id perde o NOT NULL — a automação não tem
--      usuário por trás (created_by_user_id = NULL e source =
--      'ALERT_AUTOMATION'); o CAMPO continua existindo e a API manual continua
--      exigindo ator no service (a validação é da aplicação, não da coluna);
--   4. uq_incidents_auto_open_per_service — índice parcial Único: no máximo UM
--      incidente AUTOMÁTICO ainda aberto por serviço (linha de defesa sob
--      corrida entre dois ciclos; o pré-check do service é o caminho comum).
--      Incidentes MANUAIS ficam fora do predicado — nunca são afetados;
--   5. idx_alerts_auto_create_firing — cobre a reconciliação (alertas FIRING
--      sem incidente de um serviço), que roda ao fim de cada avaliação.
--
-- NÃO existe aqui: coluna de resultado em alert_rules, mudança no vínculo
-- alerts.incident_id (continua invertido e condicional), notificação, ack,
-- escalonamento, atribuição, on-call ou reabertura. O Alert Engine NÃO
-- referencia este arquivo: a decisão mora em
-- com.sentinel.project.alert.automation (fronteira travada por
-- MonitoringEngineBoundaryTest.alertingPackageHasNoIncidentReferences).

-- 1. flag de configuração da regra (opt-in, default OFF)
ALTER TABLE alert_rules
  ADD COLUMN auto_create_incident BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. origem do incidente — vocabulário fechado, default MANUAL para as
--    linhas existentes (todo incidente pré-V12 nasceu de um humano)
ALTER TABLE incidents
  ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE incidents
  ADD CONSTRAINT incidents_source_check CHECK (
    source IN ('MANUAL','ALERT_AUTOMATION')
  );

-- 3. ator anulável APENAS na origem automatizada (a FK permanece: um id
--    preenchido continua obrigado a apontar um usuário existente)
ALTER TABLE incidents ALTER COLUMN created_by_user_id DROP NOT NULL;

-- 4. trava de correlação: no máximo UM incidente automático ABERTO por
--    serviço (o predicado source = 'ALERT_AUTOMATION' mantém os manuais
--    intocados; status <> 'RESOLVED' deixa um novo disparo criar o próximo
--    depois que o anterior for encerrado)
CREATE UNIQUE INDEX uq_incidents_auto_open_per_service
  ON incidents (service_id)
  WHERE source = 'ALERT_AUTOMATION' AND status <> 'RESOLVED';

-- 5. leitura da reconciliação (AlertRepository.findOpenEligibleForAutomation)
CREATE INDEX idx_alerts_auto_create_firing
  ON alerts (service_id, status)
  WHERE status = 'FIRING';
