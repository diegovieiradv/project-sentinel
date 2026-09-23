# Sentinel

**Plataforma multi-tenant de observabilidade e gerenciamento de incidentes** — em construção.

> **Aviso: este é o Prompt 01 de 18 — apenas a fundação. Login, organizações, monitoramento, alertas e incidentes NÃO existem ainda.**

## Estado atual

- **Prompt 01 — fundação técnica**: repositório, backend Spring Boot mínimo (health + tratamento de erros + CORS + async), PostgreSQL via Docker Compose, documentação de arquitetura.
- **NENHUMA funcionalidade de negócio implementada**: não há autenticação, tenants, organizações, projetos, serviços monitorados, alertas, incidentes nem dashboards.

## Stack atual

| Camada | Tecnologia |
| --- | --- |
| Linguagem | Java 21 |
| Backend | Spring Boot 4.1.1 (Web, Data JPA, Validation, Actuator) |
| Build | Maven 3.9+ |
| Schema | Flyway |
| Banco | PostgreSQL 17 (Docker) |
| Infra local | Docker / Docker Compose |
| Frontend | Next.js 16 + TypeScript |
| Testes | JUnit 5 + Testcontainers |

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
│   └── architecture-foundation.md
├── backend/                  # Spring Boot (monólito modular)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/sentinel/
│       │   ├── SentinelApplication.java
│       │   ├── config/       # CORS + async
│       │   ├── error/        # tratamento global de erros
│       │   └── health/       # endpoint técnico de health
│       ├── main/resources/   # application*.yml + Flyway migrations
│       └── test/java/        # testes JUnit 5 + Testcontainers
└── frontend/                 # Next.js (SPA) — outro agente/fluxo
```

## Endpoints técnicos

| Método | Path | Descrição |
| --- | --- | --- |
| GET | `/api/v1/health` | Health técnico do serviço (`{"status":"UP",...}`) |
| GET | `/actuator/health` | Health do Actuator (detalhes ocultos) |

Ambos respondem em `http://localhost:8080`.

## Documentação de arquitetura

Ver [docs/architecture-foundation.md](docs/architecture-foundation.md) para decisões arquiteturais e fronteiras modulares planejadas.

---

**Prompt 01 de 18 — apenas a fundação.** Login, organizações, monitoramento, alertas e incidentes NÃO existem ainda.
