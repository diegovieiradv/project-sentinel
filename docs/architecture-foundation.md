# Fundação da arquitetura — Sentinel

> Prompt 01 de 18 (atualizado nos **Prompt 02 — Identity**, **Prompt 03 — Identity stateless**, **Prompt 04 — Multi-tenancy**, **Prompt 05 — Tenant Security** e **Prompt 06 — Projects**). Este documento descreve **decisões e fronteiras planejadas**; apenas o que estiver indicado como "construído" existe hoje no código.

## 1. Decisões arquiteturais

| Decisão | Escolha | Justificativa |
| --- | --- | --- |
| Estilo do backend | **Monólito modular** (pacote único `com.sentinel` com fronteiras por domínio) | Time pequeno, domínio ainda em descoberta; evita custo de rede/deploy prematuro de microserviços, mantendo separação lógica para extrair serviços no futuro se houver necessidade real. |
| Frontend separado | **SPA Next.js ↔ API REST** em processos/portas distintos (3000 ↔ 8080) | Separação de ciclos de deploy e responsabilidades (UI vs domínio); comunicação HTTP JSON com CORS restrito ao localhost. |
| Banco de dados | **PostgreSQL 17** (único banco) | Robustez, conformidade com o padrão do projeto; multi-tenancy via dados compartilhados (coluna/relação de organização), não via múltiplos bancos ou schemas. |
| Infraestrutura via Docker | **Somente PostgreSQL** no Compose (apps rodam localmente) | Docker serve aqui como infra de dependência, não como runtime de app — reduz complexidade no Prompt 01. Backend mapeado fora da porta 5432 do host (usa **5433**). |
| Evolução de schema | **Flyway** (`classpath:db/migration`, `ddl-auto=validate`) | Migrations versionadas, reviewáveis e repetíveis; JPA nunca gera schema em runtime. |
| Evolução incremental | Fundação com health, erros padronizados, CORS, async e perfis dev/test/prod | Permite crescer por prompts (identity → organizations → …) sem reescrever a base nem introduzir microserviços prematuros. |

## 2. Fronteiras modulares (construídas × planejadas)

As fronteiras **identity**, **organizations** e **projects** já existem como código. As demais **não existem como código nem como pastas vazias** — são apenas o mapa previsto:

- **identity** — ⚠️ **parcialmente construída nos Prompt 02 e 03**: usuários, credenciais (BCrypt) e autenticação *stateless* (`register`/`login`/`refresh`/`logout`/`me`, access token JWT + refresh token em cookie, CSRF, CORS). Ainda **faltam**: permissões por recurso além dos papéis de organização.
- **organizations** — ✅ **construída no Prompt 04**: tenants da plataforma — `Organization`, `Membership`, papéis por organização (`ADMIN`/`OPERATOR`/`VIEWER`), gestão de membros e resolução de contexto de tenant (`TenantContext` + `TenantInterceptor` + `@TenantScoped`). Contrato em [organizations.md](organizations.md).
- **projects** — ✅ **construída no Prompt 06**: agrupamento lógico dentro de uma organização — tabela `projects` (migration `V5`), pacote `com.sentinel.project` (entidade, repositório, DTOs, service e controller), unicidade de nome por organização em duas camadas, ativação/desativação idempotente. Herda o contexto de tenant já resolvido (não recria filtro, interceptor nem regra de segurança). Contrato em [projects.md](projects.md). **Ambientes** e a vinculação de serviços ao projeto continuam **planejados**.
- **services** — catálogo dos serviços monitorados: cadastro, metadados, tags e associação a projetos.
- **monitoring** — coleta e estado de saúde dos serviços: checks, métricas, janelas de observação e sinais de falha.
- **alerting** — regras e decisões de alerta: condições, thresholds, supressões e disparo de notificações futuras.
- **incidents** — ciclo de vida de incidentes: abertura, triagem, atualização, resolução e histórico.
- **audit** — trilha de auditoria imutável de ações relevantes (quem fez o quê, quando, em qual tenant).

Fronteiras construídas e o caminho da requisição autenticada (o contexto de tenant é resolvido **uma** vez e só é lido nos services):

```mermaid
graph TB
    FE[Frontend Next.js] -->|Bearer + CSRF| BE[SecurityConfig<br/>JWT + CSRF + CORS]
    BE -->|/auth/** sem tenant| ID[identity<br/>✅ Prompt 02/03]
    BE -->|demais rotas| INTER[TenantInterceptor<br/>só em handlers @TenantScoped]
    INTER -->|popula| TC[TenantContext<br/>organizationId + role]
    INTER --> ORG[organization<br/>✅ Prompt 04/05]
    INTER --> PRJ[project<br/>✅ Prompt 06]
    ORG -->|papéis ADMIN/OPERATOR/VIEWER| TC
    PRJ -->|lê papel semântico| TC
    PRJ -.planejado.-> SVC[services]
    SVC -.planejado.-> MON[monitoring]
    MON -.planejado.-> ALT[alerting]
    MON -.planejado.-> INC[incidents]
```

