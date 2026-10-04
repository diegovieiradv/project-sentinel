# Alert Rules — Sentinel

> **Prompt 12 de 18** — configuração, thresholds e gerenciamento das condições de alerta: quinto nível da hierarquia Organization → Project → Service → AlertRule, com escopo opcional de Monitor. Este documento descreve **o que está construído**: tabela `alert_rules` (migration `V9` + flag `auto_create_incident` da `V12`, ver [alert-incident-automation.md](alert-incident-automation.md)), pacote `com.sentinel.project.alert` (entidade, repositório, matriz de validação, service, DTOs e controller), a semântica de `PATCH`, paginação/filtros/ordenação, unicidade de nome **por serviço** em duas camadas, ativação/desativação idempotente e a matriz de autorização sobre o contexto de tenant já resolvido. O contexto de tenant (membership, papéis e `404` anti-enumeração) é o do [organizations.md](organizations.md), os projetos são os do [projects.md](projects.md), os serviços são os do [services.md](services.md), os monitores são os do [monitors.md](monitors.md) e a base de autenticação (Bearer + CSRF) é a do [identity.md](identity.md).

**Uma AlertRule representa uma condição configurada pelo usuário que será avaliada futuramente pelo Alert Engine (Prompt 13).** Neste prompt nada é avaliado, disparado ou notificado — só se cria, consulta, edita e ativa/desativa **configuração**.

## 1. Escopo implementado

| Item | Estado |
| --- | --- |
| Migration `V9__create_alert_rules_table.sql` (tabela `alert_rules`) | Construído |
| Entidade `AlertRule` + `AlertRuleRepository` (busca combinada, unicidade, ownership de monitor) | Construído |
| `AlertRuleValidationMatrix` (matriz type × operator, faixas de threshold, exclusividade numérico × status) | Construído |
| `POST .../services/{serviceId}/alert-rules` (criação, `ADMIN`/`OPERATOR`) | Construído |
| `GET .../alert-rules` (página com filtros `active`/`type`/`severity`/`monitorId`/`name` e ordenação por allowlist) | Construído |
| `GET .../alert-rules/{ruleId}` (detalhe + `role` do usuário) | Construído |
| `PATCH .../alert-rules/{ruleId}` (atualização parcial com revalidação da condição efetiva) | Construído |
| `PATCH .../alert-rules/{ruleId}/activation` (ativação/desativação idempotente e fail-safe) | Construído |
| Escopo **Service-level** (`monitorId = null`) × **Monitor-level** (`monitorId` presente, do MESMO serviço) | Construído |
| Unicidade de nome **por serviço** em **duas camadas** (pré-check → `409`, `UNIQUE` → `409`) | Construído |
| Pais inativos: leitura `200` × mutação `409 PROJECT_INACTIVE`/`409 SERVICE_INACTIVE` | Construído |
| Isolamento cross-tenant → `404` genérico anti-enumeração em **quatro níveis** (projeto, serviço, regra e monitor) | Construído |
| RBAC: leitura para qualquer papel, mutação para `ADMIN`/`OPERATOR` | Construído |
| Validações de entrada sem `500` (matriz, thresholds, nome, paginação, `sort`, enums de query, ids malformados, corpo malformado) | Construído |
| Frontend de gerenciamento (`AlertRulesPanel` no detalhe do serviço + helpers em `src/lib/alert-rules.ts`) | Construído |
| `DELETE`/`PUT` da rota → `405` (remoção não existe neste prompt) | Construído |
| **Avaliação** das regras, disparo (`firing`), entidade `Alert`, scheduler de regras, notificações, incidentes e supressões | **Fora do escopo** (Prompt 13+) |
| Flag `autoCreateIncident` (opt-in de automação Alert → Incident) | **Fora do escopo do Prompt 12** — **construída no Prompt 15** (ver [alert-incident-automation.md](alert-incident-automation.md)) |

## 2. Conceito e ownership

