# Projects — Sentinel

> **Prompt 06 de 18** — primeiro recurso de domínio do tenant: projetos de uma organização. Este documento descreve **o que está construído**: tabela `projects` (`V5`), módulo `com.sentinel.project` (entidade, repositório, DTOs, service e controller), paginação/filtros/ordenação, unicidade de nome por organização em duas camadas, ativação/desativação idempotente e a matriz de autorização sobre o contexto de tenant já resolvido. O contexto de tenant (membership, papéis e `404` anti-enumeração) é o do [organizations.md](organizations.md); a base de autenticação (Bearer + CSRF) é a do [identity.md](identity.md).

## 1. Escopo implementado

| Item | Estado |
| --- | --- |
| Migration `V5__create_projects_table.sql` (tabela `projects`) | Construído |
| Entidade `Project` + `ProjectRepository` (consultas escopadas por tenant) | Construído |
| `POST /api/v1/organizations/{organizationId}/projects` (criação, `ADMIN`/`OPERATOR`) | Construído |
| `GET .../projects` (página com filtros `active`/`name` e ordenação por allowlist) | Construído |
| `GET .../projects/{projectId}` (detalhe + `role` do usuário) | Construído |
| `PATCH .../projects/{projectId}` (atualização parcial: `name?`, `description?`) | Construído |
| `PATCH .../projects/{projectId}/status` (ativação/desativação idempotente) | Construído |
| Unicidade de nome por organização em **duas camadas** (pré-check → `409`, `UNIQUE` → `409`) | Construído |
| Isolamento cross-tenant → `404` genérico anti-enumeração (mesmo corpo de id inexistente) | Construído |
| RBAC: leitura para qualquer papel, mutação para `ADMIN`/`OPERATOR` | Construído |
| Validações de entrada sem `500` (nome, descrição, paginação, `sort`, id malformado, corpo malformado) | Construído |
| `DELETE` da rota → `405` (remoção não existe neste prompt) | Construído |
| Serviços, monitors, alertas e incidentes | **Fora do escopo** (prompts futuros) |

## 2. Modelo de dados

### 2.1 `Project` (tabela `projects`)

| Coluna | Tipo | Observação |
| --- | --- | --- |
| `id` | `UUID` PK | Gerado **pela aplicação** (`@PrePersist` + `UUID.randomUUID()`) — a migration não declara `DEFAULT` (mesmo critério de `V4`; `ddl-auto=validate` não valida `DEFAULT`) |
| `organization_id` | `UUID NOT NULL` → `organizations(id)` | `ON DELETE CASCADE` — excluir a organização remove seus projetos |
| `name` | `VARCHAR(120) NOT NULL` | `CHECK projects_name_not_blank` (`trim(name) <> ''`) espelha a validação da API |
| `name_normalized` | `VARCHAR(120) NOT NULL` | cópia normalizada calculada **pela aplicação** (`trim` + `lowercase` com `Locale.ROOT`) |
| `description` | `VARCHAR(1000) NULL` | opcional; valor vazio é normalizado para `NULL` |
| `active` | `BOOLEAN NOT NULL DEFAULT true` | todo projeto nasce ativo; a desativação é **soft-delete** |
| `created_at` / `updated_at` | `TIMESTAMPTZ NOT NULL` | `updated_at` mantido por `@PreUpdate` (sem trigger) |

Constraints e índices:

- **`UNIQUE (organization_id, name_normalized)`** (`projects_org_name_normalized_unique`): o mesmo nome normalizado não pode existir **duas vezes na mesma organização** — ativo **ou** inativo (desativar não libera o nome). Organizações diferentes repetem o nome livremente.
- **Índice extra em `organization_id`: NÃO criado** — o índice único já tem `organization_id` como coluna de **prefixo** e cobre todas as leituras por tenant. (Diferente de `memberships`, cujo unique é `(user_id, organization_id)` e por isso precisa de `idx_memberships_organization_id`.)
- `name_normalized` é coluna **derivada mas materializada** de propósito: uma restrição `UNIQUE` não seria expressível sobre uma expressão normalizada por linha sem índice funcional, e a aplicação usaria `lower()` na query (função de coluna quebra o plano de índice).

### 2.2 Normalização do nome

