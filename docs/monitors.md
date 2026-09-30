# Monitors — Sentinel

> **Prompt 08 de 18** — configuração de monitoramento HTTP/HTTPS de um serviço: quarto nível da hierarquia Organization → Project → Service → Monitor. Este documento descreve **o que está construído**: tabela `monitors` (`V7`), pacote `com.sentinel.project.monitor` (entidade, repositório, DTOs, service e controller), a guarda SSRF do alvo (`com.sentinel.project.security`), paginação/filtros/ordenação, unicidade de nome **por serviço** em duas camadas, ativação/desativação idempotente e a matriz de autorização sobre o contexto de tenant já resolvido. O contexto de tenant (membership, papéis e `404` anti-enumeração) é o do [organizations.md](organizations.md), os projetos são os do [projects.md](projects.md), os serviços são os do [services.md](services.md) e a base de autenticação (Bearer + CSRF) é a do [identity.md](identity.md).

## 1. Escopo implementado

| Item | Estado |
| --- | --- |
| Migration `V7__create_monitors_table.sql` (tabela `monitors`) | Construído |
| Entidade `Monitor` + `MonitorRepository` (lookup por serviço, 2 pré-checks, 8 listagens) | Construído |
| `SsrfGuard` + `DnsResolver`/`SystemDnsResolver` (validação do alvo no cadastro) | Construído |
| `POST .../services/{serviceId}/monitors` (criação, `ADMIN`/`OPERATOR`) | Construído |
| `GET .../monitors` (página com filtros `active`/`method`/`name` e ordenação por allowlist) | Construído |
| `GET .../monitors/{monitorId}` (detalhe + `role` do usuário) | Construído |
| `PATCH .../monitors/{monitorId}` (atualização parcial: `name?`, `url?`, `method?`, `intervalSeconds?`, `timeoutMillis?`) | Construído |
| `PATCH .../monitors/{monitorId}/activation` (ativação/desativação idempotente e fail-safe) | Construído |
| Unicidade de nome **por serviço** em **duas camadas** (pré-check → `409`, `UNIQUE` → `409`) | Construído |
| Pais inativos: leitura `200` × mutação `409 PROJECT_INACTIVE`/`409 SERVICE_INACTIVE` | Construído |
| Isolamento cross-tenant → `404` genérico anti-enumeração em **três níveis** (projeto, serviço e monitor) | Construído |
| RBAC: leitura para qualquer papel, mutação para `ADMIN`/`OPERATOR` | Construído |
| Validações de entrada sem `500` (nome, método, intervalo, timeout, coerência, paginação, `sort`, ids malformados, corpo malformado) | Construído |
| Alvo interno/privado recusado **antes de persistir** → `400 MONITOR_TARGET_NOT_ALLOWED` | Construído |
| `DELETE`/`PUT` da rota → `405` (remoção não existe neste prompt) | Construído |
| **Execução** de checagens (HTTP real, redirects, revalidação em conexão), resultados, latência/status/falha, séries temporais, alertas, incidentes e dashboards | **Fora do escopo** (Prompt 09+) |

## 2. Modelo de dados

### 2.1 `Monitor` (tabela `monitors`)

| Coluna | Tipo | Observação |
| --- | --- | --- |
| `id` | `UUID` PK | Gerado **pela aplicação** (`@PrePersist` + `UUID.randomUUID()`) — a migration não declara `DEFAULT` (mesmo critério de `V4`/`V5`/`V6`) |
| `service_id` | `UUID NOT NULL` → `services(id)` | `ON DELETE RESTRICT` — excluir um serviço com monitores é recusado pelo banco (a linha é **histórico** da configuração) |
| `name` | `VARCHAR(120) NOT NULL` | `CHECK monitors_name_not_blank` (`trim(name) <> ''`) espelha a validação da API |
| `name_normalized` | `VARCHAR(120) NOT NULL` | cópia normalizada calculada **pela aplicação** (`trim` + `lowercase` com `Locale.ROOT`) |
| `type` | `VARCHAR(10) NOT NULL DEFAULT 'HTTP'` | `CHECK monitors_type_check` (`HTTP`) — **invariante do cadastro**: nasce `HTTP` e nenhum endpoint o altera (não existe `setType`) |
| `url` | `VARCHAR(2048) NOT NULL` | alvo da checagem, **validado pelo `SsrfGuard`** antes de qualquer persistência; a guarda NÃO é replicada no banco (é regra de segurança viva, não integridade) |
| `method` | `VARCHAR(10) NOT NULL DEFAULT 'GET'` | `CHECK monitors_method_check` (`GET` \| `HEAD`) |
| `interval_seconds` | `INTEGER NOT NULL DEFAULT 60` | `CHECK monitors_interval_seconds_check` (`30..86400`) — distância entre checagens, em segundos |
| `timeout_millis` | `INTEGER NOT NULL DEFAULT 5000` | `CHECK monitors_timeout_millis_check` (`100..30000`) **+** `CHECK monitors_timeout_below_interval_check` (`timeout < interval * 1000`) |
| `active` | `BOOLEAN NOT NULL DEFAULT true` | todo monitor nasce ativo; a desativação é **soft-delete** |
| `created_at` / `updated_at` | `TIMESTAMPTZ NOT NULL` | `updated_at` mantido por `@PreUpdate` (sem trigger) |