- **Ownership**: `Organization → Project → Service → AlertRule`; opcionalmente `AlertRule → Monitor` (quando monitor-scoped). A regra pertence a **exatamente um serviço** e pode apontar para **zero ou um monitor do mesmo serviço**.
- **Service-level** (`monitorId = null`): a condição se refere a métrica agregada do **Serviço** (ex.: disponibilidade do serviço nas últimas 24h).
- **Monitor-level** (`monitorId` presente): a condição se refere a métrica de **um monitor específico** (ex.: latência média daquele endpoint). O monitor precisa **existir no serviço** (`404` se de outro serviço/tenant); **não precisa estar ativo** — decidir elegibilidade de monitor inativo é papel do futuro Alert Engine.
- **Ativação**: `active: boolean`, `default true`. Regra inativa continua **persistida e consultável** (filtro `active=false` na listagem); inatividade é o soft-delete deste prompt (não existe `DELETE`).

## 3. Modelo de dados

### 3.1 `AlertRule` (tabela `alert_rules`)

| Coluna | Tipo | Observação |
| --- | --- | --- |
| `id` | `UUID` PK | Gerado **pela aplicação** (`@PrePersist` + `UUID.randomUUID()`, mesmo critério de `V4`–`V7`) — a migration não declara `DEFAULT` |
| `service_id` | `UUID NOT NULL` → `services(id)` | `ON DELETE RESTRICT` — excluir um serviço com regras é recusado pelo banco (a linha é **histórico** da configuração) |
| `monitor_id` | `UUID NULL` → `monitors(id)` | `ON DELETE RESTRICT`; `NULL` = Service-level; com valor = Monitor-level do **mesmo** serviço (garantido pela aplicação, não por constraint cross-tabela) |
| `name` | `VARCHAR(120) NOT NULL` | `CHECK alert_rules_name_not_blank` (`trim(name) <> ''`) espelha a validação da API |
| `name_normalized` | `VARCHAR(120) NOT NULL` | cópia normalizada calculada **pela aplicação** (`trim` + `lowercase` com `Locale.ROOT`) |
| `type` | `VARCHAR(30) NOT NULL` | `CHECK` espelha o enum `AlertRuleType` (5 valores) |
| `operator` | `VARCHAR(30) NOT NULL` | `CHECK` espelha o enum `AlertOperator` (5 valores); a **matriz** type × operator é validada pela aplicação |
| `threshold_numeric` | `NUMERIC(19,4) NULL` | usado pelos 4 tipos numéricos; faixas ricas validadas pela aplicação + `CHECK` condicional como última barreira |
| `threshold_status` | `VARCHAR(20) NULL` | usado **apenas** por `SERVICE_STATUS` (`DEGRADED`/`UNAVAILABLE`) |
| `"window"` | `VARCHAR(30) NOT NULL` | `CHECK` espelha o enum `EvaluationWindow` (5 valores); **palavra reservada do PostgreSQL** → citada com aspas no DDL (nome lógico/JSON continua `window`) |
| `severity` | `VARCHAR(10) NOT NULL` | `CHECK` espelha o enum `AlertSeverity` (`SEV1`..`SEV4`) |
| `active` | `BOOLEAN NOT NULL DEFAULT true` | toda regra nasce ativa; a desativação é soft-delete via `.../activation` |
| `auto_create_incident` | `BOOLEAN NOT NULL DEFAULT FALSE` | **V12, Prompt 15** — opt-in de automação: com `true`, o nascimento de um alerta desta regra cria incidente `ALERT_AUTOMATION`; `false` = comportamento exato dos Prompts 12/13. Configuração **editável** (create/PATCH), efeito **prospectivo** (não mexe em alerta/incidente existente) |
| `created_at` / `updated_at` | `TIMESTAMPTZ NOT NULL` | `updated_at` mantido por `@PreUpdate` (sem trigger) — ao contrário do append-only do `V8`, esta é configuração **editável** |

Constraints e índices:

- **`UNIQUE (service_id, name_normalized)`** (`alert_rules_service_name_normalized_unique`): o mesmo nome normalizado não existe **duas vezes no mesmo serviço** — ativo **ou** inativo (desativar não libera o nome). Serviços diferentes (e portanto projetos/organizações diferentes) repetem o nome livremente.
- **Checks de threshold mutuamente exclusivos**: `threshold_numeric` só coexiste com os tipos numéricos (com as faixas de cada um) e `threshold_status` só com `SERVICE_STATUS` — nunca os dois juntos.
- **Índices**: `idx_alert_rules_monitor_id` (lookup por monitor), `idx_alert_rules_active` (elegibilidade futura do Engine) e `idx_alert_rules_service_id` implícito pelo prefixo do `UNIQUE (service_id, ...)`.
- **Nenhuma coluna de resultado**: última avaliação, disparo, estado, incidente ou notificação **não são persistidos** por este prompt (§12). A coluna `auto_create_incident` (§3.1) é **configuração** (Prompt 15), não resultado.

### 3.2 Normalização do nome

`AlertRule.normalizeName(name)` = `trim` + `lowercase(Locale.ROOT)` — a **única** fonte da regra (idêntica ao critério de `Service`/`Monitor`):

- `  Padded   ` → persiste `Padded`, unicidade decide sobre `padded`;
- `High Latency` e `high latency` colidem → `409`;
- **espaços internos são preservados**; `nameNormalized` **nunca** sai no payload.

## 4. Domínio da regra

### 4.1 RuleTypes e thresholds

| Tipo | Threshold | Faixa validada (aplicação) | Operadores permitidos |
| --- | --- | --- | --- |
| `AVAILABILITY` | `thresholdNumeric` (percentual) | `0..100`, escala ≤ 2 casas | `<`, `≤` |
| `SUCCESS_RATE` | `thresholdNumeric` (percentual) | `0..100`, escala ≤ 2 casas | `<`, `≤` |
| `AVERAGE_LATENCY` | `thresholdNumeric` (ms) | `> 0`, escala ≤ 4 casas (cabe no `NUMERIC(19,4)`) | `>`, `≥` |
| `TARGET_FAILURE_COUNT` | `thresholdNumeric` (inteiro) | `≥ 0`, escala 0 (integer) | `>`, `≥`, `=` |
| `SERVICE_STATUS` | `thresholdStatus` (`DEGRADED`/`UNAVAILABLE`) | enum fechado; `thresholdNumeric` deve ser `NULL` | `=` |

- `thresholdNumeric` e `thresholdStatus` são **mutuamente exclusivos** (nunca coexistem) — a matriz rejeita qualquer combinação híbrida com `400 VALIDATION_ERROR`.
- Alertar contra `OPERATIONAL`/`UNKNOWN` não faz sentido → só `DEGRADED`/`UNAVAILABLE` são aceitos como status.
- **Matriz type × operator** (`AlertRuleValidationMatrix.ALLOWED_OPERATORS`): a tabela acima é a autoridade — qualquer operador fora da lista do tipo vira `400 operator: {OP} not allowed for {TYPE}`.

### 4.2 Evaluation windows

`FIVE_MINUTES` (300s) · `FIFTEEN_MINUTES` (900s) · `ONE_HOUR` (3600s) · `SIX_HOURS` (21600s) · `TWENTY_FOUR_HOURS` (86400s) — a janela de observação que o futuro Alert Engine usará; a validação de existência é o `CHECK` + o desserializador (valor fora do enum → `400`).

### 4.3 Severity

`SEV1` (Critical) · `SEV2` (High) · `SEV3` (Moderate) · `SEV4` (Low) — rótulo semântico fixo (não é prioridade editável); o contrato JSON usa o código (`SEV1`), o frontend sempre exibe **código + palavra** junto.

## 5. Autorização e ordem das checagens

Ordem do service (contrato público):

1. **role** — mutação exige `ADMIN`/`OPERATOR` (`403`); leitura é livre para qualquer papel;
2. **projeto no tenant** (`404`) → **serviço no projeto** (`404`), nessa ordem — cadeia Organization → Project → Service → AlertRule sem consulta global;
3. **pais ATIVOS apenas em mutação** (`409 PROJECT_INACTIVE` **antes de** `409 SERVICE_INACTIVE`) — leitura sempre permitida e **desativação é fail-safe** (não exige pais ativos);
4. **regra** (`404` genérico — inexistente, de outro serviço ou de outra organização responde igual, anti-enumeração);
5. **validação de domínio** (`400 VALIDATION_ERROR`) — matriz, thresholds e exclusividade; no `PATCH` os campos **ausentes** são resolvidos para os valores **efetivos** antes de validar (create e update passam pela mesma regra, sem bypass);
6. **unicidade** (`409 ALERT_RULE_NAME_DUPLICATE`);
7. **ownership de monitor** quando `monitorId` está no payload (`404` — deve pertencer ao MESMO serviço).

