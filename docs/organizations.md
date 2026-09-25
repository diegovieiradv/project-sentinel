# Organizations (multi-tenancy) — Sentinel

> **Prompt 04 de 18.** Este documento descreve **o que está construído**: organizações (tenants), memberships com papéis por organização (`ADMIN` / `OPERATOR` / `VIEWER`), resolução de contexto de tenant (`TenantContext` + `TenantInterceptor` + `@TenantScoped`), regra do último ADMIN e isolamento cross-tenant. A base de autenticação (Bearer + CSRF) é a do [identity.md](identity.md).

## 1. Escopo implementado

| Item | Estado |
| --- | --- |
| Migration `V4__create_organizations_and_memberships.sql` (tabelas `organizations`, `memberships`) | Construído |
| Entidades `Organization`, `Membership` e enum `Role` (`ADMIN`/`OPERATOR`/`VIEWER`) | Construído |
| `POST /api/v1/organizations` (cria org + membership `ADMIN` do criador na mesma transação) | Construído |
| `GET /api/v1/organizations` (apenas as organizações do usuário) | Construído |
| `GET /api/v1/organizations/{organizationId}` (detalhe + `role` do usuário) | Construído |
| `GET/POST/PATCH/DELETE .../members` (gestão de membros, admin-only) | Construído |
| `TenantContext` (ThreadLocal) + `TenantInterceptor` + `@TenantScoped` + `TenantWebConfig` | Construído |
| Regra do último ADMIN → `409 LAST_ADMIN_REQUIRED` sob lock pessimista (`SELECT ... FOR UPDATE`) | Construído |
| Isolamento cross-tenant → `404` genérico anti-enumeração | Construído |
| CORS com método `PATCH` e header `X-Organization-Id` | Construído |
| Frontend: listar/criar/selecionar organização + gerenciar membros | Construído |
| Vinculação de projetos/serviços/monitoramento à organização | **Fora do escopo** (prompts futuros) |

## 2. Modelo de dados

### 2.1 `Organization` (tabela `organizations`)

| Coluna | Tipo | Observação |
| --- | --- | --- |
| `id` | `UUID` PK | Gerado **pela aplicação** (`@PrePersist` + `UUID.randomUUID()`) — a migration não declara `DEFAULT` |
| `name` | `VARCHAR(128) NOT NULL` | `CHECK organizations_name_not_blank` (`trim(name) <> ''`) espelha o `@NotBlank` da API |
| `created_at` / `updated_at` | `TIMESTAMPTZ NOT NULL` | `updated_at` mantido por `@PreUpdate` (sem trigger) |

Sem índice em `name`: não há busca por nome neste prompt.

### 2.2 `Membership` (tabela `memberships`)

| Coluna | Tipo | Observação |
| --- | --- | --- |
| `id` | `UUID` PK | Gerado pela aplicação |
| `user_id` | `UUID NOT NULL` → `users(id)` | `ON DELETE CASCADE` |
| `organization_id` | `UUID NOT NULL` → `organizations(id)` | `ON DELETE CASCADE` |
| `role` | `VARCHAR(32) NOT NULL` | `CHECK memberships_role_check` em `('ADMIN','OPERATOR','VIEWER')` |
| `created_at` / `updated_at` | `TIMESTAMPTZ NOT NULL` | |

Constraints e índices:

- **`UNIQUE (user_id, organization_id)`** (`memberships_user_org_unique`): no máximo **um** papel por usuário por organização — é o que transforma a corrida de duplicidade em `409` na aplicação.
- **`idx_memberships_user_id`** — caminho da listagem "minhas organizações".
- **`idx_memberships_organization_id`** — caminho de toda leitura por tenant (lista de membros e o lock de último ADMIN).

As associações `user` e `organization` são **`EAGER` de propósito** (mesmo racional do `RefreshToken`): o `TenantInterceptor` resolve a membership **fora** de transação e um proxy `LAZY` explodiria em `LazyInitializationException`.

### 2.3 Papéis (`Role`)

| Papel | Escopo | Uso |
| --- | --- | --- |
| `ADMIN` | organização | Gestão da organização e dos membros; **obrigatório pelo menos um** por organização |
| `OPERATOR` | organização | Operação dos recursos do tenant (sem gestão de membros) |
| `VIEWER` | organização | Somente leitura |