`Project.normalizeName(name)` = `trim` + `lowercase(Locale.ROOT)`. É a **única** regra:

- `  Padded   ` → persiste `Padded`, unicidade decide sobre `padded`;
- `BACKEND WEB` e `backend web` colidem → `409`;
- **espaços internos são preservados**: `Frontend  Web` e `Frontend Web` são nomes diferentes (a normalização não colapsa espaços internos — decisão consciente, coberta por teste);
- a coluna exposta no payload é `name`; `nameNormalized` **nunca** sai na resposta (detalhe de implementação da unicidade, não informação de domínio).

## 3. Autorização e ordem das checagens

Ordem em todo endpoint do módulo:

1. **CSRF** (filtro, `403`) — só para requisições sem `Authorization: Bearer` (ver §7);
2. **autenticação** (`401`);
3. **membership** no `TenantInterceptor` (`@TenantScoped`, `404` genérico para não-membro/órgão inexistente/id malformado/path × header divergente);
4. **papel** no `ProjectService.requireMutationRole()` (`403 "Access denied"` para mutação sem `ADMIN`/`OPERATOR`);
5. **regras do payload** (`400` validação, `409` unicidade, `404` projeto fora do tenant).

| Ação | Papéis |
| --- | --- |
| Leitura (`GET` lista/detalhe) | **qualquer papel** (`ADMIN`, `OPERATOR`, `VIEWER`) — o interceptor já provou a membership |
| Mutação (`POST`, `PATCH`, `PATCH .../status`) | `ADMIN` ou `OPERATOR` |

O papel vem de `TenantContext.role()` — lido do banco **na requisição corrente**, nunca de claim de JWT. Todos os `403` de papel são idênticos (`"Access denied"`), sem detalhar a política.

## 4. Endpoints

Base: `/api/v1/organizations/{organizationId}/projects`. Todos são `@TenantScoped` (organização **sempre** no path; o DTO de criação nem tem campo de organização — impossível criar projeto "em outra org" por payload).

### 4.1 Matriz de acesso

| Método | Path | Papel mínimo | CSRF |
| --- | --- | --- | --- |
| POST | `.../projects` | `ADMIN` / `OPERATOR` | sim |
| GET | `.../projects` | qualquer papel | não |
| GET | `.../projects/{projectId}` | qualquer papel | não |
| PATCH | `.../projects/{projectId}` | `ADMIN` / `OPERATOR` | sim |
| PATCH | `.../projects/{projectId}/status` | `ADMIN` / `OPERATOR` | sim |

`PUT` e `DELETE` **não existem** nas rotas: caem no contrato global de `405 METHOD_NOT_ALLOWED` (a remoção de projeto não existe neste prompt — desativação é o `.../status`).

### 4.2 `POST .../projects` → `201`

```json
{ "name": "Backend API", "description": "Serviços de backend" }
```

```json
{ "id": "uuid", "organizationId": "uuid", "name": "Backend API",
  "description": "Serviços de backend", "active": true,
  "createdAt": "2026-09-27T…Z", "updatedAt": "2026-09-27T…Z", "role": "ADMIN" }
```

- Nasce `active=true`; `role` é o papel **de quem está respondendo** nesta organização (mesma semântica de `OrganizationResponse`).
- `name`: obrigatório (`@NotBlank`), trim, `≤ 120` → `400` `"name: must not be blank"` / `"name: must be at most 120 characters"`.
- `description`: opcional, trim, `≤ 1000`; vazia vira `null` → `400` `"description: must be at most 1000 characters"`.
- Nome já existente na organização → `409 PROJECT_NAME_DUPLICATE` (§5.1).
- `VIEWER` → `403`; não-membro → `404`; sem Bearer → `401`.

### 4.3 `GET .../projects` → `200`

```json
{ "content": [ { "id": "uuid", "organizationId": "uuid", "name": "Backend API",
    "description": null, "active": true, "createdAt": "…", "updatedAt": "…",
    "role": "ADMIN" } ],
  "totalElements": 3, "totalPages": 1, "number": 0, "size": 20 }
```

Parâmetros:

