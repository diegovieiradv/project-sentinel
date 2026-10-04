# Services — Sentinel

> **Prompt 07 de 18** — serviços de um projeto: segundo nível da hierarquia Organization → Project → Service. Este documento descreve **o que está construído**: tabela `services` (`V6`), módulo `com.sentinel.project` (entidade `Service`, repositório, DTOs, service e controller), paginação/filtros/ordenação, unicidade de nome **por projeto** em duas camadas, ativação/desativação idempotente e a matriz de autorização sobre o contexto de tenant já resolvido. O contexto de tenant (membership, papéis e `404` anti-enumeração) é o do [organizations.md](organizations.md), os projetos são os do [projects.md](projects.md) e a base de autenticação (Bearer + CSRF) é a do [identity.md](identity.md).

## 1. Escopo implementado

| Item | Estado |
| --- | --- |
| Migration `V6__create_services_table.sql` (tabela `services`) | Construído |
| Entidade `Service` + `ServiceRepository` (lookups em duas consultas, tenant-scoped) | Construído |
| `POST .../projects/{projectId}/services` (criação, `ADMIN`/`OPERATOR`) | Construído |
| `GET .../services` (página com filtros `active`/`status`/`name` e ordenação por allowlist) | Construído |
| `GET .../services/{serviceId}` (detalhe + `role` do usuário) | Construído |
| `PATCH .../services/{serviceId}` (atualização parcial: `name?`, `description?`) | Construído |
| `PATCH .../services/{serviceId}/activation` (ativação/desativação idempotente) | Construído |
| Unicidade de nome **por projeto** em **duas camadas** (pré-check → `409`, `UNIQUE` → `409`) | Construído |
| Projeto inativo: leitura `200` × mutação `409 PROJECT_INACTIVE` | Construído |
| Isolamento cross-tenant → `404` genérico anti-enumeração em **dois níveis** (projeto e serviço) | Construído |
| RBAC: leitura para qualquer papel, mutação para `ADMIN`/`OPERATOR` | Construído |
| Validações de entrada sem `500` (nome, descrição, paginação, `sort`, ids malformados, corpo malformado) | Construído |
| `DELETE` da rota → `405` (remoção não existe neste prompt) | Construído |
| Coleta de dados de saúde, regras de alerta e dashboards | **Fora do escopo** (prompts futuros) |
| Ciclo de vida de incidentes do serviço | **Construído no Prompt 14** (sub-recurso `.../services/{serviceId}/incidents` — ver [incidents.md](incidents.md)) |

## 2. Modelo de dados

### 2.1 `Service` (tabela `services`)

| Coluna | Tipo | Observação |
| --- | --- | --- |
| `id` | `UUID` PK | Gerado **pela aplicação** (`@PrePersist` + `UUID.randomUUID()`) — a migration não declara `DEFAULT` (mesmo critério de `V4`/`V5`) |
| `project_id` | `UUID NOT NULL` → `projects(id)` | `ON DELETE RESTRICT` — excluir um projeto com serviços é recusado pelo banco (a linha é **histórico** do projeto) |
| `name` | `VARCHAR(120) NOT NULL` | `CHECK services_name_not_blank` (`trim(name) <> ''`) espelha a validação da API |
| `name_normalized` | `VARCHAR(120) NOT NULL` | cópia normalizada calculada **pela aplicação** (`trim` + `lowercase` com `Locale.ROOT`) |
| `description` | `VARCHAR(1000) NULL` | opcional; valor vazio é normalizado para `NULL` |
| `active` | `BOOLEAN NOT NULL DEFAULT true` | todo serviço nasce ativo; a desativação é **soft-delete** |
| `status` | `VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'` | `CHECK services_status_check` (`OPERATIONAL` \| `DEGRADED` \| `UNAVAILABLE` \| `UNKNOWN`) — **nenhum endpoint deste prompt o altera** |
| `created_at` / `updated_at` | `TIMESTAMPTZ NOT NULL` | `updated_at` mantido por `@PreUpdate` (sem trigger) |