Salvo no create: o `@Valid` do `POST` roda no **controller**, antes do service (name em branco no create → `400` antes de qualquer lookup).

Papel é lido do `TenantContext` **por requisição** (nunca de token) — mesmo arranjo dos Prompts 06–08.

## 6. Endpoints

Base: `/api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/alert-rules`

### 6.1 Matriz de acesso

| Operação | ADMIN | OPERATOR | VIEWER | Anônimo |
| --- | --- | --- | --- | --- |
| `GET` (lista/detalhe) | ✅ | ✅ | ✅ | `401` |
| `POST` / `PATCH` / `PATCH .../activation` | ✅ | ✅ | `403` | `401` |

### 6.2 `POST .../alert-rules` → `201`

Body: `{name, type, operator, thresholdNumeric?, thresholdStatus?, window, severity, monitorId?, autoCreateIncident?}` — o DTO **não tem campos de escopo** (`serviceId`, `active`, timestamps são servidor-owned; enviado no JSON são ignorados). Regra nasce `active=true`. `autoCreateIncident` (Prompt 15) é opcional: ausente/`null` → `false` (comportamento dos Prompts 12/13), `true` liga a automação dos próximos disparos; tipo errado → `400 VALIDATION_ERROR`. A condição é validada pela matriz **antes** de qualquer escrita; o escopo de monitor (quando presente) é provado por último.

### 6.3 `GET .../alert-rules` → `200`

Página no formato Spring `Page` (`{content, totalElements, totalPages, number, size}`), mesmo formato dos Prompts 06–08.

- `page` default `0` (negativo → `400 page: must not be negative`); `size` default `20`, faixa `1..100` (fora → `400 size: must be between 1 and 100`);
- `sort` allowlist `createdAt|name|id` + direção, default `createdAt,asc`, desempate fixo `id asc` (fora da allowlist → `400 sort: must be one of createdAt, name, id`);
- filtros opcionais combináveis: `active`, `type`, `severity`, `monitorId`, `name` (busca case-insensitive por subcadeia);
- `type`/`severity` fora do enum → `400` ("Invalid request parameter"); `monitorId` malformado na **query string** → `400` (mesmo `MethodArgumentTypeMismatchException` dos enums — parâmetro de query tipado como `UUID`); ids malformados no **path** (projeto, serviço, regra) → `404` genérico (política anti-enumeração);
- `monitorId` **presente** → restringe àquele escopo; **ausente** → todos os escopos (Service-level + Monitor-level);
- leitura liberada mesmo com pais inativos e para regras inativas.

### 6.4 `GET .../alert-rules/{ruleId}` → `200` | `404`

Detalhe + `role` do usuário; `ruleId` malformado ou de outro tenant/serviço → `404` genérico.

### 6.5 `PATCH .../alert-rules/{ruleId}` → `200`

Body parcial: `{name?, type?, operator?, thresholdNumeric?, thresholdStatus?, window?, severity?, monitorId?, autoCreateIncident?}`.