| Parâmetro | Default | Regra |
| --- | --- | --- |
| `page` | `0` | inteiro `≥ 0` → senão `400` `"page: must not be negative"` |
| `size` | `20` | `1..100` → senão `400` `"size: must be between 1 and 100"` |
| `sort` | `createdAt,asc` | campo por **allowlist** (`createdAt`, `name`, `id`) + direção `asc`/`desc` (ausente ou em branco → default) → senão `400` `"sort: must be one of createdAt, name, id"` |
| `active` | *(ausente = todos)* | `true` / `false` → `400` se não for booleano |
| `name` | *(ausente = sem filtro)* | busca por **subcadeia case-insensitive** sobre o nome normalizado (`?name=BACKEND` encontra "Backend API"); valor em branco = sem filtro |

- **Ordenação determinística**: `id asc` entra sempre como desempate (fixo no service) — o mesmo resultado mesmo com timestamps iguais; `id` já ordenado não leva desempate extra.
- O formato é a serialização **direta** de `Page` do Spring Data (modo default). Os campos garantidos por teste são `content`, `totalElements`, `totalPages`, `number` e `size` (o Spring Data emite um `WARN` único por página sugerindo `PagedModel`/`VIA_DTO` — o modo default foi mantido por ser o que o frontend já consome).
- Só os projetos do tenant corrente: a query parte de `findByOrganizationId*` com o id do `TenantContext` — "ver projetos de outra organização" é impossível por construção.

### 4.4 `GET .../projects/{projectId}` → `200` | `404`

- Membro (qualquer papel) → `200` com o mesmo shape do `201`.
- Projeto inexistente, de **outra** organização ou `projectId` malformado → **`404`** `{"code":"NOT_FOUND","message":"Resource not found"}` (o mesmo corpo nos três casos — anti-enumeração; o parse do UUID é manual no controller, nunca `400`/`500`).
- Sem `Bearer` → `401`.

### 4.5 `PATCH .../projects/{projectId}` → `200`

```json
{ "name": "Backend API v2" }
```

```json
{ "description": "Nova descrição" }
```

- Atualização **parcial**: campo **ausente** (`null`) não altera; `description: ""` **limpa** a descrição (vira `null`); `name: "   "` → `400` (a validação de nome do service cobre "presente porém em branco", que o `@Valid` do create não cobre no PATCH).
- Renomear recalcula `nameNormalized` e valida a unicidade contra **todos** os projetos do tenant, **exceto o próprio** → renomear para o próprio nome (ou só mudar a caixa) é `200` idempotente; para o nome de outro projeto → `409`.
- Nada das tentativas inválidas é aplicado: a validação acontece antes do `saveAndFlush`.
- `role`/`active`/`organizationId` não podem ser alterados por aqui (DTO fechado; `active` tem rota própria).

### 4.6 `PATCH .../projects/{projectId}/status` → `200`

```json
{ "active": false }
```

- **Idempotente**: ativar um projeto já ativo (ou desativar um já inativo) devolve `200` com o estado atual **sem escrita** — o `updatedAt` não avança (covierto por teste).
- `Boolean` (objeto) + `@NotNull`: body ausente, `{}` ou `{"active": null}` → `400` `"active: must not be null"` — o servidor nunca "adivinha" um `false` implícito.
- Desativar não apaga o nome da unicidade: criar outro projeto com o mesmo nome continua `409`.

### 4.7 `DELETE`/`PUT` → `405`

A remoção **não existe** neste prompt (nem handler, nem soft-delete implícito): `DELETE .../projects` e `DELETE .../projects/{projectId}` respondem `405 METHOD_NOT_ALLOWED` no shape padrão, assim como `PUT` na coleção.

## 5. Regras de domínio

### 5.1 Unicidade de nome em duas camadas

1. **Pré-check** (`existsByOrganizationIdAndNameNormalized[AndIdNot]`) → `409 PROJECT_NAME_DUPLICATE` imediato com mensagem amigável — é o caminho comum;
2. **`UNIQUE (organization_id, name_normalized)`** na migration: absorve a corrida entre o check e o `saveAndFlush`. A `DataIntegrityViolationException` é traduzida **no service** no **mesmo** `409` com a **mesma** mensagem — nunca um `500` nem mensagem crua de SQL.

