-- Prompt 08 — Módulo Monitors: configuração de monitoramento HTTP/HTTPS de um
-- serviço (Organization → Project → Service → Monitor).
--
-- IMPORTANTE: esta migration cria apenas a CONFIGURAÇÃO declarativa. Nenhum
-- resultado de checagem (latência, status HTTP, falha, série temporal) é
-- persistido aqui — coleta/execução é escopo de um prompt futuro.
--
-- IDs gerados PELA APLICAÇÃO (@PrePersist com UUID.randomUUID(), mesmo padrão
-- de Organization/Membership/Project/Service) — por isso a coluna id NÃO tem
-- DEFAULT: o preenchimento é sempre responsabilidade da entidade JPA. (Mesmo
-- critério documentado em V4/V5/V6; ddl-auto=validate não valida DEFAULT —
-- apenas nome, tipo e nullability de coluna.)
--
-- updated_at é mantido pela aplicação (@PreUpdate) — sem trigger, mesmo
-- critério das migrations V2..V6 (evita duplicidade de responsabilidade).
--
-- name / name_normalized: idênticos ao padrão de services (trim + lowercase
-- com Locale.ROOT calculado PELA APLICAÇÃO) — a chave de unicidade é composta,
-- então o MESMO nome de monitor pode existir em serviços diferentes.
--
-- UNIQUE (service_id, name_normalized): mesmo nome normalizado não existe duas
-- vezes no mesmo serviço — ativo OU inativo (desativar não libera o nome).
-- É a última linha de defesa contra a corrida entre o pré-check do service e
-- o INSERT (DataIntegrityViolationException → 409 na aplicação, mesmo padrão
-- de services_project_name_normalized_unique).
--
-- type: hoje apenas 'HTTP' (VARCHAR(10) + CHECK espelha o enum MonitorType).
-- O CHECK documenta o domínio e impede linha inválida escrita fora da API;
-- nenhum endpoint altera type depois da criação (é invariante do cadastro).
--
-- url: alvo a checar, VALIDADO contra SSRF pela aplicação (SsrfGuard) antes de
-- qualquer persistência. O banco não replica a checagem de ranges/privados —
-- essa é regra de segurança viva (evolução de ranges), não restrição de
-- integridade. Tampouco há coluna de credencial: a URL é anônima e o monitor
-- NÃO guarda header/cookie/senha (prompt 08).
--
-- method: GET ou HEAD (CHECK espelha o enum MonitorMethod). A aplicação
-- valida o valor vindo do payload e o banco é a última linha de defesa.
--
-- interval_seconds / timeout_millis: faixas espelhando a API (30..86400 e
-- 100..30000) + a coerência timeout < interval*1000 (uma checagem nunca pode
-- demorar mais que o próprio intervalo, senão as execuções se sobrepõem).
-- Os DEFAULTs documentam os defaults da API (60s / 5s) para inserts
-- manuteníveis fora da aplicação.
--
-- active: BOOLEAN NOT NULL DEFAULT true — todo monitor nasce ativo; a
-- desativação é soft-delete via rota .../activation (não existe DELETE).
--
-- Índice EXTRA em service_id: NÃO criado — o índice único já tem service_id
-- como coluna de PREFIXO e cobre todas as leituras por serviço (lookup do
-- detalhe findByServiceIdAndId, listagem com filtros e os dois pré-checks de
-- unicidade são igualmente por serviço). Mesmo critério do V6 em project_id.
--
-- ON DELETE RESTRICT: excluir um serviço com monitores é recusado pelo banco
-- (mesmo racional de V6: a linha de monitores é HISTÓRICO da configuração).
CREATE TABLE monitors (
  id UUID PRIMARY KEY,
  service_id UUID NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
  name VARCHAR(120) NOT NULL,
  name_normalized VARCHAR(120) NOT NULL,
  type VARCHAR(10) NOT NULL DEFAULT 'HTTP',
  url VARCHAR(2048) NOT NULL,
  method VARCHAR(10) NOT NULL DEFAULT 'GET',
  interval_seconds INTEGER NOT NULL DEFAULT 60,
  timeout_millis INTEGER NOT NULL DEFAULT 5000,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT monitors_service_name_normalized_unique UNIQUE (service_id, name_normalized),
  CONSTRAINT monitors_name_not_blank CHECK (trim(name) <> ''),
  CONSTRAINT monitors_type_check CHECK (type IN ('HTTP')),
  CONSTRAINT monitors_method_check CHECK (method IN ('GET', 'HEAD')),
  CONSTRAINT monitors_interval_seconds_check CHECK (interval_seconds BETWEEN 30 AND 86400),
  CONSTRAINT monitors_timeout_millis_check CHECK (timeout_millis BETWEEN 100 AND 30000),
  CONSTRAINT monitors_timeout_below_interval_check CHECK (timeout_millis < interval_seconds * 1000)
);