Constraints e índices:

- **`UNIQUE (service_id, name_normalized)`** (`monitors_service_name_normalized_unique`): o mesmo nome normalizado não existe **duas vezes no mesmo serviço** — ativo **ou** inativo (desativar não libera o nome). Serviços diferentes (e portanto projetos/organizações diferentes) repetem o nome livremente.
- **Índice extra em `service_id`: NÃO criado** — o índice único já tem `service_id` como coluna de **prefixo** e cobre todas as leituras por serviço (mesmo critério do `V6` em `project_id`).
- `name_normalized` é coluna **derivada mas materializada** de propósito (mesmo racional de `V5`/`V6`).
- **Nenhuma coluna de credencial**: a URL é anônima — o monitor **não** guarda header, cookie ou senha (não existe campo para isso na API).
- **Nenhuma coluna de resultado**: latência, status HTTP, falha, última execução, janela de observação ou saúde **não são persistidos** por este prompt (§10).

### 2.2 Normalização do nome

`Monitor.normalizeName(name)` = `trim` + `lowercase(Locale.ROOT)` — a **única** fonte da regra (calculada no construtor de domínio e recalculada em `rename()`), idêntica ao critério de `Service`:

- `  Padded   ` → persiste `Padded`, unicidade decide sobre `padded`;
- `HEALTH CHECK` e `health check` colidem → `409`;
- **espaços internos são preservados**: `Health  Check` e `Health Check` são nomes diferentes;
- a coluna exposta no payload é `name`; `nameNormalized` **nunca** sai na resposta.

### 2.3 O que este módulo É (e o que não é)

| Eixo | Significado | Quem o altera |
| --- | --- | --- |
| `active` | "está **no catálogo**?" (soft-delete) | `PATCH .../activation` |
| `type` | "qual protocolo de checagem?" (`HTTP` por enquanto) | **neste prompt, ninguém** (invariante do construtor) |

`active=false` é um estado perfeitamente válido e é o que a rota de ativação produz — a ativação **não** toca em `type`, `name`, `url`, `method`, `intervalSeconds`, `timeoutMillis` nem `createdAt` (coberto por teste). O cadastro guarda **configuração declarativa**: alvo, método, intervalo, timeout e participação no catálogo. Nada de "como está o alvo agora" — isso é material de monitoramento e entra com tabela própria no Prompt 09.

## 3. Autorização e ordem das checagens

Ordem em todo endpoint do módulo (a ordem é **contratual** e coberta por teste):

1. **bean validation do controller** (`@Valid` no `POST`, só ali) — `400` **antes** de tudo (um `name` em branco no create responde `400` antes mesmo do guard);
2. **CSRF** (filtro, `403`) — só para requisições sem `Authorization: Bearer` (ver §7);
3. **autenticação** (`401`);
4. **membership** no `TenantInterceptor` (`@TenantScoped`, `404` genérico para não-membro/organização inexistente/id malformado/path × header divergente);
5. **papel** em `MonitorService.requireMutationRole()` (`403 "Access denied"` para mutação sem `ADMIN`/`OPERATOR`);
6. **projeto** resolvido no tenant (`404` genérico) → **serviço** no projeto (`404` genérico), nessa ordem;
7. **pais ativos** (só em mutação): `409 PROJECT_INACTIVE` **antes** de `409 SERVICE_INACTIVE`;
8. **monitor** dentro do serviço já resolvido (`404` genérico);
9. **SSRF** quando há URL a validar (`400 MONITOR_TARGET_NOT_ALLOWED`) — create sempre, update quando `url` está presente, ativação na reativação `false → true` (§5.4);
10. **regras de payload do service** (`400`, alcançáveis sobretudo no `PATCH`, que não usa `@Valid`);
11. **unicidade** (`409 MONITOR_NAME_DUPLICATE`).

| Ação | Papéis |
| --- | --- |
| Leitura (`GET` lista/detalhe) | **qualquer papel** (`ADMIN`, `OPERATOR`, `VIEWER`) — o interceptor já provou a membership |
| Mutação (`POST`, `PATCH`, `PATCH .../activation`) | `ADMIN` ou `OPERATOR` |