Constraints e índices:

- **`UNIQUE (project_id, name_normalized)`** (`services_project_name_normalized_unique`): o mesmo nome normalizado não pode existir **duas vezes no mesmo projeto** — ativo **ou** inativo (desativar não libera o nome). Projetos diferentes (e portanto organizações diferentes) repetem o nome livremente.
- **Índice extra em `project_id`: NÃO criado** — o índice único já tem `project_id` como coluna de **prefixo** e cobre todas as leituras por projeto (mesmo critério do `V5` em `organization_id`).
- `name_normalized` é coluna **derivada mas materializada** de propósito: a `UNIQUE` não seria expressível sobre uma expressão normalizada por linha e a busca usaria `lower()` na query (função de coluna quebra o plano de índice).
- Nenhum índice extra para `active`/`status`: o escopo é sempre **um projeto** (o índice único é o caminho de leitura) e a cardinalidade por projeto é pequena.

### 2.2 Normalização do nome

`Service.normalizeName(name)` = `trim` + `lowercase(Locale.ROOT)` — a **única** fonte da regra (calculada no construtor de domínio e recalculada em `rename()`):

- `  Padded   ` → persiste `Padded`, unicidade decide sobre `padded`;
- `BACKEND WEB` e `backend web` colidem → `409`;
- **espaços internos são preservados**: `Frontend  Web` e `Frontend Web` são nomes diferentes;
- a coluna exposta no payload é `name`; `nameNormalized` **nunca** sai na resposta (é detalhe da unicidade, não informação de domínio).

### 2.3 `active` × `status` — dois eixos independentes

| Eixo | Significado | Quem o altera |
| --- | --- | --- |
| `active` | "está **no catálogo**?" (soft-delete) | `PATCH .../activation` |
| `status` | "como está operando?" (`UNKNOWN` nasce aqui) | **neste prompt, ninguém** |

`active=false` + `status=UNKNOWN` é um estado perfeitamente válido e é o que a rota de ativação produz — a ativação **não** toca em `status`, nome, descrição nem `createdAt`. `UNKNOWN` significa *ausência de observação*, não "saudável": o servidor não afirma saúde que ninguém mediu. O enum existe hoje porque a coluna é parte do modelo deste prompt; a transição de `status` entra como caminho de domínio explícito em um prompt futuro, nunca como campo de payload.

## 3. Autorização e ordem das checagens

Ordem em todo endpoint do módulo:

1. **bean validation do controller** (`@Valid` no `POST`, só ali) — `400` **antes** de tudo (mesmo comportamento do `ProjectController.create`: um payload estruturalmente inválido responde `400` independentemente do papel, sem nenhuma informação sobre recursos);
2. **CSRF** (filtro, `403`) — só para requisições sem `Authorization: Bearer` (ver §7);
3. **autenticação** (`401`);
4. **membership** no `TenantInterceptor` (`@TenantScoped`, `404` genérico para não-membro/organização inexistente/id malformado/path × header divergente);
5. **papel** em `ServiceService.requireMutationRole()` (`403 "Access denied"` para mutação sem `ADMIN`/`OPERATOR`);
6. **projeto** resolvido no tenant (`404` genérico);
7. **projeto ativo** (`409 PROJECT_INACTIVE`);
8. **regras de payload do service** (`400`, alcançáveis sobretudo no `PATCH`, que não usa `@Valid`) e **unicidade** (`409`).

| Ação | Papéis |
| --- | --- |
| Leitura (`GET` lista/detalhe) | **qualquer papel** (`ADMIN`, `OPERATOR`, `VIEWER`) — o interceptor já provou a membership |
| Mutação (`POST`, `PATCH`, `PATCH .../activation`) | `ADMIN` ou `OPERATOR` |

O papel vem de `TenantContext.role()` — lido do banco **na requisição corrente**, nunca de claim de JWT. Todos os `403` de papel são idênticos (`"Access denied"`), sem detalhar a política.