- **Campo ausente não altera**; `monitorId: null` **explícito** limpa o escopo (vira Service-level); presente → revalida ownership.
- **`autoCreateIncident` (Prompt 15)**: ausente ou `null` explícito **não altera**; presente liga/desliga a automação dos **próximos** disparos — desligar **nunca** desfaz alerta/incidente já existente (efeito prospectivo, ver [alert-incident-automation.md](alert-incident-automation.md)).
- A condição **efetiva** (novos + persistidos) é revalidada por inteiro pela matriz — mudar um campo invalida a combinação inteira de propósito.
- **Resolução dos thresholds ao mudar de tipo**: `thresholdNumeric` presente → usado; ausente → herdado, **exceto** quando o tipo efetivo virou `SERVICE_STATUS` (aí vira `NULL`); `thresholdStatus` simétrico. Assim `{type: SERVICE_STATUS, thresholdStatus: UNAVAILABLE}` converte uma regra numérica limpando o número automaticamente (e o inverso), sem o cliente mandar `null`s redundantes.
- Unicidade checada **depois** do payload (renomear não pode colidir consigo mesmo) e absorvida pelo `UNIQUE` na corrida.
- **Campos fora do contrato são `200`, nunca `400`**: server-owned (`id`, `serviceId`, `organizationId`, `active`, timestamps) e chaves desconhecidas (`description`, `nameNormalized`, qualquer chave inventada) são **ignorados** — mesma convenção de `MonitorUpdateRequest`/`ServiceUpdateRequest` (`FAIL_ON_UNKNOWN_PROPERTIES` desabilitado pela auto-configuração do Spring Boot). Decisão Única fixada por `AlertRulePatchTest.patchIgnoresServerOwnedFields` (inclui casos server-owned + desconhecidos) e `patchIgnoresServiceIdTransferAttempt`.
- **Presença de thresholds (sem flag)**: `thresholdNumeric`/`thresholdStatus` são campos anuláveis simples — `"thresholdNumeric": null` **colapsa para "ausente"** (herda o valor persistido); **não há limpeza explícita** de threshold via payload. A única forma de zerar um threshold é a **mudança de tipo** descrita acima. `monitorId` é o **único** campo com três estados (ausente / `null` / valor), via flag `monitorIdSet` do setter `@JsonSetter`.

### 6.6 `PATCH .../alert-rules/{ruleId}/activation` → `200`

`{active: boolean}` (`@NotNull` — body ausente/nulo → `400`). **Idempotente**: já no estado pedido → devolve o estado atual **sem escrita** (`updatedAt` não avança). `false → true` exige pais ativos; `true → false` é sempre permitida (inclusive com pais inativos — desligar é a direção segura).

### 6.7 `DELETE`/`PUT` → `405`

Remoção não existe neste prompt — a rota cai no contrato global de `405` (soft-delete é o `.../activation`).

## 7. Regras de domínio

### 7.1 Unicidade de nome em duas camadas (por serviço)

Pré-check no service (`existsByServiceIdAndNameNormalized` → `409` imediato) + corrida absorvida pelo `UNIQUE (service_id, name_normalized)` da `V9`: a `DataIntegrityViolationException` vira o **mesmo** `409 ALERT_RULE_NAME_DUPLICATE`, nunca SQL vazando. Mesmo nome em **serviços diferentes** é livre. Desativar não libera o nome.

### 7.2 Isolamento cross-tenant em QUATRO níveis

Projeto (`findByOrganizationIdAndId`) → serviço (`findByProjectIdAndId`) → regra (`findByServiceIdAndId`) → monitor (`findByServiceIdAndId`) — nunca `findById` avulso; qualquer nível ausente/malformado responde o **mesmo `404` genérico**, sem escrita lateral.

### 7.3 Pais inativos × desativação fail-safe

Leitura (list/get) sempre `200`, mesmo com projeto/serviço inativos. Criação, edição e ativação (`false → true`) exigem os dois pais ativos (`409 PROJECT_INACTIVE` antes de `409 SERVICE_INACTIVE`). **Desativação (`true → false`) nunca exige** pais ativos.

### 7.4 Mass assignment

`id`, `organizationId`, `projectId`, `serviceId`, `active`, `createdAt`, `updatedAt` e `role` **não existem** nos DTOs de create/update — impossível criar regra "em outro serviço" ou forçar `active` por payload; a ativação tem rota própria.

## 8. Contrato de erro

Shape padrão `{code, message, timestamp, path}`:

| Código | HTTP | Quando |
| --- | --- | --- |
| `VALIDATION_ERROR` | `400` | matriz (`operator: X not allowed for Y`), thresholds (`thresholdNumeric: must be between 0 and 100 for AVAILABILITY`, `thresholdStatus: required for SERVICE_STATUS`, `thresholdNumeric: must be null for SERVICE_STATUS`, …), nome (`name: must not be blank`, `name: must be at most 120 characters`), `page`/`size`/`sort`, `@Valid` do create (`campo: defaultMessage`), corpo malformado, enums de query inválidos, `monitorId` malformado na query (UUID inválido) |
| `NOT_FOUND` | `404` | recurso inexistente, de outro tenant/serviço, id malformado **no path**, monitor de outro serviço (mensagem `Resource not found`, anti-enumeração) |
| `FORBIDDEN` | `403` | `VIEWER` em mutação (`Access denied`) |
| `PROJECT_INACTIVE` | `409` | mutação/ativação com projeto inativo |
| `SERVICE_INACTIVE` | `409` | mutação/ativação com serviço inativo |
| `ALERT_RULE_NAME_DUPLICATE` | `409` | nome duplicado no serviço (pré-check **ou** corrida do `UNIQUE`) |
| `METHOD_NOT_ALLOWED` | `405` | `DELETE`/`PUT` na rota |
| — | `401` | sem token (ou refresh inválido) |

Nenhum caminho responde `500` por entrada inválida (ids malformados **no path** → `404`; enums e parâmetros de query tipados malformados — incl. `monitorId` → `400`; `PageRequest` nunca recebe `page` negativo sem checagem prévia).

## 9. CSRF e autenticação

Bearer `Authorization: Bearer <accessToken>` em todas as rotas; CSRF double-submit (`XSRF-TOKEN` + `X-XSRF-TOKEN`) nos métodos não-safe (`POST`/`PATCH`) — mesmos mecanismos do [identity.md](identity.md). Handlers todos `@TenantScoped`: o `TenantInterceptor` valida a membership **antes** do handler e devolve `404` genérico a não-membros.

## 10. Frontend (Prompt 12)

- **Cliente**: seção Alert Rules em `frontend/src/lib/api.ts` — tipos (`AlertRuleType`, `AlertOperator`, `EvaluationWindow`, `AlertSeverity`, `AlertRuleResponse/CreateRequest/UpdateRequest/ActivationRequest/ListParams/Page`, com `autoCreateIncident` nos tipos de regra desde o Prompt 15) e `buildAlertRuleListParams` (ordem de query string estável) e `listAlertRules`/`getAlertRule`/`createAlertRule`/`updateAlertRule`/`activateAlertRule` (PATCHs montam body só com campos definidos).
- **Helpers puros** (`frontend/src/lib/alert-rules.ts`): `ALLOWED_OPERATORS`/`allowedOperatorsFor` (espelho da matriz do backend), rótulos (`TYPE_LABELS`, `OPERATOR_LABELS`, `SEVERITY_LABELS`, `WINDOW_LABELS`, `AUTO_CREATE_INCIDENT_LABEL`/`AUTO_CREATE_INCIDENT_HINT` desde o Prompt 15), `thresholdFieldFor` (kind percent/ms/count/status com unit, options e placeholder), `validateAlertRuleForm`, `buildCreateAlertRuleRequest`/`buildUpdateAlertRuleRequest` (levam a flag `autoCreateIncident` do form — o form nasce `false`), `formValuesFromRule` (devolve a flag persistida), `withTypeChange` (troca de tipo revalida a combinação inteira), `canManageAlertRules` e `alertRulesViewState(status, ruleCount)` → `loading|error|empty|list` — a decisão Única de o que renderizar na listagem (critérios 99–102), consumida pelo painel.
- **Painel** (`frontend/src/components/AlertRulesPanel.tsx`): listagem com filtros (nome com debounce, tipo, severidade, escopo), paginação, criação, edição inline, toggle de ativação, `ThresholdInput` dinâmico por tipo, **checkbox `autoCreateIncident`** nos forms de criação e edição (Prompt 15, gate de `VIEWER` inalterado), escopo exibido como badge (Service-level × Monitor do serviço), estados de vazio/carregando/erro com Retry derivados de `alertRulesViewState` e gate de `VIEWER` (oculta o form). Montado no detalhe expandido do serviço (`ServicesPanel.tsx`) com `key={alert-rules-${service.id}}` — estado nasce limpo por serviço.