O papel vem de `TenantContext.role()` — lido do banco **na requisição corrente**, nunca de claim de JWT. Todos os `403` de papel são idênticos (`"Access denied"`).

Exceções documentadas da ordem:

- **Leitura não exige pais ativos**: `list`/`get` usam `requireParents` e param a cadeia (6 → 8) sem checar `active` — respondem `200` mesmo com projeto/serviço desativados (§5.3); o papel também não é checado em leitura.
- **A ativação é fail-safe**: `active=false` pula a checagem de pais (a direção segura é sempre permitida) e **já no estado pedido devolve `200` sem escrita, ANTES de qualquer revalidação SSRF**. `active=true` exige pais ativos e revalida a URL persistida.
- No `POST`, o `@Valid` do controller roda antes do service — a ordem do create é: bean validation (nome) → role → cadeia → pais → SSRF → payload → unicidade.

A ordem **5 → 6 → 7 → 10 → 11** é coberta por `MonitorIntegrationTest.mutationOrderIsRoleThenProjectThenServiceThenPayloadThenUniqueness`: `VIEWER` sobre projeto/serviço inexistente → `403` (papel antes do `404`); `ADMIN` sobre projeto inexistente → `404`; serviço de **outro projeto** → `404`; serviço inativo → `409 SERVICE_INACTIVE` (nunca `400`); nome duplicado + payload inválido → o `400` do payload (nunca o `409`); payload OK → o `409` documentado. O lado SSRF da ordem fica no `MonitorSsrfTest` (`ssrfRunsAfterRoleAndParentChecks`, `ssrfRunsBeforePayloadAndUniquenessOnCreate`).

## 4. Endpoints

Base: `/api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/monitors`. Todos são `@TenantScoped` (organização **sempre** no path) e todos passam por um `parseId` manual de `projectId`, `serviceId` e `monitorId` — id fora do formato UUID responde o **mesmo** `404` genérico, nunca `400`/`500` de conversão de tipo.

### 4.1 Matriz de acesso

| Método | Path | Papel mínimo | CSRF |
| --- | --- | --- | --- |
| POST | `.../monitors` | `ADMIN` / `OPERATOR` | sim |
| GET | `.../monitors` | qualquer papel | não |
| GET | `.../monitors/{monitorId}` | qualquer papel | não |
| PATCH | `.../monitors/{monitorId}` | `ADMIN` / `OPERATOR` | sim |
| PATCH | `.../monitors/{monitorId}/activation` | `ADMIN` / `OPERATOR` | sim |

`PUT` e `DELETE` **não existem** nas rotas: caem no contrato global de `405 METHOD_NOT_ALLOWED` (a remoção de monitor não existe neste prompt — soft-delete é o `.../activation`).

### 4.2 `POST .../monitors` → `201`

```json
{ "name": "payments-api health", "url": "https://93.184.216.34/health",
  "method": "GET", "intervalSeconds": 60, "timeoutMillis": 5000 }
```

```json
{ "id": "uuid", "organizationId": "uuid", "projectId": "uuid", "serviceId": "uuid",
  "name": "payments-api health", "type": "HTTP",
  "url": "https://93.184.216.34/health", "method": "GET",
  "intervalSeconds": 60, "timeoutMillis": 5000, "active": true,
  "createdAt": "2026-09-28T…Z", "updatedAt": "2026-09-28T…Z", "role": "ADMIN" }
```

- Nasce `active=true` + `type=HTTP`; **o servidor define os dois** — payload não opina.
- `role` é o papel **de quem está respondendo** nesta organização (mesma semântica de `ServiceResponse`).
- `name`: obrigatório (`@NotBlank`), trim, `≤ 120` → `400` `"name: must not be blank"` / `"name: must be at most 120 characters"`.
- `url`: obrigatório **no contrato** mas sem `@NotBlank` de propósito — a checagem pertence ao caminho de segurança: ausente/vazia/nula cai no guard → `400 MONITOR_TARGET_NOT_ALLOWED` (SSRF roda **antes** da validação de domínio).
- `method`: opcional (`GET` no default); presente deve ser exatamente `GET` ou `HEAD` → senão `400` `"method: must be GET or HEAD"`.
- `intervalSeconds`: opcional (`60` no default), faixa `30..86400` → senão `400` `"intervalSeconds: must be between 30 and 86400"`.
- `timeoutMillis`: opcional (`5000` no default), faixa `100..30000` → senão `400` `"timeoutMillis: must be between 100 and 30000"`; além disso `timeout < interval × 1000` → senão `400` `"timeoutMillis: must be less than intervalSeconds * 1000"` (recalculado sobre os valores **efetivos**, também no `PATCH`).
- Nome já existente **no serviço** → `409 MONITOR_NAME_DUPLICATE` (§5.1).
- Projeto/serviço inativo → `409 PROJECT_INACTIVE` / `409 SERVICE_INACTIVE` (§5.3); `VIEWER` → `403`; não-membro → `404`; sem Bearer → `401`.