A ordem **5 → 6 → 7 → 8** é contratual e coberta por teste (`mutationOrderIsRoleThenProjectThenActiveThenPayload` + `inactiveProjectAllowsReadsAndBlocksMutationsWith409`): `VIEWER` sobre projeto inexistente → `403` (papel antes do projeto, que responderia `404`); `ADMIN` sobre projeto inexistente → `404`; `PATCH` com nome em branco em projeto inativo → `409 PROJECT_INACTIVE` (nunca `400`); criação de nome duplicado em projeto inativo → `409 PROJECT_INACTIVE` (nunca `SERVICE_NAME_DUPLICATE`); e projeto inativo com `serviceId` inexistente → ainda `409`.

## 4. Endpoints

Base: `/api/v1/organizations/{organizationId}/projects/{projectId}/services`. Todos são `@TenantScoped` (organização **sempre** no path) e todos passam por um `parseId` manual de `projectId` e `serviceId` — id fora do formato UUID responde o **mesmo** `404` genérico, nunca `400`/`500` de conversão de tipo.

### 4.1 Matriz de acesso

| Método | Path | Papel mínimo | CSRF |
| --- | --- | --- | --- |
| POST | `.../services` | `ADMIN` / `OPERATOR` | sim |
| GET | `.../services` | qualquer papel | não |
| GET | `.../services/{serviceId}` | qualquer papel | não |
| PATCH | `.../services/{serviceId}` | `ADMIN` / `OPERATOR` | sim |
| PATCH | `.../services/{serviceId}/activation` | `ADMIN` / `OPERATOR` | sim |

`PUT` e `DELETE` **não existem** nas rotas: caem no contrato global de `405 METHOD_NOT_ALLOWED` (a remoção de serviço não existe neste prompt — soft-delete é o `.../activation`).

### 4.2 `POST .../services` → `201`

```json
{ "name": "payments-api", "description": "API de pagamentos" }
```

```json
{ "id": "uuid", "organizationId": "uuid", "projectId": "uuid",
  "name": "payments-api", "description": "API de pagamentos",
  "active": true, "status": "UNKNOWN",
  "createdAt": "2026-09-27T…Z", "updatedAt": "2026-09-27T…Z", "role": "ADMIN" }
```

- Nasce `active=true` + `status=UNKNOWN`; **o servidor define os dois** — payload não opina.
- `role` é o papel **de quem está respondendo** nesta organização (mesma semântica de `ProjectResponse`).
- `name`: obrigatório (`@NotBlank`), trim, `≤ 120` → `400` `"name: must not be blank"` / `"name: must be at most 120 characters"`.
- `description`: opcional, trim, `≤ 1000`; vazia vira `null` → `400` `"description: must be at most 1000 characters"`.
- Nome já existente **no projeto** → `409 SERVICE_NAME_DUPLICATE` (§5.1).
- Projeto inativo → `409 PROJECT_INACTIVE` (§5.3); `VIEWER` → `403`; não-membro → `404`; sem Bearer → `401`.

### 4.3 `GET .../services` → `200`

```json
{ "content": [ { "id": "uuid", "organizationId": "uuid", "projectId": "uuid",
    "name": "payments-api", "description": null, "active": true,
    "status": "UNKNOWN", "createdAt": "…", "updatedAt": "…", "role": "ADMIN" } ],
  "totalElements": 3, "totalPages": 1, "number": 0, "size": 20 }
```

Parâmetros:

| Parâmetro | Default | Regra |
| --- | --- | --- |
| `page` | `0` | inteiro `≥ 0` → senão `400` `"page: must not be negative"` |
| `size` | `20` | `1..100` → senão `400` `"size: must be between 1 and 100"` |
| `sort` | `createdAt,asc` | campo por **allowlist** (`createdAt`, `name`, `id`) + direção `asc`/`desc` (ausente ou em branco → default) → senão `400` `"sort: must be one of createdAt, name, id"` |
| `active` | *(ausente = todos)* | `true` / `false` → `400` se não for booleano |
| `status` | *(ausente = todos)* | valor do enum → `400` se fora dele, nunca `500` |
| `name` | *(ausente = sem filtro)* | **subcadeia case-insensitive** sobre o nome normalizado (`?name=API` encontra "payments-api"); valor em branco = sem filtro |