- O papel é **sempre escopo-tenant**: o mesmo usuário pode ser `ADMIN` numa organização e `VIEWER` noutra.
- Por isso o papel **nunca** mora em claim de JWT (claims continuam mínimas: `sub`, `iat`, `exp`) — é lido do banco, na resolução do tenant, pelo servidor.

## 3. Tenant Context

### 3.1 Peças

| Peça | Papel |
| --- | --- |
| `TenantContext` (`context/`) | `ThreadLocal` com o registro imutável `Context(user, organization, membership, role)` da requisição corrente. Por que `ThreadLocal` e não um bean singleton: o contexto varia por requisição — um campo mutável em singleton seria compartilhado entre requisições concorrentes (vazamento cross-tenant). |
| `@TenantScoped` (`web/`) | Marcador de **método ou classe** que exige tenant resolvido **antes** do handler. |
| `TenantInterceptor` (`web/`) | `HandlerInterceptor` que detecta o marcador, resolve a organização, valida a membership e popula o contexto. |
| `TenantWebConfig` (`web/`) | `@Configuration` **novo e separado** do `SecurityConfig` (que cuida de autenticação/CORS e não participa da resolução de tenant): registra o interceptor na pilha MVC. |

Ordem correta: a cadeia Spring Security autentica primeiro; o `DispatcherServlet` só então executa `preHandle` com o principal já resolvido (a entidade `User`).

### 3.2 Fluxo do `preHandle`

1. Ignora handlers sem `@TenantScoped` (custo: uma checagem de anotação).
2. Extrai a organização-alvo: **path `organizationId`** se existir, senão **header `X-Organization-Id`**.
   - Nenhuma fonte → `400 TENANT_REQUIRED`.
   - Valor malformado → tratado como organização inexistente (`404`).
   - Path e header presentes e **divergentes** → `404` (possível confusão cross-tenant; a resposta não revela qual dos dois existe).
3. Valida `Membership(user, organization)` no banco. Organização inexistente **e** usuário sem membership respondem o **mesmo** `404` `"Resource not found"` — sem enumeração de tenants.
4. Popula o `TenantContext` da thread.

### 3.3 Limpeza do contexto (sempre)

- `afterCompletion` limpa **incondicionalmente** — toda requisição devolve a thread limpa.
- Se o `preHandle` lançar no meio, o `DispatcherServlet` **não** chama `afterCompletion` para quem lançou: o `catch` limpa o contexto **antes** de propagar a exceção. Erros (`Error`/`RuntimeException`) têm o mesmo tratamento.
- `TenantContext.require()` com contexto nulo (uso fora de um handler `@TenantScoped`) vira `400 TENANT_REQUIRED` em vez de NPE.

> `TenantInterceptor` usa `ObjectProvider<MembershipRepository>`: o slice `@WebMvcTest` (ex.: `HealthControllerTest`) varre interceptors mas **não** carrega JPA — a resolução só acontece no primeiro handler `@TenantScoped`.

### 3.4 Extensão para futuros módulos

Basta anotar o handler com `@TenantScoped` (organização no path ou no header) e ler `TenantContext.require()` / `TenantContext.role()` no service — nada mais muda. O `MultiTenantScenarioTest` cobre exatamente esse caso com um `ProbeController` em `/api/v1/probe/projects` (handler `@TenantScoped` **sem** `organizationId` no path), que é o único caminho em que o `400 TENANT_REQUIRED` de fato acontece hoje.

## 4. Endpoints