A cobertura é por teste de concorrência (`CountDownLatch` + 2 threads): dois `POST` simultâneos do mesmo nome → exatamente **um** `201` e **um** `409`; dois `PATCH` renomeando para o mesmo destino → exatamente **um** `200` e **um** `409`. O mesmo nome em **organizações diferentes** → `201` para os dois.

### 5.2 Isolamento cross-tenant

O `TenantInterceptor` resolve e valida o tenant **antes** do handler; dentro do service toda leitura passa por `findByOrganizationIdAndId(tenant, id)`, escopada pelo id do `TenantContext`. Um projeto de outra organização responde o **mesmo** `404` genérico de id inexistente — sem revelar que existe em algum outro lugar.

| Situação | Onde | Resposta |
| --- | --- | --- |
| Organização inexistente / não-membro / id malformado / path × header divergente | `TenantInterceptor` | `404` genérico |
| Projeto inexistente | `ProjectService.findInTenant` | `404` genérico |
| Projeto de **outra** organização (alcançado pelo path próprio) | `ProjectService.findInTenant` | `404` genérico |
| `projectId` malformado | `ProjectController.parseProjectId` | `404` genérico (nunca `500`) |

`ProjectIdorAdversarialTest` cobre a matriz sobre o cenário canônico **Alpha** (Alice=`ADMIN`, Bob=`OPERATOR`) × **Beta** (Bob=`ADMIN`, Alice=`VIEWER`) × **Charlie** (sem membership): leitura e escrita cross-tenant sem escrita aplicada, `VIEWER` lendo mas recebendo `403` em toda mutação, `404` em todos os endpoints sem token e o `403` de CSRF.

## 6. Contrato de erro

Shape padrão da API (o mesmo do [identity.md](identity.md)):

```json
{ "code": "PROJECT_NAME_DUPLICATE",
  "message": "Project with this name already exists in this organization",
  "timestamp": "2026-09-27T…Z",
  "path": "/api/v1/organizations/…/projects" }
```

| Código | HTTP | Quando |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | `name` em branco/>120; `description` >1000; `page` negativo; `size` fora de 1..100; `sort` fora da allowlist; `active` ausente/nulo; tipo errado em query param (`"Invalid request parameter"`) e corpo malformado (`"Malformed request body"`) |
| `TENANT_REQUIRED` | 400 | (código do contexto de tenant; nenhum endpoint deste módulo cai nele — todos trazem `organizationId` no path) |
| `UNAUTHORIZED` | 401 | Sem/inválido Bearer |
| `FORBIDDEN` | 403 | CSRF ausente/inválido (requisições sem Bearer) **ou** papel insuficiente para mutação (`"Access denied"`) |
| `NOT_FOUND` | 404 | Ver §5.2 (sempre `"Resource not found"`) |
| `METHOD_NOT_ALLOWED` | 405 | `DELETE`/`PUT` em rota existente (`"Method DELETE not allowed"`) |
| `PROJECT_NAME_DUPLICATE` | 409 | Nome já usado na mesma organização (criação ou rename, inclusive na corrida) |
| `INTERNAL_ERROR` | 500 | Erros inesperados (catch-all, sem vazar internals) |

Os `400` de corpo malformado e de tipo inválido são handlers **globais** (`GlobalExceptionHandler`) — nenhum detalhe do parser/conversão vaza ao cliente.

## 7. CSRF e autenticação