### 4.3 `GET .../monitors` → `200`

```json
{ "content": [ { "id": "uuid", "organizationId": "uuid", "projectId": "uuid",
    "serviceId": "uuid", "name": "payments-api health", "type": "HTTP",
    "url": "https://93.184.216.34/health", "method": "GET",
    "intervalSeconds": 60, "timeoutMillis": 5000, "active": true,
    "createdAt": "…", "updatedAt": "…", "role": "ADMIN" } ],
  "totalElements": 3, "totalPages": 1, "number": 0, "size": 20 }
```

Parâmetros:

| Parâmetro | Default | Regra |
| --- | --- | --- |
| `page` | `0` | inteiro `≥ 0` → senão `400` `"page: must not be negative"` |
| `size` | `20` | `1..100` → senão `400` `"size: must be between 1 and 100"` |
| `sort` | `createdAt,asc` | campo por **allowlist** (`createdAt`, `name`, `id`) + direção `asc`/`desc` (ausente ou em branco → default) → senão `400` `"sort: must be one of createdAt, name, id"` |
| `active` | *(ausente = todos)* | `true` / `false` → `400` `"Invalid request parameter"` se não for booleano |
| `method` | *(ausente = todos)* | `GET` / `HEAD` (enum tipado no controller) → `400` `"Invalid request parameter"` se fora dele, nunca `500` |
| `name` | *(ausente = sem filtro)* | **subcadeia case-insensitive** sobre o nome normalizado (`?name=HEALTH` encontra "Health Check"); valor em branco = sem filtro |

- Os três filtros são **combináveis** (`active` × `method` × `name` → 8 variantes de `derived query`; o Spring Data não aceita predicado opcional em método derivado).
- **Ordenação determinística**: `id asc` entra sempre como desempate (fixo no service); `id` já ordenado não leva desempate extra.
- Formato: serialização **direta** de `Page` do Spring Data, a mesma forma dos Prompts 06/07 (`content`, `totalElements`, `totalPages`, `number`, `size`).
- Só os monitores do serviço resolvido **dentro** do tenant corrente; a leitura funciona mesmo com projeto/serviço inativos (§5.3).

### 4.4 `GET .../monitors/{monitorId}` → `200` | `404`

- Membro (qualquer papel) → `200` com o mesmo shape do `201`.
- Monitor inexistente, **de outro serviço**, de **outra organização** ou `monitorId` malformado → **`404`** `{"code":"NOT_FOUND","message":"Resource not found"}` (o mesmo corpo nos casos — anti-enumeração).
- Sem `Bearer` → `401`.

### 4.5 `PATCH .../monitors/{monitorId}` → `200`

```json
{ "name": "payments-api health v2", "intervalSeconds": 30 }
```

- Atualização **parcial**: campo **ausente** (`null`) não altera; `name: "   "` → `400` (o service valida "presente porém em branco", que o `@Valid` do create não cobre no PATCH).
- `url` presente é **revalidada pelo guard** antes de qualquer validação de payload — um update não é bypass da proteção SSRF.
- A coerência `timeout < interval × 1000` é recalculada sobre os valores **efetivos** (campo novo ou valor persistido), então um `PATCH` que só muda um dos dois também passa por aqui.
- Renomear recalcula `nameNormalized` e valida a unicidade contra **todos** os monitores do serviço, **exceto o próprio** → renomear para o próprio nome (ou só mudar a caixa) é `200` idempotente; para o nome de outro monitor → `409`.
- Nada das tentativas inválidas é aplicado: a validação acontece antes do `saveAndFlush`.
- DTO fechado: `active` tem rota própria, `type` não é alterável e os campos de escopo não existem no payload (§5.5).

### 4.6 `PATCH .../monitors/{monitorId}/activation` → `200`

```json
{ "active": false }
```

- **Idempotente**: ativar um monitor já ativo (ou desativar um já inativo) devolve `200` com o estado atual **sem escrita** — o `updatedAt` não avança (coberto por teste).
- `Boolean` (objeto) + `@NotNull`: body ausente, `{}` ou `{"active": null}` → `400` `"active: must not be null"` — o servidor nunca "adivinha" um `false` implícito.
- **Não toca na configuração**: `active` é o único campo que muda (nome, alvo, método, intervalo, timeout, `type` e `createdAt` ficam intactos).
- `false → true` **revalida a URL persistida** pelo guard (o alvo pode ter sido reclassificado como interno) e exige pais ativos; `true → false` é sempre permitida — inclusive com projeto/serviço inativos (fail-safe, §5.3).
- Desativar não apaga o nome da unicidade: criar outro monitor com o mesmo nome continua `409`.
- O caminho se chama `activation` — e não `status` — porque o corpo liga/desliga `active`.