## 11. Estrutura de código (backend)

```
com.sentinel.project.alert
├── model/       AlertRule, AlertRuleType, AlertOperator, EvaluationWindow, AlertSeverity
├── repository/  AlertRuleRepository (busca combinada ativa/tipo/severidade/monitor/nome,
│                existsByServiceIdAndNameNormalized, existsBy...AndIdNot, findByServiceIdAndId)
├── validation/  AlertRuleValidationMatrix (função pura estática — sem bean Spring)
├── service/     AlertRuleService (create, list, get, update, activation + RBAC/tenant/pais/unicidade)
└── web/         AlertRuleController (@TenantScoped; @Valid só no POST)
    └── dto/     AlertRuleCreateRequest, AlertRuleUpdateRequest (POJO parcial),
                 AlertRuleActivationRequest, AlertRuleResponse
```

Observações de implementação:

- **Conflito `AlertRule` × estereótipo `@Service` do Spring**: mesmo arranjo do `MonitorService` — a entidade `Service` é importada normalmente e o estereótipo aparece uma vez, com nome completo (`@org.springframework.stereotype.Service`).
- **Busca por nome**: os curingas `%...%` são montados **no service** (`searchPattern`) porque `CONCAT` dentro de `LIKE` faz o Hibernate 7 binarizar o parâmetro (`varchar ~~ bytea` → 500).
- **`AlertRuleUpdateRequest`** é um POJO com flags de presença (`isMonitorIdSet()`), não record com nulls implícitos — "ausente" × "explícito null" precisa ser distinguível para `monitorId`.

## 12. Testes

- Backend: **117 testes de alert rule** (suíte incremental com `MonitoringEngineBoundaryTest`: **128/128**, exit 0):
  - `AlertRuleValidationTest` (9) — contrato de criação/validação ponta a ponta;
  - matriz unitária em `alert/validation` (40): `AlertRuleAvailabilityValidationTest` (10), `AlertRuleLatencyValidationTest` (8), `AlertRuleSuccessRateValidationTest` (8), `AlertRuleServiceStatusValidationTest` (8), `AlertRuleFailureCountValidationTest` (6);
  - `AlertRuleMonitorScopeTest` (6), `AlertRuleRbacTest` (8), `AlertRuleIdorTest` (7), `AlertRuleActivationTest` (8), `AlertRulePatchTest` (13), `AlertRuleUniquenessTest` (6, inclui concorrência), `AlertRuleApiTest` (12, MockMvc);
  - `AlertRuleMigrationTest` (8): V1–V9 existentes, **checksums SHA-256 de V1–V8 inalterados** (baseline hardcoded), FKs/CHECKs/índices da V9, `flyway.validate()` e `flyway_schema_history` com 9 linhas `success`.
- **`MonitoringEngineBoundaryTest` (11)**: inclui os guardas da fronteira P13 — `alertRuleEntityIsMappedToAlertRulesTable`, `alertRulesMigrationIsConfigBoundedAndWithoutFiringColumns`, `noAlertEntityExists`, `noAlertEngineOrEvaluatorExists` (predicados `Engine`/`Scheduler`/`Firing`/`evaluat*` sobre todo o pacote `alert`, com única exceção nomeada `EvaluationWindow`), `alertPackageHasNoScheduledOrAsyncAnnotations` (varredura de fonte: sem `@Scheduled`/`@Async` no pacote alert), `noNotificationEntitiesExist`, `noIncidentEntitiesExist`.
- Frontend: **126 testes** (95 baseline + 31 em `alert-rules.test.ts` — helpers, validação espelhando a matriz, query string, bodies dos 5 endpoints e estado da view), `tsc --noEmit` exit 0, `eslint` exit 0, `next build` exit 0.
- Como os números foram medidos: `grep -c "@Test"` por classe de teste (soma 117) + 11 do boundary; Surefire dos testes de alert rule + boundary = 128; Vitest reporta 126.

### 12.1 Cobertura dos critérios 99–112 (frontend) e limitação de infraestrutura