- Toda escrita segue o contrato double-submit da API (cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`); nenhum teste usa `.with(csrf())` — o `CsrfFilter` real é o que está sob teste.
- **Nota de framework (comportamento intencional do Spring Security):** requisições que trazem `Authorization: Bearer …` são **isentas** da proteção CSRF (`OAuth2ResourceServerConfigurer$BearerTokenRequestMatcher` entra no matcher do `CsrfFilter`) — o bearer não é anexado automaticamente pelo navegador, logo não há superfície de CSRF. O double-submit continua obrigatório para requisições autenticadas por cookie/sessão. Cobre dois testes: `writeWithoutCsrfTokenGets403` (sem Bearer → `403` antes mesmo da autenticação) e `authenticatedBearerWriteIsCsrfExemptByFramework` (com Bearer → a escrita procede).

## 8. Estrutura de código (backend)

```
com.sentinel
└── project
    ├── model
    │   └── Project.java               # entidade `projects` (rename() recalcula nameNormalized)
    ├── repository
    │   └── ProjectRepository.java     # consultas escopadas por organizationId + existsBy/…AndIdNot
    ├── dto
    │   ├── ProjectCreateRequest.java  # {name, description?} — @NotBlank + @Size com mensagem explícita
    │   ├── ProjectUpdateRequest.java  # {name?, description?} — sem @Valid: ausente ≠ em branco
    │   ├── ProjectStatusRequest.java  # {@NotNull Boolean active}
    │   └── ProjectResponse.java       # {id,organizationId,name,description,active,createdAt,updatedAt,role}
    ├── service
    │   └── ProjectService.java        # RBAC, validações, unicidade em 2 camadas, paginação/allowlist
    └── web
        └── ProjectController.java     # /api/v1/organizations/{organizationId}/projects (@TenantScoped)
```

- O pacote **não** cria configuração nova: não há filtro, interceptor, handler global nem bean de segurança próprios — o módulo se apoia em `TenantInterceptor`/`TenantContext` (Prompt 04) e na cadeia de segurança do Prompt 03.
- `ProjectUpdateRequest` é validado **no service** (não com `@Valid`) porque só o service sabe distinguir "campo ausente" de "campo em branco".
- As mensagens dos `@Size` do create são **explícitas** (`"must be at most 120 characters"`) para o contrato não depender do texto default do Bean Validation (que também duplicaria mensagem no caso de `min=1` junto do `@NotBlank`).

## 9. Testes

- `mvn test` — **188 testes, 0 falhas, exit 0** (135 do Prompt 05 + **53** novos); `mvn -DskipTests package` — exit 0.
- **Novas classes** (todas herdam `AbstractProjectIntegrationTest` → `AbstractTenantIntegrationTest`, contexto próprio por `PostgreSQLContainer`):
  - `ProjectIntegrationTest` (35): contrato do `201` (sem campos internos, `nameNormalized` fora do payload), trim, validações (`400` com mensagem exata para nome/descrição/paginação/`sort`), `401`, unicidade nas duas camadas (duplicata, duplicata com caixa/espaços circundantes, duplicata de projeto desativado, mesmo nome em orgs diferentes), `404` (inexistente/malformado/outro tenant), listagem (shape estável, paginação, filtros `active`/`name`, ordenação, escopo do tenant), `PATCH` (rename com novo `nameNormalized`, campos ausentes, limpar descrição, `400`, `409`, rename para o próprio nome idempotente, cross-tenant, `401`) e `405` de `DELETE`/`PUT`.
  - `ProjectIdorAdversarialTest` (9): cenário Alpha/Beta/Charlie — não-membro `404` em tudo sem escrita, projeto de outro tenant `404`, `VIEWER` lê mas recebe `403` em toda mutação, `OPERATOR` pode mutar, `401` sem token, CSRF (`403` sem Bearer × isenção com Bearer), path × header divergente e `organizationId`/`projectId` malformados.
  - `ProjectStatusIdempotencyTest` (6): round-trip persistido no banco, repetição do estado atual **sem** avançar `updatedAt`, `400` com `active` ausente/nulo, corpo malformado, `404` genérico para id desconhecido e id malformado.
  - `ProjectConcurrencyTest` (3): criação simultânea do mesmo nome → um `201` + um `409`; rename simultâneo para o mesmo destino → um `200` + um `409`; mesmo nome em duas organizações em paralelo → dois `201`.
- Os testes usam CSRF **real** (cookie + header, nenhum `.with(csrf())`) e os helpers de identidade/tenant do Prompt 04 — nenhuma classe de teste antiga foi alterada, exceto `AbstractTenantIntegrationTest` tornada `public abstract` (hoje também estendida pelos testes de projeto).

## 10. Fora do escopo (prompts futuros)

- Serviços/monitors vinculados a projeto, coleta, alertas, incidentes e dashboards.
- Remoção definitiva de projeto (hoje a rota `DELETE` devolve `405`; o soft-delete é o `.../status`).
- Trilha de auditoria de criação/edição/desativação por tenant.
- Índices de busca textual além da subcadeia sobre `name_normalized` (ex.: `pg_trgm`) — só se houver requisito real de busca.