- Os três filtros são **combináveis** (`active` × `status` × `name` → 8 variantes de `derived query`; o Spring Data não aceita predicado opcional em método derivado).
- **Ordenação determinística**: `id asc` entra sempre como desempate (fixo no service) — o mesmo resultado mesmo com timestamps iguais; `id` já ordenado não leva desempate extra.
- O formato é a serialização **direta** de `Page` do Spring Data (modo default), a mesma forma que o cliente de projetos já consome: `content`, `totalElements`, `totalPages`, `number`, `size`.
- Só os serviços do projeto resolvido **dentro** do tenant corrente; a leitura funciona mesmo com o projeto inativo (§5.3).

### 4.4 `GET .../services/{serviceId}` → `200` | `404`

- Membro (qualquer papel) → `200` com o mesmo shape do `201`.
- Serviço inexistente, **de outro projeto**, de **outra organização** ou `serviceId` malformado → **`404`** `{"code":"NOT_FOUND","message":"Resource not found"}` (o mesmo corpo nos casos — anti-enumeração).
- Sem `Bearer` → `401`.

### 4.5 `PATCH .../services/{serviceId}` → `200`

```json
{ "name": "payments-api v2" }
```

```json
{ "description": "Nova descrição" }
```

- Atualização **parcial**: campo **ausente** (`null`) não altera; `description: ""` **limpa** a descrição (vira `null`); `name: "   "` → `400` (a validação do service cobre "presente porém em branco", que o `@Valid` do create não cobre no PATCH).
- Renomear recalcula `nameNormalized` e valida a unicidade contra **todos** os serviços do projeto, **exceto o próprio** → renomear para o próprio nome (ou só mudar a caixa) é `200` idempotente; para o nome de outro serviço → `409`.
- Nada das tentativas inválidas é aplicado: a validação acontece antes do `saveAndFlush`.
- DTO fechado: `active` tem rota própria, `status` não é alterável e os campos de escopo não existem no payload (ver §5.4).

### 4.6 `PATCH .../services/{serviceId}/activation` → `200`

```json
{ "active": false }
```

- **Idempotente**: ativar um serviço já ativo (ou desativar um já inativo) devolve `200` com o estado atual **sem escrita** — o `updatedAt` não avança (coberto por teste).
- `Boolean` (objeto) + `@NotNull`: body ausente, `{}` ou `{"active": null}` → `400` `"active: must not be null"` — o servidor nunca "adivinha" um `false` implícito.
- **Não toca em `status`**: `active=false` + `status=UNKNOWN` é o resultado esperado.
- Desativar não apaga o nome da unicidade: criar outro serviço com o mesmo nome continua `409`.
- O caminho se chama `activation` — e não `status` — porque o corpo liga/desliga `active`; um caminho `status` com `{"active": ...}` prometeria exatamente a confusão que o domínio quer evitar.

### 4.7 `DELETE`/`PUT` → `405`

A remoção **não existe** neste prompt (nem handler, nem soft-delete implícito): `DELETE .../services` e `DELETE .../services/{serviceId}` respondem `405 METHOD_NOT_ALLOWED` no shape padrão, assim como `PUT` na coleção.

## 5. Regras de domínio

### 5.1 Unicidade de nome em duas camadas (por projeto)

1. **Pré-check** (`existsByProjectIdAndNameNormalized[AndIdNot]`) → `409 SERVICE_NAME_DUPLICATE` imediato com mensagem amigável — é o caminho comum;
2. **`UNIQUE (project_id, name_normalized)`** na migration: absorve a corrida entre o check e o `saveAndFlush`. A `DataIntegrityViolationException` é traduzida **no service** no **mesmo** `409` com a **mesma** mensagem — nunca um `500` nem mensagem crua de SQL.

