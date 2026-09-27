-- Prompt 07 — Módulo Services: serviços de um projeto (Organization → Project → Service).
--
-- IDs gerados PELA APLICAÇÃO (@PrePersist com UUID.randomUUID(), mesmo padrão
-- de Organization/Membership/Project) — por isso a coluna id NÃO tem DEFAULT:
-- o preenchimento é sempre responsabilidade da entidade JPA. (Mesmo critério
-- documentado em V4/V5; ddl-auto=validate não valida DEFAULT — apenas nome,
-- tipo e nullability de coluna.)
--
-- updated_at é mantido pela aplicação (@PreUpdate) — sem trigger, mesmo
-- critério das migrations V2/V3/V4/V5 (evita duplicidade de responsabilidade).
--
-- name: CHECK espelha a validação da API (nome vazio ou só espaços não existe
-- nem no banco nem no service), mesmo racional do projects_name_not_blank.
--
-- name_normalized: cópia normalizada de name calculada PELA APLICAÇÃO
-- (trim + lowercase com Locale.ROOT) e persistida — é a chave de unicidade e o
-- alvo da busca por nome (case-insensitive sem funções de coluna na query).
--
-- UNIQUE (project_id, name_normalized): mesmo nome normalizado não pode
-- existir DUAS VEZES no mesmo projeto — ativo OU inativo (desativar não libera
-- o nome). Projetos diferentes (e portanto organizações diferentes) podem
-- repetir o nome. É a última linha de defesa contra a corrida entre o
-- pré-check do service e o INSERT (DataIntegrityViolationException → 409 na
-- aplicação, mesmo padrão de projects_org_name_normalized_unique).
--
-- ON DELETE RESTRICT: excluir um projeto com serviços é recusado pelo banco.
-- Diferente de organizations/projects (ON DELETE CASCADE), aqui a linha de
-- serviços é HISTÓRICO do projeto — apagá-la em cascata destruiria o registro
-- de "o que existiu". Na prática a API nem tem DELETE de projeto (rota devolve
-- 405), então RESTRICT protege apenas um delete direto no banco.
--
-- active BOOLEAN NOT NULL DEFAULT true: todo serviço nasce ativo (catálogo);
-- a desativação é soft-delete via rota .../activation (não existe DELETE).
--
-- status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' + CHECK: estado OPERACIONAL,
-- eixo INDEPENDENTE de active. DEFAULT 'UNKNOWN' documenta a semântica da
-- coluna (nada foi observado ainda) para inserts manuteníveis fora da API;
-- a aplicação SEMPRE escreve o valor (nenhum endpoint público altera status
-- neste prompt). 'UNKNOWN' ≠ "saudável": é ausência de observação — não há
-- falsa observabilidade de que tudo esteja operacional.
--
-- active vs status: "está no catálogo?" × "como está operando?". Desligar um
-- serviço não muda o status e (futuramente) um serviço indisponível pode
-- continuar ativo no catálogo.
--
-- Índice EXTRA em project_id: NÃO criado — o índice único já tem project_id
-- como coluna de PREFIXO e cobre todas as leituras por projeto (lookup do
-- detalhe findByProjectIdAndId, listagem com filtros e os dois pré-checks de
-- unicidade são igualmente por projeto). Mesmo critério do V5 em
-- organization_id; a diferença é que em memberships o unique é
-- (user_id, organization_id), por isso precisa de índice dedicado.
CREATE TABLE services (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects (id) ON DELETE RESTRICT,
  name VARCHAR(120) NOT NULL,
  name_normalized VARCHAR(120) NOT NULL,
  description VARCHAR(1000),
  active BOOLEAN NOT NULL DEFAULT true,
  status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT services_project_name_normalized_unique UNIQUE (project_id, name_normalized),
  CONSTRAINT services_name_not_blank CHECK (trim(name) <> ''),
  CONSTRAINT services_status_check CHECK (status IN ('OPERATIONAL', 'DEGRADED', 'UNAVAILABLE', 'UNKNOWN'))
);