### 4.7 `DELETE`/`PUT` → `405`

A remoção **não existe** neste prompt (nem handler, nem soft-delete implícito): `DELETE .../monitors` e `DELETE .../monitors/{monitorId}` respondem `405 METHOD_NOT_ALLOWED` no shape padrão, assim como `PUT` na coleção.

## 5. Regras de domínio

### 5.1 Unicidade de nome em duas camadas (por serviço)

1. **Pré-check** (`existsByServiceIdAndNameNormalized[AndIdNot]`) → `409 MONITOR_NAME_DUPLICATE` imediato — é o caminho comum;
2. **`UNIQUE (service_id, name_normalized)`** na migration: absorve a corrida entre o check e o `saveAndFlush`. A `DataIntegrityViolationException` é traduzida **no service** no **mesmo** `409` com a **mesma** mensagem (`"Monitor with this name already exists in this service"`) — nunca um `500` nem mensagem crua de SQL.

Cobertura por teste de concorrência (`CountDownLatch` + 2 threads): dois `POST` simultâneos do mesmo nome → exatamente **um** `201` e **um** `409`; dois `PATCH` renomeando para o mesmo destino → exatamente **um** `200` e **um** `409`. O mesmo nome em **serviços diferentes** → `201` para os dois (a unicidade é por serviço, não global).

### 5.2 Isolamento cross-tenant em TRÊS níveis

O `TenantInterceptor` resolve e valida o tenant **antes** do handler; dentro do service a autorização é provada por **três consultas encadeadas** — nunca por um `findById` avulso:

1. `ProjectRepository.findByOrganizationIdAndId(tenant, projectId)` prova **Organization → Project**;
2. `ServiceRepository.findByProjectIdAndId(projectId, serviceId)` prova **Project → Service**;
3. `MonitorRepository.findByServiceIdAndId(serviceId, monitorId)` prova **Service → Monitor**.

Todo finder do repositório é `findByServiceId*(serviceId, …)`: **não existe caminho de leitura global de monitores**. Como o serviço já foi resolvido dentro do tenant, um monitor de outro serviço/tenant responde o mesmo `404` de um id inexistente.

| Situação | Onde | Resposta |
| --- | --- | --- |
| Organização inexistente / não-membro / id malformado / path × header divergente | `TenantInterceptor` | `404` genérico |
| Projeto inexistente ou de **outra** organização | `MonitorService.findProjectInTenant` | `404` genérico |
| Serviço inexistente ou pertencente a **outro** projeto | `MonitorService.findService` | `404` genérico |
| Monitor inexistente, **de outro serviço** ou de outra organização | `MonitorService.findMonitor` | `404` genérico |
| `projectId`/`serviceId`/`monitorId` malformado | `MonitorController.parseId` | `404` genérico (nunca `500`) |

`MonitorIdorAdversarialTest` cobre a matriz sobre o cenário canônico **Alpha** (Alice=`ADMIN`, Bob=`OPERATOR`) × **Beta** (Bob=`ADMIN`, Alice=`VIEWER`) × **Charlie** (sem membership): monitor Beta alcançado pelo path de Alpha, `projectId` Beta dentro do path de Alpha, monitor Alpha alcançado pelo projeto/serviço Beta, monitor alcançado pelo **serviço irmão** no mesmo projeto, id aleatório/malformado, `VIEWER` lendo mas recebendo `403` em toda mutação, `404` em todos os endpoints sem token, o `403` de CSRF e a divergência path × header — **sempre sem escrita aplicada**.

### 5.3 Pais inativos × desativação fail-safe

| Operação | pais ativos | pais inativos |
| --- | --- | --- |
| `GET` lista / `GET` detalhe | `200` | **`200`** (o catálogo continua consultável) |
| `POST` / `PATCH` | segue a matriz | **`409 PROJECT_INACTIVE`** / **`409 SERVICE_INACTIVE`** |
| `PATCH .../activation` com `active=false` | `200` | **`200`** (desligar é a direção segura) |
| `PATCH .../activation` com `active=true` | `200` | **`409 …_INACTIVE`** (a checagem de pais roda **antes** do no-op: um monitor já ativo em projeto inativo também recebe `409`) |

