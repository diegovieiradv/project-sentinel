# Sentinel

**Plataforma multi-tenant de observabilidade e gerenciamento de incidentes** — em construção.

> **Aviso: este é o Prompt 06 de 18 — Projects (primeiro recurso de domínio do tenant). Monitoramento, alertas e incidentes NÃO existem ainda.**

## Estado atual

- **Prompt 01 — fundação técnica**: repositório, backend Spring Boot mínimo (health + tratamento de erros + CORS + async), PostgreSQL via Docker Compose, documentação de arquitetura.
- **Prompt 02 — identity**: usuários (`users`), cadastro/login por sessão HTTP (`JSESSIONID`), `GET /api/v1/auth/me`, BCrypt, páginas de login/register no frontend.
- **Prompt 03 — identity stateless**: access token JWT HS256 (15 min) + refresh token opaco em cookie `HttpOnly` (7 dias) com rotação, revogação e logout, CSRF double-submit, CORS configurável, migrations `V3`.
- **Prompt 04 — multi-tenancy**: organizações (tenants) e memberships com papéis por organização (`ADMIN` / `OPERATOR` / `VIEWER`), `TenantContext` (ThreadLocal) + `TenantInterceptor` + `@TenantScoped`, regra do último ADMIN (`409 LAST_ADMIN_REQUIRED`) sob lock pessimista (`SELECT ... FOR UPDATE`), isolamento cross-tenant com `404` genérico anti-enumeração, handler global de `405`, migration `V4`. CORS agora também aceita `PATCH` e o header `X-Organization-Id`.
- **Prompt 05 — tenant security / IDOR / hardening**: autoridade lida **por requisição** no servidor (o mesmo access token reflete promoção/rebaixamento/remoção sem refresh), tentativas cross-tenant (`GET`, `POST`, `PATCH`, `DELETE`) respondendo o `404` genérico anti-enumeração, concorrência do último ADMIN coberta para `PATCH × PATCH`, `PATCH × DELETE` e dois ADMINs em corrida, `400 VALIDATION_ERROR` genérico para corpo malformado e parâmetro de tipo inválido (sem vazar detalhes do parser), validação **fail-fast** das origens CORS no startup (wildcard com `allowCredentials` é erro de boot) e headers de segurança `X-Content-Type-Options: nosniff` + `X-Frame-Options: DENY`. Sem migrations novas.
- **Prompt 06 — projects**: projetos de cada organização em `/api/v1/organizations/{organizationId}/projects` (criar, listar com paginação/filtros/ordenação, detalhar, atualizar e ativar/desativar), pacote `com.sentinel.project`, tabela `projects` (`V5`), unicidade de nome por organização em duas camadas (`409 PROJECT_NAME_DUPLICATE`, inclusive sob concorrência), RBAC sobre `TenantContext.role()` (leitura para qualquer papel, mutação para `ADMIN`/`OPERATOR`), isolamento cross-tenant com `404` genérico e `DELETE` → `405` (a remoção não existe). Contrato em [docs/projects.md](docs/projects.md).
- **Ainda NÃO existem**: serviços monitorados, coleta/monitoramento, alertas, incidentes, dashboards, trilha de auditoria, recuperação de senha, verificação de e-mail nem MFA.

> O **frontend foi adaptado** a este contrato: access token em memória + header `Authorization: Bearer`, refresh token em cookie `HttpOnly`, CSRF por header `X-XSRF-TOKEN` e bootstrap de sessão via `POST /api/v1/auth/refresh`. No Prompt 04 ele também passou a listar/criar/selecionar organizações e gerenciar membros.

## Stack atual

| Camada | Tecnologia |
| --- | --- |
| Linguagem | Java 21 |
| Backend | Spring Boot 4.1.1 (Web, Data JPA, Validation, Actuator, Security, **OAuth2 Resource Server**) |
| Build | Maven 3.9+ |
| Schema | Flyway |
| Banco | PostgreSQL 17 (Docker) |
| Infra local | Docker / Docker Compose |
| Frontend | Next.js 16 + TypeScript |
| Testes | JUnit 5 + Testcontainers + spring-security-test |

## Pré-requisitos

- Java 21 (ex.: OpenJDK 21.0.12)
- Maven 3.9+
- Docker 29+ com plugin `docker compose` v2
- Node.js 20+ e npm (para o frontend)
- Git 2.x

> Se o `mvn` não encontrar o Java, defina `JAVA_HOME`, ex.: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.

## Configuração

1. (Opcional) copie as variáveis de exemplo:
   ```bash
   cp .env.example .env
   ```
2. Os **defaults já funcionam para dev local** (banco `sentinel` na porta `5433`, backend na `8080`). Não é obrigatório criar o `.env`.
3. `.env` e similares são ignorados pelo Git; apenas `.env.example` (placeholders) é versionado.

