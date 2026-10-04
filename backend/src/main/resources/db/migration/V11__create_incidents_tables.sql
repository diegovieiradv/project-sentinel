-- Prompt 14 — Incidents: o registro manual de um incidente de UM serviço +
-- a timeline append-only dos eventos do seu ciclo de vida.
--
-- IMPORTANTE: esta migration é ADITIVA e a ÚNICA do prompt. As V1..V10 não são
-- alteradas (o Flyway valida o checksum delas em todo startup). NÃO existe
-- tabela de notificação, ack, silêncio, escalonamento, atribuição, on-call,
-- correlação ou postmortem aqui — a fronteira é travada por
-- MonitoringEngineBoundaryTest.
--
-- NENHUMA automação Alert → Incident: não há trigger, listener, scheduler ou
-- coluna de "alerta dono do incidente". O vínculo é INVERTIDO e vive em
-- alerts.incident_id (abaixo): um incidente é criado POR UM HUMANO via API e,
-- se quiser, é associado a um ou mais alertas depois. O Alert Engine não
-- conhece este arquivo.
--
-- IDs gerados PELA APLICAÇÃO (@PrePersist com UUID.randomUUID(), mesmo padrão
-- de Organization/Project/Service/Monitor/AlertRule/Alert) — por isso as
-- colunas id NÃO têm DEFAULT.
--
-- updated_at é mantido pela aplicação (@PreUpdate) — sem trigger, mesmo
-- critério das demais migrations (evita duplicidade de responsabilidade
-- entre SQL e JPA). incident_events NÃO tem updated_at: o log é append-only.

CREATE TABLE incidents (
  id UUID PRIMARY KEY,
  service_id UUID NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(4000),
  severity VARCHAR(10) NOT NULL,
  status VARCHAR(15) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ,
  created_by_user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL,
  CONSTRAINT incidents_title_not_blank CHECK (trim(title) <> ''),
  CONSTRAINT incidents_severity_check CHECK (severity IN ('SEV1','SEV2','SEV3','SEV4')),
  CONSTRAINT incidents_status_check CHECK (
    status IN ('OPEN','INVESTIGATING','IDENTIFIED','RESOLVED')
  ),
  -- Consistência do ciclo: resolved_at existe EXATAMENTE quando o incidente
  -- está RESOLVED (a transição é amarrada pela aplicação — máquina de
  -- estados centralizada em IncidentStatus — e este CHECK vigia a "metade
  -- escrita" da linha).
  CONSTRAINT incidents_lifecycle_check CHECK (
    (status <> 'RESOLVED' AND resolved_at IS NULL) OR
    (status = 'RESOLVED' AND resolved_at IS NOT NULL)
  )
);

-- Listagem paginada do serviço (ordem fixa da API: started_at DESC, id DESC)
-- e os dois filtros tipados aceitos pela API (status/severity).
CREATE INDEX idx_incidents_service_started_desc ON incidents (service_id, started_at DESC);
CREATE INDEX idx_incidents_service_status ON incidents (service_id, status);
CREATE INDEX idx_incidents_service_severity ON incidents (service_id, severity);

-- Timeline append-only de UM incidente: ORDER BY created_at, id ASC (o índice
-- cobre exatamente a ordenação do GET .../incidents/{incidentId}/events).
CREATE TABLE incident_events (
  id UUID PRIMARY KEY,
  incident_id UUID NOT NULL REFERENCES incidents (id) ON DELETE RESTRICT,
  type VARCHAR(30) NOT NULL,
  message VARCHAR(1000) NOT NULL,
  actor_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT incident_events_type_check CHECK (
    type IN ('CREATED','STATUS_CHANGED','SEVERITY_CHANGED','TITLE_CHANGED',
             'DESCRIPTION_CHANGED','ALERT_LINKED','ALERT_UNLINKED')
  ),
  CONSTRAINT incident_events_message_not_blank CHECK (trim(message) <> '')
);

CREATE INDEX idx_incident_events_incident_created
  ON incident_events (incident_id, created_at, id);

-- --- Associação Incident ↔ Alert (cardinalidade N Alert : 1 Incident) -------
--
-- A FK mora no LADO DO ALERTA e não no do incidente: um incidente pode reunir
-- ZERO A N alertas, mas um alerta pertence a NO MÁXIMO UM incidente. ON DELETE
-- SET NULL documenta que, se um incidente sumir (só possível por SQL direto —
-- a API não tem DELETE de incidente), o alerta HISTÓRICO continua existindo:
-- a remoção de vínculo nunca destrói o alerta.
--
-- A unicidade "UM alerta ≤ UM incidente" é INERENTE à coluna única por linha
-- de alerta + ao UPDATE CONDICIONAL (WHERE incident_id IS NULL) do
-- AlertRepository.linkIncident — nenhum índice ÚNICO é necessário para ela, e
-- um UNIQUE (incident_id) aqui seria o INVERSO do contrato: proibiria DOIS
-- alertas distintos apontando para o MESMO incidente (o PUT .../alerts/{id}
-- legítimo do segundo alerta cairia em erro de integridade). Dois incidentes
-- disputando o MESMO alerta continuam resolvendo na escrita condicional:
-- {200, 409}, nunca dois vínculos.
--
-- O índice abaixo é SIMPLES (não-único) e existe só para a listagem
-- GET .../incidents/{incidentId}/alerts (searchByIncidentId).
--
-- link/unlink NUNCA alteram nenhum outro campo do alerta (a escrita é um
-- UPDATE condicional em SQL, sem tocar em message/severity/estados/timestamps).
ALTER TABLE alerts ADD COLUMN incident_id UUID REFERENCES incidents (id) ON DELETE SET NULL;
CREATE INDEX idx_alerts_incident ON alerts (incident_id);