Cobertura por teste de concorrência (`CountDownLatch` + 2 threads): dois `POST` simultâneos do mesmo nome → exatamente **um** `201` e **um** `409`; dois `PATCH` renomeando para o mesmo destino → exatamente **um** `200` e **um** `409`. O mesmo nome em **projetos diferentes** → `201` para os dois (a unicidade é por projeto, não global).

### 5.2 Isolamento cross-tenant em DOIS níveis

O `TenantInterceptor` resolve e valida o tenant **antes** do handler; dentro do service a autorização é provada por **duas consultas encadeadas** — nunca por um `findById` avulso:

1. `ProjectRepository.findByOrganizationIdAndId(tenant, projectId)` prova **Organization → Project** (e é o que permite checar se o projeto está ativo);
2. `ServiceRepository.findByProjectIdAndId(projectId, serviceId)` prova **Project → Service**.

A entidade `Service` não tem `organizationId` próprio (o caminho seria `project.organization.id`), por isso o lookup é composto — o Spring Data não inventaria esse predicado numa derived query. Como o projeto já foi resolvido dentro do tenant, todo o resto do repositório é escopado só por `projectId`: **não existe caminho de leitura global de serviços**.

| Situação | Onde | Resposta |
| --- | --- | --- |
| Organização inexistente / não-membro / id malformado / path × header divergente | `TenantInterceptor` | `404` genérico |
| Projeto inexistente ou de **outra** organização | `ServiceService.findProjectInTenant` | `404` genérico |
| Serviço inexistente ou pertencente a **outro** projeto | `ServiceService.findService` | `404` genérico |
| `projectId`/`serviceId` malformado | `ServiceController.parseId` | `404` genérico (nunca `500`) |

`ServiceIdorAdversarialTest` cobre a matriz sobre o cenário canônico **Alpha** (Alice=`ADMIN`, Bob=`OPERATOR`) × **Beta** (Bob=`ADMIN`, Alice=`VIEWER`) × **Charlie** (sem membership): `serviceId` Beta alcançado pelo path de Alpha, `projectId` Beta dentro do path de Alpha, serviço Alpha alcançado pelo projeto Beta, id aleatório/malformado, `VIEWER` lendo mas recebendo `403` em toda mutação, `404` em todos os endpoints sem token, o `403` de CSRF e a divergência path × header — **sempre sem escrita aplicada**.

### 5.3 Projeto inativo

| Operação | Projeto ativo | Projeto inativo |
| --- | --- | --- |
| `GET` lista / `GET` detalhe | `200` | **`200`** (o catálogo continua consultável) |
| `POST` / `PATCH` / `PATCH .../activation` | segue a matriz | **`409 PROJECT_INACTIVE`** |

Mensagem: `"Cannot create or modify services in an inactive project"`. A checagem acontece **depois** do `404` de projeto fora do tenant e **antes** da validação de payload (§3) — desativar um projeto congela o catálogo sem apagá-lo.

### 5.4 Mass assignment

Os DTOs são **fechados**: `organizationId`, `projectId`, `id`, `active`, `status` e timestamps não existem nos records de entrada. O escopo vem do path (interceptor + lookup), os atributos de servidor são definidos pelo construtor de domínio, e `active` tem rota própria. Campos desconhecidos no JSON são **ignorados pelo parser** (comportamento default do Jackson no Spring Boot — confirmado empiricamente por teste, não por suposição): enviar `"status": "OPERATIONAL"` ou `"active": false` no `POST` não muda nada — o serviço nasce `active=true` + `status=UNKNOWN`, e o `id`/`createdAt` gerados são os do servidor.

## 6. Contrato de erro

Shape padrão da API (o mesmo do [identity.md](identity.md)):

```json
{ "code": "SERVICE_NAME_DUPLICATE",
  "message": "Service with this name already exists in this project",
  "timestamp": "2026-09-27T…Z",
  "path": "/api/v1/organizations/…/services" }
```