Mensagens: `"Cannot create or modify monitors in an inactive project"` e `"Cannot create or modify monitors in an inactive service"`. A ordem é **sempre** `PROJECT_INACTIVE` antes de `SERVICE_INACTIVE`, e as checagens acontecem **depois** do `404` de cadeia e **antes** da validação de payload/SSRF — desativar projeto e serviço congela a configuração sem apagá-la, mantendo a leitura e a direção segura de desativação disponíveis.

### 5.4 SSRF no cadastro do alvo

`SsrfGuard` valida a URL **antes de persistir ou reativar** — nenhuma requisição sai do servidor para o alvo (exceto DNS, quando o host é um nome, apenas para classificar endereços):

| Momento | URL validada? |
| --- | --- |
| `POST` (create) | **sempre** |
| `PATCH` (update) | só quando o campo `url` está **presente** |
| `activation` `false → true` | **sim** (revalida a URL persistida) |
| `activation` repetindo o estado atual | **não** (no-op acontece antes) |
| `activation` `true → false` | não (desligar nunca revalida) |

Ordem das checagens do guard (todas violações → `400 MONITOR_TARGET_NOT_ALLOWED`):

1. parse com `java.net.URI` (nada de regex própria): sintaxe inválida/vazia/nula rejeita;
2. scheme só `http`/`https`; host presente;
3. sem `userinfo` (`user:pass@host`) e sem `fragment`;
4. porta ausente ou em `1..65535`;
5. fast-path `localhost`/`*.localhost` bloqueado **antes** de qualquer resolução;
6. literal IP classifica direto (sem DNS); hostname resolve via `DnsResolver` e **TODOS** os endereços precisam ser públicos (mistura público+privado é bloqueada);
7. classes bloqueadas explicitamente: IPv4 `0/8`, `10/8`, `100.64/10`, `127/8`, `169.254/16`, `172.16/12`, `192.0.0/24`, `192.0.2/24`, `192.88.99/24`, `192.168/16`, `198.18/15`, `198.51.100/24`, `203.0.113/24`, `224/4` e `240/4`; IPv6 `::`, `::1`, `fc00::/7`, `fe80::/10`, `ff00::/8`, fora de `2000::/3` e `2001:db8::/32`.

Mensagens do contrato (nunca citam IP, range ou a regra exata): violação → `"This target is not allowed for security reasons."`; falha de DNS → `"Unable to resolve target host"`.

**Limites declarados:** este guard valida o **cadastro** — reduz o espaço de ataque, mas **não** é garantia em tempo de execução. DNS rebinding e redirects para alvos internos exigem revalidação **antes de cada conexão e de cada redirect**, trabalho do Prompt 09. Por isso a interface `DnsResolver` é injetável: o mesmo guard é reutilizável lá sem repetir a política de ranges.

### 5.5 Mass assignment

Os DTOs são **fechados**: `organizationId`, `projectId`, `serviceId`, `id`, `active`, `type` e timestamps não existem nos records de entrada. O escopo vem do path (interceptor + lookup), `type` nasce `HTTP` no construtor de domínio e `active` tem rota própria. Campos desconhecidos no JSON são **ignorados pelo parser** (comportamento default do Jackson, confirmado por teste): enviar `"active": false` ou `"type": "PING"` no `POST`/`PATCH` não muda nada — o monitor nasce ativo e `HTTP`, e os `id`/`createdAt` são os do servidor.

## 6. Contrato de erro

Shape padrão da API (o mesmo do [identity.md](identity.md)):

```json
{ "code": "MONITOR_NAME_DUPLICATE",
  "message": "Monitor with this name already exists in this service",
  "timestamp": "2026-09-28T…Z",
  "path": "/api/v1/organizations/…/monitors" }
```

| Código | HTTP | Quando |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | `name` em branco/>120; `method` fora de `GET`/`HEAD`; `intervalSeconds` fora de 30..86400; `timeoutMillis` fora de 100..30000 ou `≥ interval × 1000`; `page` negativo; `size` fora de 1..100; `sort` fora da allowlist; `active` ausente/nulo na ativação; tipo errado em query param (`"Invalid request parameter"`) e corpo malformado (`"Malformed request body"`) |
| `MONITOR_TARGET_NOT_ALLOWED` | 400 | Alvo rejeitado pelo `SsrfGuard` (create, update com `url` ou reativação) — mensagens do §5.4 |
| `UNAUTHORIZED` | 401 | Sem/inválido Bearer |
| `FORBIDDEN` | 403 | CSRF ausente/inválido (requisições sem Bearer) **ou** papel insuficiente para mutação (`"Access denied"`) |
| `NOT_FOUND` | 404 | Ver §5.2 (sempre `"Resource not found"`) |
| `METHOD_NOT_ALLOWED` | 405 | `DELETE`/`PUT` em rota existente (`"Method DELETE not allowed"`) |
| `MONITOR_NAME_DUPLICATE` | 409 | Nome já usado **no mesmo serviço** (criação ou rename, inclusive na corrida) |
| `PROJECT_INACTIVE` | 409 | Mutação com projeto desativado (`"Cannot create or modify monitors in an inactive project"`) |
| `SERVICE_INACTIVE` | 409 | Mutação com serviço desativado (`"Cannot create or modify monitors in an inactive service"`) |
| `INTERNAL_ERROR` | 500 | Erros inesperados (catch-all, sem vazar internals) |