Variáveis principais:

| Variável | Default | Descrição |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5433/sentinel` | JDBC URL do PostgreSQL (opcional) |
| `DATABASE_USERNAME` | `sentinel` | Usuário do PostgreSQL (opcional) |
| `DATABASE_PASSWORD` | `sentinel` | Senha do PostgreSQL no Docker Compose; **obrigatória (sem default) no perfil `prod`** |
| `SERVER_PORT` | `8080` | Porta do backend |
| `SPRING_PROFILES_ACTIVE` | `dev` | Perfil Spring (`dev`, `test`, `prod`) |
| `JWT_SECRET` | *(vazio)* | Segredo HS256 (≥ 32 chars). Vazio em dev → segredo efêmero no startup (**obrigatória no perfil `prod`**) |
| `JWT_ACCESS_TTL_SECONDS` | `900` | TTL do access token (15 min) |
| `JWT_REFRESH_TTL_DAYS` | `7` | TTL do refresh token |
| `JWT_COOKIE_NAME` / `JWT_COOKIE_PATH` / `JWT_COOKIE_SECURE` / `JWT_COOKIE_SAME_SITE` | `refresh_token` / `/api/v1/auth` / `false` / `Strict` | Atributos do cookie de refresh (prod força `Secure=true`) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Origens permitidas, separadas por vírgula |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Base URL da API para o frontend |

## Como iniciar

### 1. PostgreSQL (Docker, porta 5433)

```bash
docker compose up -d
docker compose ps   # deve ficar healthy
```

> A porta do host é **5433** (o host já usa 5432 para outro PostgreSQL).

### 2. Backend (porta 8080)

```bash
cd backend
mvn spring-boot:run
```

### 3. Frontend (porta 3000)

```bash
cd frontend
npm install
npm run dev
```

## Testes e builds

```bash
# Testes do backend (usa Testcontainers — requer Docker em execução)
cd backend && mvn test

# Build do backend (gera o jar)
cd backend && mvn package