| Código | HTTP | Quando |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | `name` em branco/>120; `description` >1000; `page` negativo; `size` fora de 1..100; `sort` fora da allowlist; `active` ausente/nulo; `status` fora do enum; tipo errado em query param (`"Invalid request parameter"`) e corpo malformado (`"Malformed request body"`) |
| `UNAUTHORIZED` | 401 | Sem/inválido Bearer |
| `FORBIDDEN` | 403 | CSRF ausente/inválido (requisições sem Bearer) **ou** papel insuficiente para mutação (`"Access denied"`) |
| `NOT_FOUND` | 404 | Ver §5.2 (sempre `"Resource not found"`) |
| `METHOD_NOT_ALLOWED` | 405 | `DELETE`/`PUT` em rota existente (`"Method DELETE not allowed"`) |
| `SERVICE_NAME_DUPLICATE` | 409 | Nome já usado **no mesmo projeto** (criação ou rename, inclusive na corrida) |
| `PROJECT_INACTIVE` | 409 | Mutação em serviço de projeto desativado |
| `INTERNAL_ERROR` | 500 | Erros inesperados (catch-all, sem vazar internals) |

## 7. CSRF e autenticação

- Toda escrita segue o contrato double-submit da API (cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`); nenhum teste usa `.with(csrf())` — o `CsrfFilter` real é o que está sob teste.
- **Nota de framework (comportamento intencional do Spring Security):** requisições que trazem `Authorization: Bearer …` são **isentas** da proteção CSRF — o bearer não é anexado automaticamente pelo navegador, logo não há superfície de CSRF. O double-submit continua obrigatório para requisições autenticadas por cookie/sessão. Cobre dois testes: `writeWithoutCsrfTokenGets403` (sem Bearer → `403` antes mesmo da autenticação) e `authenticatedBearerWriteIsCsrfExemptByFramework` (com Bearer → a escrita procede).

## 8. Estrutura de código (backend)

```
com.sentinel
└── project
    ├── model
    │   ├── Project.java                # inalterado no Prompt 06 (Prompt 07 não o modifica)
    │   ├── Service.java                # entidade `services` (rename() recalcula nameNormalized)
    │   └── ServiceStatus.java          # enum {OPERATIONAL, DEGRADED, UNAVAILABLE, UNKNOWN}
    ├── repository
    │   ├── ProjectRepository.java      # inalterado
    │   └── ServiceRepository.java      # 2 lookups tenant-scoped + 2 existsBy + 8 listagens
    ├── dto
    │   ├── ServiceCreateRequest.java   # {name, description?} — @NotBlank + @Size explícitos
    │   ├── ServiceUpdateRequest.java   # {name?, description?} — sem @Valid: ausente ≠ em branco
    │   ├── ServiceActivationRequest.java # {@NotNull Boolean active}
    │   └── ServiceResponse.java        # {id,organizationId,projectId,name,description,active,status,createdAt,updatedAt,role}
    ├── service
    │   ├── ProjectService.java         # inalterado
    │   └── ServiceService.java         # RBAC, projeto inativo, validações, unicidade em 2 camadas, paginação
    └── web
        ├── ProjectController.java      # inalterado
        └── ServiceController.java      # .../projects/{projectId}/services (@TenantScoped)
```

- O pacote **não** cria configuração nova: não há filtro, interceptor, handler global nem bean de segurança próprios — o módulo se apoia em `TenantInterceptor`/`TenantContext` (Prompt 04) e na cadeia de segurança do Prompt 03.
- **Conflito de nome `Service` × `@Service`:** a entidade e o estereótipo do Spring colidem. Como Java não tem alias de import, o arranjo é: entidade importada normalmente (aparece em várias assinaturas) e estereótipo escrito **uma única vez** com FQN — `@org.springframework.stereotype.Service` na declaração de `ServiceService`. Nenhum outro arquivo do módulo precisa de FQN.
- `ServiceUpdateRequest` é validado **no service** (não com `@Valid`) porque só o service sabe distinguir "campo ausente" de "campo em branco".
- Os tamanhos das mensagens de `@Size` são **explícitos** (mesmo critério de `ProjectCreateRequest`).
- A ordem das checagens (`requireMutationRole()` → projeto → ativo → payload) fica no service, e não no controller — é uma regra de domínio, não de rota.

## 9. Testes

- `mvn clean test` — **257 testes, 0 falhas, exit 0** (188 do baseline + **69** novos); `mvn -DskipTests package` — exit 0.
- **Nenhuma classe de teste existente foi alterada**: a base nova `AbstractServiceIntegrationTest` estende `AbstractProjectIntegrationTest` e só acrescenta o vocabulário de serviço (URLs, `createService`, `changeActivation` e o `serviceRepository`).
- **Novas classes** (todas herdam `AbstractServiceIntegrationTest`, contexto próprio por `PostgreSQLContainer`):
  - `ServiceIntegrationTest` (46): contrato do `201` (sem campos internos, `nameNormalized` fora do payload), trim e validações (`400` com mensagem exata para nome/descrição/paginação/`sort`), `401`, unicidade nas duas camadas (duplicata, duplicata com caixa/espaços circundantes, duplicata de serviço desativado, mesmo nome em projetos diferentes), **mass assignment** (payload com `id`/`projectId`/`active`/`status`/timestamps ignorado), `404` (inexistente/malformado/outro projeto), listagem (shape estável, paginação, filtros `active`/`status`/`name` e combinações, ordenação, escopo do tenant), `PATCH` (rename com novo `nameNormalized`, campos ausentes, limpar descrição, `400`, `409`, rename idempotente), ativação, `405` de `DELETE`/`PUT`, projeto inativo (leitura `200` × mutação `409`), **ordem das checagens** (role → projeto → ativo → payload), RBAC (`VIEWER` 403, `OPERATOR` pode mutar) e **stale auth** (mesmo token reflete mudança de papel sem refresh).
  - `ServiceIdorAdversarialTest` (13): cenário Alpha/Beta/Charlie nos **dois** níveis (serviceId cross-tenant, projectId de outro tenant dentro do path próprio, serviço próprio via projeto alheio), id aleatório/malformado, não-membro `404` em tudo sem escrita, `VIEWER` lê mas recebe `403` em toda mutação, `OPERATOR` pode mutar, `401` sem token, CSRF (`403` sem Bearer × isenção com Bearer), path × header divergente e `organizationId`/`projectId` malformados.
  - `ServiceActivationIdempotencyTest` (7): round-trip persistido no banco, repetição do estado atual **sem** avançar `updatedAt`, ativação que **não** toca `status`/nome/descrição/`createdAt`, `400` com `active` ausente/nulo, corpo malformado, `404` genérico para id desconhecido e id malformado.
  - `ServiceConcurrencyTest` (3): criação simultânea do mesmo nome → um `201` + um `409`; rename simultâneo para o mesmo destino → um `200` + um `409`; mesmo nome em dois projetos em paralelo → dois `201`.
- Os testes usam CSRF **real** (cookie + header, nenhum `.with(csrf())`) e os helpers de identidade/tenant dos prompts anteriores.

## 10. Fora do escopo (prompts futuros)

- Coleta de dados de saúde dos serviços, regras de alerta e dashboards — prompts futuros. O **ciclo de vida de incidentes** já está construído no Prompt 14 como sub-recurso do serviço (ver [incidents.md](incidents.md)).
- Remoção definitiva de serviço (hoje a rota `DELETE` devolve `405`; o soft-delete é o `.../activation`).
- Alteração de `status` por endpoint (nenhum existe; a transição entrará como caminho de domínio explícito).
- Tags/metadados livres, busca textual além da subcadeia sobre `name_normalized` (ex.: `pg_trgm`) — só se houver requisito real.
- Trilha de auditoria de criação/edição/desativação por tenant.