Cada critério tem **ao menos um teste executável cujo nome cita o número** (busca por `#99`…`#112` em `frontend/src/lib/alert-rules.test.ts`):

| # | Critério | Teste (nome citando o critério) |
|---|----------|--------------------------------|
| 99 | lista real | `#99 lista real: dados prontos + regras da API → estado "list" com a condição formatada` (função pura `alertRulesViewState` + `thresholdSummary`); complementar: `GET path de CINCO níveis com a query da listagem` |
| 100 | empty | `#100 empty: pronto e zero regras → estado "empty" (mensagem própria)` |
| 101 | loading | `#101 loading: idle (antes do 1º fetch) e loading → estado "loading"` |
| 102 | error + Retry | `#102 error: falha de listagem → estado "error" com Retry, nunca confundido com vazio` (inclui leitura do fonte: botão Retry dentro do branch de erro, antes do vazio) |
| 103 | create via form → API | `#103 POST com body APENAS de configuração (nunca ids/active/timestamps)` |
| 104 | edit via form → API | `#104 PATCH manda só o que foi informado (parcial de verdade)` |
| 105 | activation toggle → API | `#105 PATCH .../activation com {active} idempotente` |
| 106 | rule type muda campos | `#106 cada tipo mapeia para o campo e os operadores da matriz do backend` |
| 107 | SERVICE_STATUS sem numeric | `#107 SERVICE_STATUS esconde o input numérico e oferece select de status` |
| 108 | AVAILABILITY percentual | `#108 AVAILABILITY mostra input de percentual com unidade %` |
| 109 | LATENCY em ms | `#109 AVERAGE_LATENCY mostra input em ms` |
| 110 | severity textual | `#110 cada SEV traz a palavra humana junto do código (texto, não só cor)` |
| 111 | Viewer read-only | `#111 VIEWER não pode gerenciar (sem controles mutáveis); ADMIN e OPERATOR podem` |
| 112 | sem dados fake | `#112 AlertRulesPanel só consome a API real (sem arrays/mock de regras)` (leitura do fonte do componente) |

**Limitação registrada (fora do escopo deste prompt)**: a RENDERIZAÇÃO DOM (mount do `AlertRulesPanel` com Testing Library, esqueletos/labels no DOM) exige `jsdom` + `@testing-library/react` — dependências **novas**, que o Prompt 12 não pode instalar. Por isso a decisão de renderização foi extraída para a função pura `alertRulesViewState` (consumida pelo componente), cobrindo 99–102 sem DOM; os demais critérios já eram puros ou cobertos por leitura do fonte. Seguir com renderização DOM é candidato a infraestrutura de testes de frontend num prompt futuro.

## 13. Fronteira com o Prompt 13 (fora do escopo)

**NENHUMA** destas coisas existe neste prompt (travado por `MonitoringEngineBoundaryTest` e revisão):

- avaliação automática das regras (nenhuma classe `*Engine*`, `*Evaluator*`, `*Scheduler*`, nenhuma classe com token `evaluat*`/`scheduler`/`firing` no pacote `alert`, nenhum `@Scheduled`/`@Async` no módulo `alert` — tudo varrido por `MonitoringEngineBoundaryTest`);
- entidade **`Alert`**, disparo (`firing`), supressão/silenciamento/escalation/acknowledgment;
- scheduler de Alert Rules ou qualquer leitura de métrica para decidir condição;
- notificações (e-mail, Slack, webhook) e templates;
- entidades `Incident*`/`IncidentEvent*` ou criação automática de incidente — **a flag `auto_create_incident` entrou no Prompt 15** ([alert-incident-automation.md](alert-incident-automation.md)), mas a decisão de criar continua fora destas classes (aqui só se guarda configuração);
- tracing/OpenTelemetry e IA.

O que **é** permitido (e foi feito): CRUD de `AlertRule`, validação de domínio, RBAC + IDOR + pais inativos, frontend de gerenciamento, migration `V9` e documentação. O `AlertRule` guarda **configuração**; a decisão de "estourou ou não" e tudo que vem depois é o **Prompt 13**.