## 3. Diagrama da fundação construída

```mermaid
graph LR
    FE[Frontend Next.js :3000] -->|HTTP + Bearer/cookie :8080| BE[Backend Spring Boot :8080]
    BE -->|JDBC :5433| PG[(PostgreSQL 17 :5433)]
```

O que existe hoje no backend:

- `GET /api/v1/health` — health técnico do serviço (também materializa o cookie `XSRF-TOKEN`).
- `GET /actuator/health` — health do Actuator (sem detalhes).
- Tratamento global de erros com shape `{code, message, timestamp, path}` (+ 404 padronizado para rota desconhecida; **405** para método não permitido em rota existente, Prompt 04; **400** genérico para corpo malformado e parâmetro de tipo inválido, Prompt 05).
- **Prompt 02**: `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /api/v1/auth/me` — BCrypt, tabela `users` (`V2`).
- **Prompt 03**: autenticação *stateless* — access token JWT HS256 (15 min, claims `sub`/`iat`/`exp`) validado pelo `oauth2ResourceServer`, refresh token opaco em cookie `HttpOnly + SameSite=Strict + Path=/api/v1/auth` (7 dias, só o SHA-256 persistido) com rotação atômica, revogação em caso de reuso e logout idempotente; CSRF double-submit (`XSRF-TOKEN`/`X-XSRF-TOKEN`); tabela `refresh_tokens` (`V3`).
- **Prompt 04**: multi-tenancy — `POST`/`GET /api/v1/organizations`, `GET /api/v1/organizations/{organizationId}` e gestão de membros em `/api/v1/organizations/{organizationId}/members` (`GET`/`POST`/`PATCH`/`DELETE`); `TenantContext` (ThreadLocal) populado pelo `TenantInterceptor` em handlers `@TenantScoped` (path `organizationId` ou header `X-Organization-Id`) e sempre limpo no fim da requisição; papéis `ADMIN`/`OPERATOR`/`VIEWER`, regra do último ADMIN sob lock pessimista, isolamento cross-tenant com 404 genérico; tabelas `organizations` e `memberships` (`V4`).
- **Prompt 05**: tenant security sem mudança de contrato — a autoridade é consultada no servidor **por requisição** (papel nunca em JWT: promoção/rebaixamento/remoção valem sem refresh), a matriz de ataques IDOR (`GET`/`POST`/`PATCH`/`DELETE` cross-tenant, `membershipId` de outro tenant ou aleatório, path × header divergente) responde o mesmo `404` genérico sem efeito colateral, a regra do último ADMIN é coberta sob concorrência (`PATCH × PATCH`, `PATCH × DELETE` e dois ADMINs em corrida), corpo/parâmetro inválido vira `400` genérico, origens CORS são validadas no startup e os headers de segurança padrão ficam verificados. **Sem migrations novas.**
- **Prompt 06**: primeiro recurso de domínio do tenant — `POST`/`GET /api/v1/organizations/{organizationId}/projects`, `GET`/`PATCH .../projects/{projectId}` e `PATCH .../projects/{projectId}/status` (leitura para qualquer papel, mutação para `ADMIN`/`OPERATOR` via `TenantContext.role()`), unicidade de nome por organização em **duas camadas** (pré-check → `409` + `UNIQUE (organization_id, name_normalized)` traduzido no mesmo `409`, nunca `500`), listagem paginada com filtros `active`/`name`, ordenação por allowlist (`createdAt`/`name`/`id`) com desempate por `id`, `DELETE` → `405`, tabela `projects` (`V5`) — contrato em [projects.md](projects.md).
- CORS liberado para `http://localhost:3000` com credenciais — **fonte única**: `SecurityConfig.corsConfigurationSource`, origens configuráveis via `app.cors.allowed-origins` (o antigo `WebMvcConfig` foi removido no Prompt 02); desde o Prompt 04 os métodos incluem `PATCH` e os headers incluem `X-Organization-Id`; desde o **Prompt 05** a lista é **validada no startup** (curinga com `allowCredentials=true` derruba a subida) e as respostas trazem `X-Content-Type-Options: nosniff` e `X-Frame-Options: DENY` (defaults do Spring Security).
- Bean assíncrono (`taskExecutor`) como base futura de processamento assíncrono.
- Flyway: `V1__init_schema.sql` (fundação) + `V2__create_users_table.sql` (identity) + `V3__create_refresh_tokens_table.sql` (refresh tokens) + `V4__create_organizations_and_memberships.sql` (multi-tenancy) + `V5__create_projects_table.sql` (projetos, Prompt 06) — **o Prompt 05 não acrescenta migrations**.