Base: `http://localhost:8080`. Todos exigem `Authorization: Bearer <accessToken>`; os métodos não-safe exigem também CSRF double-submit (cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`).

### 4.1 Matriz de acesso

| Método | Path | `@TenantScoped` | Papel mínimo | CSRF |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/organizations` | não | qualquer usuário autenticado (o criador recebe `ADMIN`) | sim |
| GET | `/api/v1/organizations` | não | — (lista só as memberships do próprio usuário) | não |
| GET | `/api/v1/organizations/{organizationId}` | **sim** | qualquer papel (membro) | não |
| GET | `/api/v1/organizations/{organizationId}/members` | **sim** | `ADMIN` | não |
| POST | `/api/v1/organizations/{organizationId}/members` | **sim** | `ADMIN` | sim |
| PATCH | `/api/v1/organizations/{organizationId}/members/{membershipId}` | **sim** | `ADMIN` | sim |
| DELETE | `/api/v1/organizations/{organizationId}/members/{membershipId}` | **sim** | `ADMIN` | sim |

Ordem das checagens: **CSRF** (filtro, `403`) → **autenticação** (`401`) → **membership** no interceptor (`404`) → **papel** no service (`403`) → regras do payload (`400`/`404`/`409`).

### 4.2 `POST /api/v1/organizations` → `201`

```json
{ "name": "Acme" }
```

```json
{ "id": "uuid", "name": "Acme",
  "createdAt": "2026-09-25T…Z", "updatedAt": "2026-09-25T…Z",
  "role": "ADMIN" }
```

- Cria a organização **e** a membership `ADMIN` do criador na **mesma transação** — não existe cenário de organização sem ADMIN.
- `name`: `@NotBlank` + `@Size(max=128)` (o service também trimma e rejeita vazio) → `400 VALIDATION_ERROR`.
- `role` na resposta é sempre `"ADMIN"`: é o papel de quem está respondendo, nunca uma visão neutra da organização.
- Sem `Bearer` → `401 UNAUTHORIZED` (com CSRF presente; sem CSRF o filtro responde `403` antes).

### 4.3 `GET /api/v1/organizations` → `200`

```json
[ { "id": "uuid", "name": "Acme", "createdAt": "…", "updatedAt": "…", "role": "ADMIN" } ]
```

- Lista **apenas** as organizações em que o usuário é membro — a query parte das `memberships` do usuário (`findAllByUserIdWithOrganization`), ordenada pela criação da organização. "Ver tudo o que não é seu" é impossível por construção, não por filtro.
- Não existe endpoint "todas as organizações".
- Sem `Bearer` → `401`.

### 4.4 `GET /api/v1/organizations/{organizationId}` → `200` | `404`

- Membro → `200` com o mesmo shape do `201`, incluindo `role` do usuário **nesta** organização.
- Não-membro, organização inexistente ou `organizationId` malformado → **`404`** `{"code":"NOT_FOUND","message":"Resource not found",…}` (o mesmo corpo nos três casos).
- Sem `Bearer` → `401`.

### 4.5 `GET /api/v1/organizations/{organizationId}/members` → `200` | `403` | `404`

```json
[ { "membershipId": "uuid", "userId": "uuid", "name": "Ada Lovelace",
    "email": "ada@example.com", "role": "VIEWER",
    "createdAt": "2026-09-25T…Z" } ]
```

- **Admin-only**: é o único payload da API que expõe e-mails de outros usuários — por isso `VIEWER`/`OPERATOR` recebem `403`.
- Não-membro (mesmo sendo membro de **outra** organização) → `404`.
- Nunca inclui senha/hash/token.
- Ordenação: `createdAt` da membership asc, depois `id` (ordem estável).

### 4.6 `POST /api/v1/organizations/{organizationId}/members` → `201`

```json
{ "email": "ada@example.com", "role": "VIEWER" }
```

```json
{ "membershipId": "uuid", "userId": "uuid", "name": "Ada Lovelace",
  "email": "ada@example.com", "role": "VIEWER", "createdAt": "…" }
```

- `email`: `@NotBlank` + `@Email` + `@Size(max=255)`; normalizado `trim` + `toLowerCase` antes da busca (mesma normalização do registro em identity).
- `role`: **obrigatório e explícito** (`@NotBlank` + `@Size(max=32)`) — sem default implícito de `VIEWER`. Aceito com caixa mista (`admin` → `ADMIN`).
- Usuário **não cadastrado** → `404` genérico (a resposta é a mesma de organização inexistente: não revela se a conta existe).
- Já é membro → `409 MEMBERSHIP_ALREADY_EXISTS` (inclusive na corrida, via `UNIQUE (user_id, organization_id)`).
- Não-ADMIN → `403`; não-membro → `404`.

### 4.7 `PATCH /api/v1/organizations/{organizationId}/members/{membershipId}` → `200`

```json
{ "role": "OPERATOR" }
```

```json
{ "membershipId": "uuid", "userId": "uuid", "name": "Ada Lovelace",
  "email": "ada@example.com", "role": "OPERATOR", "createdAt": "…" }
```

- Rebaixar o **último** `ADMIN` → `409 LAST_ADMIN_REQUIRED`.
- Membership de outra organização ou inexistente → `404`; `membershipId` malformado → `404` (nunca `500`).
- Reatribuir um único ADMIN como `ADMIN` → `200` (no-op permitido).
- `role` em branco → `400` `"role: must not be blank"`; fora do domínio → `400` `"role: must be one of ADMIN, OPERATOR, VIEWER"`.

### 4.8 `DELETE /api/v1/organizations/{organizationId}/members/{membershipId}` → `204`

- Resposta **sem corpo**.
- Remover o último `ADMIN` (inclusive a si mesmo) → `409 LAST_ADMIN_REQUIRED`.
- Membership de outra organização/inexistente/malformado → `404`; não-ADMIN → `403`.

## 5. Regras de autorização e isolamento

### 5.1 Autorização no service, papel do servidor

Todo endpoint de `/members` chama `requireAdmin()` **antes** de qualquer outra checagem de dados: papel diferente de `ADMIN` → **`403 FORBIDDEN`** com `"Access denied"` (mesmo corpo para todos os papéis, sem detalhar a política).

O papel vem de `TenantContext.role()` — populado pelo interceptor a partir do banco, **nunca** de claim de JWT.

### 5.2 Último ADMIN (D5) com lock pessimista

`updateRole` e `removeMember`:

1. travam **todos** os memberships da organização com `SELECT ... FOR UPDATE` (`MembershipRepository.findAllByOrganizationIdForUpdate`, `@Lock(PESSIMISTIC_WRITE)`);
2. localizam o alvo **dentro** da lista travada (membership de outra organização não está lá → mesmo `404`);
3. contam os `ADMIN`s dentro do lock;
4. rebaixar/remover o único `ADMIN` → `409 LAST_ADMIN_REQUIRED` **dentro do lock**.

Operações concorrentes sobre o mesmo tenant são **serializadas** em vez de decidirem sobre uma contagem potencialmente obsoleta (o teste `concurrentDeleteOfSoleAdminNeverLeavesOrganizationWithoutAdmin` cobre isso).

### 5.3 Isolamento cross-tenant (anti-enumeração)

O **mesmo** `404` `{"code":"NOT_FOUND","message":"Resource not found"}` é devolvido para:

| Situação | Onde |
| --- | --- |
| Organização inexistente | `TenantInterceptor` |
| Organização existente mas usuário não é membro | `TenantInterceptor` |
| `organizationId` malformado (não é UUID) | `TenantInterceptor` |
| Path `organizationId` ≠ header `X-Organization-Id` | `TenantInterceptor` |
| `membershipId` malformado ou de outra organização | `MembershipService` |
| E-mail não cadastrado no `POST .../members` | `MembershipService` |

O teste `unknownAndForeignOrganizationsReturnTheSame404Body` compara `code` e `message` dos dois corpos e exige `"Resource not found"` nos dois — os campos `timestamp`/`path` não entram na comparação.

### 5.4 `TENANT_REQUIRED` (`400`)

Disparado quando um handler `@TenantScoped` não tem **nenhuma** fonte de organização (nem path nem header), ou quando um service lê `TenantContext.require()` fora de uma requisição tenant-scoped. **Nenhum endpoint do Prompt 04 cai nisso** (todos trazem `organizationId` no path) — o código existe para os futuros módulos e é coberto pelo `ProbeController` do `MultiTenantScenarioTest`.

## 6. Contrato de erro

Shape padrão da API (o mesmo do [identity.md](identity.md)):

```json
{ "code": "LAST_ADMIN_REQUIRED", "message": "Organization must keep at least one admin",
  "timestamp": "2026-09-25T…Z", "path": "/api/v1/organizations/…/members/…" }
```

| Código | HTTP | Quando |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | `name` em branco/>128; `role` em branco ou fora de `ADMIN/OPERATOR/VIEWER`; `email` inválido |
| `TENANT_REQUIRED` | 400 | Handler `@TenantScoped` sem path nem header (módulos futuros) |
| `UNAUTHORIZED` | 401 | Sem/inválido Bearer |
| `FORBIDDEN` | 403 | CSRF ausente/inválido **ou** papel ≠ `ADMIN` (`"Access denied"`) |
| `NOT_FOUND` | 404 | Ver §5.3 (sempre `"Resource not found"`) |
| `METHOD_NOT_ALLOWED` | 405 | Método HTTP inválido em rota existente — handler novo no Prompt 04; mensagem `"Method <MÉTODO> not allowed"` |
| `MEMBERSHIP_ALREADY_EXISTS` | 409 | Usuário já é membro da organização |
| `LAST_ADMIN_REQUIRED` | 409 | Tentativa de rebaixar/remover o último `ADMIN` |
| `INTERNAL_ERROR` | 500 | Erros inesperados (catch-all, sem vazar internals) |

> O handler de `405` é **global** (não específico de organizações): sem ele, `HttpRequestMethodNotSupportedException` caía no catch-all e virava `500`.

## 7. CORS e configuração

- **Nenhuma variável nova** no Prompt 04: as origens continuam em `app.cors.allowed-origins` (env `CORS_ALLOWED_ORIGINS`, default `http://localhost:3000`), única fonte em `SecurityConfig.corsConfigurationSource`.
- Mudanças de **métodos** e **headers** (necessárias para o frontend):

| | Antes (Prompt 03) | Agora (Prompt 04) |
| --- | --- | --- |
| Métodos | `GET, POST, PUT, DELETE, OPTIONS` | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| Headers | `Content-Type`, `Authorization`, `X-XSRF-TOKEN`, `Accept` | `Content-Type`, `Authorization`, `X-XSRF-TOKEN`, **`X-Organization-Id`**, `Accept` |

- `PATCH` é o método do endpoint `PATCH .../members/{membershipId}` (troca de papel) — sem ele na lista, o preflight do navegador bloquearia a chamada.
- `X-Organization-Id` é a fonte de tenant para handlers `@TenantScoped` **sem** organização no path (futuros módulos); o preflight precisa anunciá-lo.
- `allowCredentials(true)` + origem exata permanecem (nunca `*`).
- Preflight coberto por teste: `preflightAdmitsPatchAndOrganizationIdHeaderForTenantModule`.

## 8. Frontend

Arquivos: `frontend/src/app/page.tsx`, `frontend/src/lib/api.ts`, `frontend/src/components/AuthStatus.tsx`, `frontend/src/app/globals.css`.

| Funcionalidade | Onde |
| --- | --- |
| Tipos `OrganizationRole`, `OrganizationResponse`, `MemberResponse` | `lib/api.ts` (espelham os DTOs do backend) |
| `listOrganizations / createOrganization / getOrganization` | `lib/api.ts` |
| `listMembers / addMember / updateMemberRole / removeMember` | `lib/api.ts` |
| Org ativa: UUID em memória + `localStorage` chave `sentinel.activeOrganizationId` | `lib/api.ts` (`getOrganizationId/setOrganizationId/clearOrganizationId`) |
| Header `X-Organization-Id` injetado **apenas** quando o path não carrega a organização | `lib/api.ts` (`apiFetch`) — evita o `404` de path/header divergente; `GET/POST /organizations` não são `@TenantScoped` e o ignoram |
| Lista de organizações + criação + seleção + indicador da org ativa | `page.tsx` (`OrganizationSection`) |
| Painel de membros (adicionar, alterar papel, remover) — visível/operável só para `ADMIN` | `page.tsx` (`MembersPanel`) |
| Gate de sessão: a carga de `/organizations` só rola depois do estado `authed` | `page.tsx` (`onAuthStateChange` vindo de `AuthStatus`) — evita corrida com o bootstrap do refresh (que cairia em `401`) |
| `401` em qualquer chamada → limpa token **e** org ativa (memória + `localStorage`) | `AuthStatus` + evento `SESSION_EXPIRED_EVENT` — a próxima conta não herda a org da anterior |
| Org ativa persistida só vale se pertencer à lista do usuário atual | `page.tsx` (UUID de outra conta ou de org da qual saiu é descartado) |

Erros exibidos no formulário de membros: `MEMBERSHIP_ALREADY_EXISTS` → "This user is already a member of the organization."; `404` → "No Sentinel account found for that email."; `403` → "Only administrators can manage members.".

## 9. Estrutura de código (backend)

```
com.sentinel
└── organization
    ├── context
    │   └── TenantContext.java          # ThreadLocal + record Context + require()/role()
    ├── model
    │   ├── Organization.java           # entidade `organizations`
    │   ├── Membership.java             # entidade `memberships` (EAGER)
    │   └── Role.java                   # ADMIN / OPERATOR / VIEWER
    ├── repository
    │   ├── OrganizationRepository.java
    │   └── MembershipRepository.java   # + findAllByOrganizationIdForUpdate (FOR UPDATE)
    ├── dto
    │   ├── OrganizationCreateRequest.java   # {name}
    │   ├── OrganizationResponse.java        # {id,name,createdAt,updatedAt,role}
    │   ├── AddMemberRequest.java            # {email, role}
    │   ├── UpdateMemberRoleRequest.java     # {role}
    │   └── MemberResponse.java              # {membershipId,userId,name,email,role,createdAt}
    ├── service
    │   ├── OrganizationService.java    # criação atômica, listagem por membership
    │   └── MembershipService.java      # admin-only, parseRole, último ADMIN
    └── web
        ├── OrganizationController.java          # /api/v1/organizations
        ├── OrganizationMemberController.java    # /api/v1/organizations/{id}/members
        ├── TenantScoped.java                    # marcador
        ├── TenantInterceptor.java               # resolve + popula + limpa
        └── TenantWebConfig.java                 # registra o interceptor (não mexe no SecurityConfig)
```

- `GlobalExceptionHandler` ganhou o handler de `405` (Prompt 04, D7).
- `SecurityConfig` só mudou em **CORS** (método `PATCH` + header `X-Organization-Id`).
- `parseRole`/`normalizeEmail` vivem no `MembershipService` porque `UserService.normalizeEmail` é `package-private` no pacote `identity` e o escopo do Prompt 04 não permitiu alterá-lo.

## 10. Testes

- `mvn test` — **100 testes, 0 falhas, exit 0** (execução confirmada no Prompt 04). Classes novas:
  - `OrganizationIntegrationTest` (12): criação `201` + membership `ADMIN` na mesma transação, nome em branco/>128 → `400`, `401` sem token, listagem **por usuário**, detalhe com `role`, `404` genérico para não-membro, `404` para id malformado, e três casos de `405` (`DELETE` na coleção e no detalhe, `PUT` no detalhe).
  - `MembershipIntegrationTest` (24): adicionar (`201`, `404` e-mail desconhecido, `400` role inválido/em branco, `409` duplicado), listar (admin vê tudo sem hashes; `403` para `VIEWER`; `401`; `404` para não-membro), trocar papel (`200`, `400`, `404` cross-tenant, `409 LAST_ADMIN_REQUIRED`), remover (`204`, `409`, `404` id malformado), `403` para `OPERATOR` nas quatro rotas de `/members` e para `VIEWER` nas rotas de escrita, `200` ao rebaixar um segundo ADMIN, remoção/troca cross-tenant → `404`, e **dois testes de concorrência** (delete duplo serializado; delete do último ADMIN nunca deixa a organização sem admin).
  - `MultiTenantScenarioTest` (8): resolução via header com `ProbeController`, `400 TENANT_REQUIRED` sem fonte de tenant, `401` antes da resolução, `404` idêntico para org desconhecida × estrangeira, header malformado → `404` (não `500`), path ≠ header → `404`, path sozinho basta, e limpeza do `ThreadLocal` após sucesso **e** após falha.
- Impacto nas classes pré-existentes: `AuthIntegrationTest` 31 → **32** (preflight `PATCH` + `X-Organization-Id`) e `GlobalExceptionHandlerTest` 5 → **6** (`405`).
- Todas as classes `@SpringBootTest` seguem com contexto próprio por `PostgreSQLContainer` (Testcontainers); as de organização herdam os helpers de `AbstractTenantIntegrationTest` (CSRF **real** via cookie/header — nenhum teste usa `.with(csrf())`).
- Frontend: `npm run lint && npm run type-check && npm run build` — exit 0.

## 11. Fora do escopo (prompts futuros)

- Vincular recursos de domínio (projetos, serviços, monitors, alertas, incidentes) à organização usando o `TenantContext` já resolvido.
- Troca de organização com reautenticação/escopo de token (hoje o papel vem do banco por requisição, não do JWT).
- Trilha de auditoria de ações por tenant.
- Rotinas de manutenção de memberships: hoje eles só saem pela API (ou via `ON DELETE CASCADE` ao excluir usuário/organização) — não há expurgo ou reconciliação programada.
