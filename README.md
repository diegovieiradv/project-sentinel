# Sentinel

**Plataforma multi-tenant de observabilidade e gerenciamento de incidentes** — em construção.

> **Aviso: este é o Prompt 03 de 18 — fundação + identity stateless. Organizações, monitoramento, alertas e incidentes NÃO existem ainda.**

## Estado atual

- **Prompt 01 — fundação técnica**: repositório, backend Spring Boot mínimo (health + tratamento de erros + CORS + async), PostgreSQL via Docker Compose, documentação de arquitetura.
- **Prompt 02 — identity**: usuários (`users`), cadastro/login por sessão HTTP (`JSESSIONID`), `GET /api/v1/auth/me`, BCrypt, páginas de login/register no frontend.
- **Prompt 03 — identity stateless**: access token JWT HS256 (15 min) + refresh token opaco em cookie `HttpOnly` (7 dias) com rotação, revogação e logout, CSRF double-submit, CORS configurável, migrations `V3`.
- **Ainda NÃO existem**: tenants, organizações, projetos, serviços monitorados, alertas, incidentes, dashboards nem RBAC.

> O **frontend foi adaptado** a este contrato: access token em memória + header `Authorization: Bearer`, refresh token em cookie `HttpOnly`, CSRF por header `X-XSRF-TOKEN` e bootstrap de sessão via `POST /api/v1/auth/refresh`.

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
│   └── identity.md               # contrato de auth do Prompt 03 (stateless)
├── backend/                  # Spring Boot (monólito modular)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/sentinel/
│       │   ├── SentinelApplication.java
│       │   ├── JwtProperties.java / JwtConfiguration.java   # config JWT
│       │   ├── SecurityConfig.java  # filter chain, CSRF, CORS, converter
│       │   ├── config/       # async
│       │   ├── error/        # tratamento global de erros
│       │   ├── health/       # endpoint técnico de health
│       │   └── identity/     # auth (controller/JWT/CSRF) + token/ (refresh) + users
│       ├── main/resources/   # application*.yml + Flyway migrations (V1, V2, V3)
│       └── test/java/        # testes JUnit 5 + Testcontainers
└── frontend/                 # Next.js — login/register/home
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

Todos os `POST` exigem CSRF double-submit: cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`.

Detalhes de contrato, erros e decisões: [docs/identity.md](docs/identity.md).

## Documentação de arquitetura

Ver [docs/architecture-foundation.md](docs/architecture-foundation.md) para decisões arquiteturais e fronteiras modulares planejadas, e [docs/identity.md](docs/identity.md) para o contrato de autenticação.

---

**Prompt 03 de 18 — fundação + identity stateless.** Organizações, monitoramento, alertas e incidentes NÃO existem ainda.