## 4. Configuração por ambientes

Perfil ativo por `SPRING_PROFILES_ACTIVE` (default `dev`):

| Perfil | Arquivo | Características |
| --- | --- | --- |
| `dev` | `application-dev.yml` | `logging.com.sentinel=DEBUG`, `spring.jpa.show-sql=true` |
| `test` | `application-test.yml` | `logging=INFO`, `show-sql=false`, **Flyway habilitado** (testes usam Testcontainers com PostgreSQL real) |
| `prod` | `application-prod.yml` | `logging=INFO`, `show-sql=false`, `flyway.validate-on-migrate=true`, **`JWT_SECRET` e `DATABASE_PASSWORD` obrigatórias** (fail-fast), `jwt.cookie.secure=true` |

Base comum (`application.yml`): porta `${SERVER_PORT:8080}`, datasource `jdbc:postgresql://localhost:5433/sentinel`, `ddl-auto=validate`, `open-in-view=false`, actuator expondo apenas `health` e `info` com `show-details: never`, bloco `jwt.*` e `app.cors.allowed-origins`.

Segredos nunca ficam no código: usa-se `.env` (gitignored) a partir de `.env.example` (placeholders). O `jwt.secret` não é logado; em `dev`/`test` vazio o backend gera um segredo efêmero no startup (WARNING no log — reiniciar invalida os tokens emitidos).

## 5. Integração Frontend ↔ Backend

- O frontend consome a API através de `NEXT_PUBLIC_API_URL` (default `http://localhost:8080`).
- Técnicas de smoke: `GET /api/v1/health` (também devolve o cookie `XSRF-TOKEN` que o frontend usa no double-submit).
- **Backend (Prompt 03)**: `register`, `login`, `refresh`, `logout` públicos com CSRF; `me` exige `Authorization: Bearer <accessToken>`. O refresh viaja em cookie `HttpOnly + Path=/api/v1/auth`.
- **Frontend (Prompt 03)**: adaptado ao contrato stateless — access token em memória com header `Authorization: Bearer`, refresh token em cookie `HttpOnly` (requisições com `credentials: "include"`), CSRF via header `X-XSRF-TOKEN` e bootstrap de sessão via `POST /refresh`.
- **Backend (Prompt 04)**: `/api/v1/organizations` autenticado por Bearer (+ CSRF nos métodos não-safe); o detalhe e todos os endpoints de membros são `@TenantScoped` — o `TenantInterceptor` valida a membership **antes** do handler e devolve 404 genérico a não-membros; `/members` exige `ADMIN` no service (papel lido do `TenantContext`, nunca de JWT).
- **Backend (Prompt 06)**: os endpoints de projetos herdam exatamente a mesma cadeia — Bearer, CSRF nos métodos não-safe, `@TenantScoped` no path `{organizationId}` e papel lido do `TenantContext`; a listagem devolve `Page` no formato `{content, totalElements, totalPages, number, size}` (mesma forma que o cliente de projetos já consome).
- **Frontend (Prompt 04)**: a home lista/cria/seleciona organizações (org ativa persistida em `localStorage` sob `sentinel.activeOrganizationId`) e gerencia membros (adicionar, alterar papel, remover) quando o papel do usuário é `ADMIN`; a seção de organizações só carrega depois que a sessão está confirmada (callback `onAuthStateChange` do `AuthStatus`), evitando corrida com o bootstrap do refresh.
- CORS no backend permite a lista de origens de `app.cors.allowed-origins` (default `http://localhost:3000`) com `GET, POST, PUT, PATCH, DELETE, OPTIONS`, `Content-Type`, `Authorization`, `X-XSRF-TOKEN`, `X-Organization-Id`, `Accept` e `allowCredentials=true`, configurado unicamente no `SecurityConfig`.

## 6. Fora do escopo desta fundação

Entidades de domínio Service, Monitor, Alert e Incident, scheduler, dashboards, notificações, trilha de auditoria e permissões por recurso **não existem** e serão endereçados nos prompts futuros. (Auth entrou no Prompt 02 — sessão transitória — e foi migrada para JWT/refresh no Prompt 03, com o frontend adaptado ao mesmo contrato — ver [identity.md](identity.md); multi-tenancy entrou no Prompt 04 — ver [organizations.md](organizations.md); o primeiro recurso de domínio (projetos) entrou no Prompt 06 — ver [projects.md](projects.md).)