# Lint, type-check e build do frontend
cd frontend && npm run lint && npm run type-check && npm run build
```

## Estrutura do repositório

```
project-sentinel/
├── .env.example              # placeholders de variáveis
├── .gitignore
├── docker-compose.yml        # apenas PostgreSQL 17 (porta 5433)
├── README.md
├── docs/
│   ├── architecture-foundation.md
│   ├── identity.md               # contrato de auth do Prompt 03 (stateless)
│   ├── organizations.md          # contrato multi-tenant do Prompt 04
│   └── projects.md               # contrato de projetos do Prompt 06
├── backend/                  # Spring Boot (monólito modular)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/sentinel/
│       │   ├── SentinelApplication.java
│       │   ├── JwtProperties.java / JwtConfiguration.java   # config JWT
│       │   ├── SecurityConfig.java  # filter chain, CSRF, CORS, converter
│       │   ├── config/       # async
│       │   ├── error/        # tratamento global de erros (400, 404, 405, 500)
│       │   ├── health/       # endpoint técnico de health
│       │   ├── identity/     # auth (controller/JWT/CSRF) + token/ (refresh) + users
│       │   ├── organization/ # multi-tenancy: context/ (TenantContext), model/
│       │   │                 #   (Organization, Membership, Role), dto/, repository/,
│       │   │                 #   service/ e web/ (controllers + TenantInterceptor)
│       │   └── project/      # projetos: model/, repository/, dto/, service/ e web/
│       │                     #   (controller @TenantScoped)
│       ├── main/resources/   # application*.yml + Flyway migrations (V1–V5)
│       └── test/java/        # testes JUnit 5 + Testcontainers (identity, organization, project, error, health)
└── frontend/                 # Next.js — login/register/home + organizações e membros
```

## Endpoints

| Método | Path | Auth | Descrição |
| --- | --- | --- | --- |
| GET | `/api/v1/health` | público | Health técnico (`{"status":"UP",...}`) — também materializa o cookie `XSRF-TOKEN` |
| GET | `/actuator/health` | público | Health do Actuator (detalhes ocultos) |
| POST | `/api/v1/auth/register` | público | Cadastro → `201 {user}` (não emite token) |
| POST | `/api/v1/auth/login` | público | Login → `200 {user, accessToken, tokenType:"Bearer"}` + cookie `refresh_token` |
| POST | `/api/v1/auth/refresh` | cookie | Rotaciona o refresh → `200 {user, accessToken}` + cookie novo \| `401 REFRESH_TOKEN_*` |
| POST | `/api/v1/auth/logout` | cookie | Revoga o refresh apresentado e limpa o cookie → `200 {message}` (idempotente) |
| GET | `/api/v1/auth/me` | Bearer | Usuário do access token → `200 {id,name,email}` \| `401` |
| POST | `/api/v1/organizations` | Bearer + CSRF | Cria a organização e vincula o criador como `ADMIN` (mesma transação) → `201 {id,name,createdAt,updatedAt,role:"ADMIN"}` \| `400 VALIDATION_ERROR` |
| GET | `/api/v1/organizations` | Bearer | Lista **apenas** as organizações do usuário → `200 [{id,name,createdAt,updatedAt,role}]` (nunca globais) |
| GET | `/api/v1/organizations/{organizationId}` | Bearer (membro) | Detalhe + `role` do usuário atual → `200 {id,name,createdAt,updatedAt,role}` \| `404` não-membro/inexistente |
| GET | `/api/v1/organizations/{organizationId}/members` | Bearer + **ADMIN** | `200 [{membershipId,userId,name,email,role,createdAt}]` \| `403 FORBIDDEN` \| `404` não-membro |
| POST | `/api/v1/organizations/{organizationId}/members` | Bearer + **ADMIN** + CSRF | `{email, role}` → `201 MemberResponse` \| `400` \| `403` \| `404` (e-mail não cadastrado) \| `409 MEMBERSHIP_ALREADY_EXISTS` |
| PATCH | `/api/v1/organizations/{organizationId}/members/{membershipId}` | Bearer + **ADMIN** + CSRF | `{role}` → `200 MemberResponse` \| `409 LAST_ADMIN_REQUIRED` \| `404` membership inexistente/outro tenant |
| DELETE | `/api/v1/organizations/{organizationId}/members/{membershipId}` | Bearer + **ADMIN** + CSRF | Remove o membro → `204` sem corpo \| `409 LAST_ADMIN_REQUIRED` \| `404` |
| POST | `/api/v1/organizations/{organizationId}/projects` | Bearer + **ADMIN/OPERATOR** + CSRF | `{name, description?}` → `201 ProjectResponse` \| `400` \| `403` \| `404` \| `409 PROJECT_NAME_DUPLICATE` |
| GET | `/api/v1/organizations/{organizationId}/projects` | Bearer (qualquer papel) | `200` página `{content, totalElements, totalPages, number, size}` com `page`/`size`/`sort` (`createdAt`\|`name`\|`id`) + filtros `active` e `name` |
| GET | `/api/v1/organizations/{organizationId}/projects/{projectId}` | Bearer (qualquer papel) | `200 ProjectResponse` (inclui `role` do usuário) \| `404` projeto de outro tenant/inexistente/id malformado |
| PATCH | `/api/v1/organizations/{organizationId}/projects/{projectId}` | Bearer + **ADMIN/OPERATOR** + CSRF | `{name?, description?}` — parcial (ausente não altera, `""` limpa) → `200` \| `400` \| `409` nome duplicado \| `404` |
| PATCH | `/api/v1/organizations/{organizationId}/projects/{projectId}/status` | Bearer + **ADMIN/OPERATOR** + CSRF | `{active: boolean}` → `200` (idempotente, sem tocar `updatedAt` se já for o estado) \| `400 active must not be null` \| `404` |
| DELETE | `/api/v1/organizations/{organizationId}/projects[/{projectId}]` | — | **405** — remoção de projeto não existe neste prompt (soft-delete é o `.../status`) |

Todo método não-safe exige CSRF double-submit: cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`. (Requisições que já trazem `Authorization: Bearer` são isentas do CSRF pelo próprio Spring Security — o bearer não é anexado automaticamente pelo navegador; o double-submit continua obrigatório nas requisições autenticadas por cookie.)

Rotas existentes com método HTTP inválido respondem `405 METHOD_NOT_ALLOWED` no shape padrão de erro.

Corpo JSON malformado, campo com tipo errado ou parâmetro de caminho fora do tipo respondem `400 VALIDATION_ERROR` (ou `404` nos ids de tenant, conforme a política anti-enumeração) com mensagem genérica — os detalhes do parser/conversão ficam no servidor.

Todas as respostas do Spring Security trazem `X-Content-Type-Options: nosniff` e `X-Frame-Options: DENY`.

Detalhes de contrato, erros e decisões: [docs/identity.md](docs/identity.md) (auth), [docs/organizations.md](docs/organizations.md) (multi-tenancy) e [docs/projects.md](docs/projects.md) (projetos).

## Documentação de arquitetura

Ver [docs/architecture-foundation.md](docs/architecture-foundation.md) para decisões arquiteturais e fronteiras modulares planejadas, [docs/identity.md](docs/identity.md) para o contrato de autenticação, [docs/organizations.md](docs/organizations.md) para o contrato multi-tenant (organizações, membros e Tenant Context) e [docs/projects.md](docs/projects.md) para o contrato de projetos.

---

**Prompt 06 de 18 — Projects (primeiro recurso de domínio do tenant).** Monitoramento, alertas e incidentes NÃO existem ainda.