## 7. CSRF e autenticação

- Toda escrita segue o contrato double-submit da API (cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`); nenhum teste usa `.with(csrf())` — o `CsrfFilter` real é o que está sob teste.
- **Nota de framework (comportamento intencional do Spring Security):** requisições que trazem `Authorization: Bearer …` são **isentas** da proteção CSRF; o double-submit continua obrigatório para requisições autenticadas por cookie/sessão. Cobre dois testes: `writeWithoutCsrfTokenGets403` (sem Bearer → `403` antes mesmo da autenticação) e `authenticatedBearerWriteIsCsrfExemptByFramework` (com Bearer → a escrita procede).

## 8. Estrutura de código (backend)

```
com.sentinel
├── project
│   ├── monitor                          # sub-pacote do Prompt 08 (quarto nível)
│   │   ├── model
│   │   │   ├── Monitor.java             # entidade `monitors` (rename() recalcula nameNormalized)
│   │   │   ├── MonitorMethod.java       # enum {GET, HEAD}
│   │   │   └── MonitorType.java         # enum {HTTP} — invariante, sem setter
│   │   ├── repository
│   │   │   └── MonitorRepository.java   # 1 lookup por serviço + 2 existsBy + 8 listagens
│   │   ├── dto
│   │   │   ├── MonitorCreateRequest.java     # {name, url, method?, intervalSeconds?, timeoutMillis?}
│   │   │   ├── MonitorUpdateRequest.java     # parcial — sem @Valid: ausente ≠ em branco
│   │   │   ├── MonitorActivationRequest.java # {@NotNull Boolean active}
│   │   │   └── MonitorResponse.java          # {id,organizationId,projectId,serviceId,name,type,url,method,intervalSeconds,timeoutMillis,active,createdAt,updatedAt,role}
│   │   ├── service
│   │   │   └── MonitorService.java      # RBAC, cadeia de 3 níveis, pais, SSRF, payload, unicidade
│   │   └── web
│   │       └── MonitorController.java   # .../services/{serviceId}/monitors (@TenantScoped)
│   └── security                         # guarda SSRF (novo no Prompt 08)
│       ├── SsrfGuard.java               # política de ranges + mensagens estáveis
│       ├── DnsResolver.java             # interface injetável (DNS stub nos testes)
│       └── SystemDnsResolver.java       # impl. de produção (InetAddress)
```

- O módulo **não** cria configuração de segurança nova: sem filtro, interceptor ou handler próprios — apoia-se em `TenantInterceptor`/`TenantContext` (Prompt 04) e na cadeia do Prompt 03.
- **Conflito de nome `Service` × `@Service`**: mesmo arranjo do `ServiceService` — entidade `com.sentinel.project.model.Service` importada normalmente e estereótipo com FQN **uma única vez** (`@org.springframework.stereotype.Service` em `MonitorService`).
- `MonitorUpdateRequest` é validado **no service** (sem `@Valid`) porque só ele distingue "campo ausente" de "campo em branco"; o `@Valid` existe **só** no `POST` e na rota de ativação.
- `method` é query param **tipado** no `GET` (`MonitorMethod`): valor fora do enum vira `MethodArgumentTypeMismatchException` → o handler global responde `400` `"Invalid request parameter"`, nunca `500`.
- A ordem das checagens fica no service — é regra de domínio, não de rota.

## 9. Testes

- `mvn clean test` — **346 testes, 0 falhas, exit 0**; `mvn -DskipTests package` — exit 0. Conta medida `@Test` por classe e fechada com o Surefire: baseline pós-Prompt-07 = **257** (fechamento de [services.md](services.md)) → gate intermediário só com o `MonitorSsrfTest` na árvore = 257 + 24 = **281** → total final = 257 + **89** novos = **346**, e os **89** batem exatamente com as cinco classes listadas abaixo (41 + 24 + 14 + 7 + 3).
- **Nenhuma classe de teste existente foi alterada**: a base nova `AbstractMonitorIntegrationTest` estende `AbstractServiceIntegrationTest` e só acrescenta o vocabulário de monitor (URLs de rota, `createMonitor`, `changeMonitorActivation`, `monitorOf` e o `monitorRepository`).
- **URL pública canônica dos testes**: `https://93.184.216.34/health` — literal IPv4 público, interpretado por `InetAddress` **sem DNS**; assim nenhuma classe (fora a de SSRF) depende de rede ou do resolver do ambiente.
- **Novas classes** (todas herdam `AbstractMonitorIntegrationTest`, contexto próprio por `PostgreSQLContainer`):
  - `MonitorIntegrationTest` (41): contrato do `201` (campos de servidor, `nameNormalized` fora do payload), trim e validações (`400` com mensagem exata), `401`, unicidade nas duas camadas (duplicata, duplicata de monitor desativado, mesmo nome em serviços diferentes), **mass assignment**, `404` (inexistente/malformado/outro serviço), listagem (shape estável, paginação, filtros `active`/`method`/`name`, ordenação, escopo do serviço, parâmetros inválidos), `PATCH` (rename, campos ausentes, `400` sem aplicar nada, `409`, rename idempotente), ativação, `405` de `DELETE`/`PUT`, **pais inativos** (leitura `200` × mutação `409`, fail-safe de desativação), **ordem das checagens** (role → projeto → serviço → payload → unicidade) e RBAC (`VIEWER` 403, `OPERATOR` pode mutar).
  - `MonitorSsrfTest` (24): guarda SSRF com **DNS stub injetável** (única classe com `@TestConfiguration` aninhada — o stub não vaza para os demais contextos): literais IPv4/IPv6 bloqueados, `localhost` antes de resolver, schemes/userinfo/porta, hostname público × privado × misto × irresolvível, alvo rejeitado nunca persistido, ordem do guard, `PATCH` com URL interna, reativação revalidando o alvo persistido, no-op **sem** revalidação — mais as validações de payload do `POST` (método, faixas, coerência) e os defaults.
  - `MonitorIdorAdversarialTest` (14): cenário Alpha/Beta/Charlie nos **três** níveis (monitor cross-tenant, projeto/serviço de outro tenant dentro do path próprio, serviço irmão), id aleatório/malformado, não-membro `404` em tudo sem escrita, `VIEWER` lê mas recebe `403`, `OPERATOR` pode mutar, `401` sem token, CSRF (`403` sem Bearer × isenção com Bearer), path × header divergente e ids malformados de organização/projeto.
  - `MonitorActivationIdempotencyTest` (7): round-trip persistido no banco, repetição do estado atual **sem** avançar `updatedAt`, ativação que **não** toca configuração nem `type` (+ `PATCH` que não força `active`/`type`), `400` com `active` ausente/nulo, corpo malformado, `404` genérico para id desconhecido e id malformado.
  - `MonitorConcurrencyTest` (3): criação simultânea do mesmo nome → um `201` + um `409`; rename simultâneo para o mesmo destino → um `200` + um `409`; mesmo nome em dois serviços em paralelo → dois `201`.
- Os testes usam CSRF **real** (cookie + header) e **sem Internet** (literais IP públicos + DNS stub apenas onde o hostname é o objeto do teste).
- **Como os números acima foram medidos**: `grep -E '^\s*@Test\b'` em cada uma das 5 classes de monitor (41 + 24 + 14 + 7 + 3 = **89**) e soma de `Tests run` nos relatórios do Surefire (`backend/target/surefire-reports/*.txt` → **346**). Um `grep -c "@Test"` cru em `MonitorSsrfTest` devolve **27** porque ainda conta as duas menções de `@TestConfiguration` na javadoc e a própria anotação `@TestConfiguration`; os testes executados são **24** — confirmado tanto pelo `grep -E '^\s*@Test\b'` quanto pelo Surefire.

## 10. Fora do escopo (prompts futuros)

- **Execução das checagens** (Prompt 09): scheduler, requisições HTTP reais, revalidação do alvo **por conexão e por redirect** (DNS rebinding), classificação de falha e codificação de resultado.
- Tabela de **resultados** de checagem: **construída no Prompt 10** fora desta tabela (`monitor_checks`, ver [operational-state.md](operational-state.md) — latência, status HTTP, falha e o `status` operacional do serviço); **séries temporais, janelas de observação por período** e dashboards continuam **fora do escopo**. Nenhuma coluna desse tipo existe em `monitors`.
- **Regras de alerta, notificações, incidentes, dashboards** e trilha de auditoria.
- Remoção definitiva de monitor (hoje a rota `DELETE` devolve `405`; o soft-delete é o `.../activation`).
- Outros tipos de checagem além de `HTTP` (`PING`, `TCP`, `DNS`…), credenciais/headers no alvo e bloqueio por escopo geográfico.
- Varredura de portas, escaneamento de rede ou qualquer uso do guard fora do cadastro de monitores — o `SsrfGuard` cobre **validação de URL configurada**, não a execução.
