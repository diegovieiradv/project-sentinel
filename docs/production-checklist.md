# Production Checklist — Sentinel

> **Prompt 18 de 18 — §21 Documentação de Produção.** Checklist objetivo e acionável do que é necessário para implantar o Sentinel, com os valores **reais** extraídos de `application.yml` / `application-prod.yml` / `docker-compose.yml` / `.env.example` e as limitações conhecidas pós-homologação (P18). Este documento **não** altera código, migrations, dependências ou configuração e **não** escolhe provedor de hospedagem — essa é decisão do responsável.

## 1. Arquitetura de implantação

| Componente | Artefato | Porta (default) | Depende de |
| --- | --- | --- | --- |
| **PostgreSQL ≥ 17** (persistente) | imagem `postgres:17-alpine` via `docker compose` ou instância própria | host **5433** → container 5432 (compose local); produção pode usar 5432 | volume persistente (`sentinel_pgdata` no compose) |
| **Backend** Spring Boot | jar `backend/target/sentinel-backend-0.1.0-SNAPSHOT.jar` (`mvn package`) | **8080** (`SERVER_PORT`) | PostgreSQL (JDBC via `DATABASE_URL`) |
| **Frontend** Next.js 16 | build estático + servidor Next (`next build` + `next start`) | **3000** | Backend (HTTP via `NEXT_PUBLIC_API_URL`) |

Ordem de subida: banco → backend (aplica Flyway no startup) → frontend. O backend aborta o startup se o banco estiver inacessível ou as migrations divergirem (§6, §11).

> **Nota de precisão**: `frontend/next.config.ts` **não** define `output: "standalone"` — o deploy usa o servidor embutido do Next (`next start`). Habilitar standalone é opcional e fora do escopo deste documento.

## 2. Pré-requisitos

| Requisito | Versão | Observação |
| --- | --- | --- |
| Java (JDK) | **21** | ex.: OpenJDK 21 (`pom.xml`: `java.version=21`; homologação com 21.0.12) |
| Maven | **3.9+** | build do jar (`mvn package`) |
| Node.js | **≥ 20.9.0** (requisito do Next 16.3.6) | homologação executada com **Node 22** (v22.23.2) |
| npm | acompanha o Node | `npm ci` no `frontend/` |
| PostgreSQL | **17+** | schema foi migrado/validado em PostgreSQL 17 |
| Docker | opcional | apenas para subir o banco via `docker compose` (compose v2) |

## 3. Variáveis de ambiente

Tabela completa conforme os fontes reais de configuração. **Fail-fast** = o startup **aborta** com a mensagem citada; **gap** = comportamento documentado abaixo.

### 3.1 Backend

| Variável | Default | Obrigatória? | Fail-fast no startup | Efeito / falha |
| --- | --- | --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5433/sentinel` | não (default local) | **não** (mesmo em prod) | **gap conhecido**: em prod não há `:?` — URL errada só falha ao conectar |
| `DATABASE_USERNAME` | `sentinel` | não | **não** | idem gap |
| `DATABASE_PASSWORD` | `sentinel` (dev) | **sim, em perfil prod** | **sim** (`:?` em `application-prod.yml`) | startup aborta: `DATABASE_PASSWORD é obrigatória no perfil prod` |
| `JWT_SECRET` | *(vazio)* | **sim, em perfil prod**; ≥ **32 caracteres** (HS256) | **sim** (`:?` em `application-prod.yml` + validação de comprimento em `JwtConfiguration`) | prod sem segredo → startup aborta; segredo < 32 chars → `IllegalStateException`; vazio em dev → segredo **efêmero** gerado no startup (WARNING; reiniciar invalida tokens) |
| `SPRING_PROFILES_ACTIVE` | `dev` | não | — | perfis: `dev`, `test`, `prod`; **prod** ativa os fail-fast acima e força cookie `Secure=true` |
| `SERVER_PORT` | `8080` | não | porta ocupada → falha de bind | porta HTTP do backend |
| `JWT_ACCESS_TTL_SECONDS` | `900` (15 min) | não | — | TTL do access token |
| `JWT_REFRESH_TTL_DAYS` | `7` | não | — | TTL do refresh token |
| `JWT_COOKIE_NAME` | `refresh_token` | não | — | nome do cookie de refresh |
| `JWT_COOKIE_PATH` | `/api/v1/auth` | não | — | path do cookie de refresh |
| `JWT_COOKIE_SECURE` | `false` | não | — | **prod força `true`** (fixo em `application-prod.yml`) |
| `JWT_COOKIE_SAME_SITE` | `Strict` | não | — | atributo SameSite do cookie |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | não (dev) | **sim**, se contiver wildcard (`*`) junto de credenciais | origens separadas por vírgula; wildcard+credenciais → `IllegalStateException` no startup |
| `MONITORING_ENABLED` | `true` | não | — | `false` pausa a coleta sem redeploy |
| `MONITORING_SCHEDULER_DELAY_MS` | `10000` | não | — | intervalo do tick (homologação usou `5000`) |
| `MONITORING_WORKER_COUNT` | `4` | não | — | workers do pool dedicado de checagens |
| `MONITORING_QUEUE_CAPACITY` | `200` | não | — | capacidade da fila; saturação não vira erro de alvo |

### 3.2 Frontend (build-time)

| Variável | Default | Obrigatória? | Observação |
| --- | --- | --- | --- |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | não (dev) | **embutida no build** (`NEXT_PUBLIC_*`); aponte para a URL pública do backend antes de `npm run build` |

### 3.3 Banco via Docker Compose

| Variável | Default | Observação |
| --- | --- | --- |
| `POSTGRES_DB` | `sentinel` | fixa no `docker-compose.yml` |
| `POSTGRES_USER` | `sentinel` | fixa no `docker-compose.yml` |
| `POSTGRES_PASSWORD` | `${DATABASE_PASSWORD:-sentinel}` | deve casar com `DATABASE_PASSWORD` do backend |

## 4. Secrets

- **Nunca** versionar secrets: `.env` é ignorado pelo Git; apenas `.env.example` com **placeholders** é versionado (verificado).
- `JWT_SECRET`: gere ≥ 32 caracteres aleatórios (ex.: `openssl rand -base64 48`); injete via ambiente, nunca no código.
- **Limitação conhecida (rotação)**: o segredo é único — trocar `JWT_SECRET` **invalida todos os tokens emitidos** (usuários precisam relogar). Não existe mecanismo de rotação sem downtime (gap §14).
- Rotacione também `DATABASE_PASSWORD` com janela de manutenção (parar backend, trocar, subir).

## 5. Banco persistente — backup e restore

Procedimento padrão PostgreSQL (declarado, **não exercitado neste projeto**):

```bash
# Backup (custom format — base para pg_restore)
pg_dump -Fc -h <host> -p <porta> -U sentinel -d sentinel -f sentinel-$(date +%Y%m%d).dump

# Restore em banco vazio (drop/create do DB alvo é responsabilidade do operador)
pg_restore -h <host> -p <porta> -U sentinel -d sentinel --clean --if-exists sentinel-YYYYMMDD.dump
```

- Backup agendado e **restore exercitado** (com verificação de consistência) são **pré-requisitos de produção** — até o momento o restore **não foi testado neste repositório** (gap §14).
- O volume do compose (`sentinel_pgdata`) não substitui backup: destruição do volume = perda dos dados.

## 6. Migrations (Flyway)

- Catálogo atual: **V1–V12**, aplicadas **no startup** do backend (`flyway.enabled=true`, `locations=classpath:db/migration`, `baseline-on-migrate=true`, `validate-on-migrate=true`).
- **Nunca editar** uma migration já aplicada: o checksum divergente derruba o startup (`validate-on-migrate`). Mudança de schema = **nova** migration (`V13+`).
- `spring.jpa.hibernate.ddl-auto: validate` — o Hibernate **valida** o schema contra as entidades e **nunca** cria/altera tabelas; divergência derruba o startup.
- Antes de publicar um jar novo: confirme que as migrations novas (se houver) são aditivas e compatíveis com dados existentes.

## 7. Backend (jar)

```bash
cd backend
mvn -DskipTests package          # gera target/sentinel-backend-0.1.0-SNAPSHOT.jar

SPRING_PROFILES_ACTIVE=prod \
DATABASE_URL=jdbc:postgresql://<host>:<porta>/sentinel \
DATABASE_USERNAME=sentinel \
DATABASE_PASSWORD=<secret> \
JWT_SECRET=<>=32 chars> \
CORS_ALLOWED_ORIGINS=https://app.exemplo.com \
java -jar target/sentinel-backend-0.1.0-SNAPSHOT.jar
```

- Health: `GET /api/v1/health` → `{"status":"UP",...}` (público).
- Actuator: expõe apenas `health,info` com `show-details: never`; **público** no filter chain apenas `/api/v1/health` e `/actuator/health` — os demais endpoints do actuator não são liberados.
- Confirme no log: Flyway aplicou/validou as migrations e o startup terminou sem `IllegalStateException`.

## 8. Frontend (Next.js)

```bash
cd frontend
npm ci
NEXT_PUBLIC_API_URL=https://api.exemplo.com npm run build
npm run start                     # servidor Next na porta 3000 (ou PORT definida)
```

- `NEXT_PUBLIC_API_URL` é lida **em build-time** — rebuild é obrigatório após mudar a URL.
- O navegador consome a API **diretamente** do `NEXT_PUBLIC_API_URL`: ou o backend serve CORS com a origem do frontend listada em `CORS_ALLOWED_ORIGINS` (§10), ou um reverse proxy unifica os dois sob o mesmo domínio (§9), tornando CORS irrelevante.

## 9. Domínio e HTTPS (reverse proxy)

- Sirva frontend (e API, se same-origin) atrás de reverse proxy com TLS: **Caddy** (auto-TLS) ou **Nginx** + certificado.
- **HSTS deve ser emitido pelo proxy** (`Strict-Transport-Security`) — a aplicação **não** emite HSTS (gap medido §14).
- Em `SPRING_PROFILES_ACTIVE=prod` o cookie de refresh nasce `Secure=true`: sem HTTPS o navegador **não persiste** o cookie e o refresh de sessão falha.
- O proxy também é o lugar natural para rate limiting temporário (§14, mitigação de rate limiting).

## 10. CORS

- `CORS_ALLOWED_ORIGINS` com as origens **explícitas** do frontend, separadas por vírgula (ex.: `https://app.exemplo.com`).
- Wildcard (`*`) combinado com credenciais é **rejeitado no startup** (`IllegalStateException`) — comportamento fixado por `SecurityConfigOriginsTest`.
- Métodos aceitos incluem `PATCH` e header `X-Organization-Id` (contrato multi-tenant).

## 11. Logs e troubleshooting

| Onde olhar | Como |
| --- | --- |
| Backend | stdout do processo (systemd unit, `docker logs`, ou console do orquestrador); nível INFO para `com.sentinel` no perfil prod |
| Flyway | linhas de startup listam as migrations aplicadas/validadas |
| Frontend | logs do processo `next start` |

Erros comuns no startup:

| Sintoma | Causa provável | Ação |
| --- | --- | --- |
| `Port 8080 was already in use` | porta ocupada | mude `SERVER_PORT` ou libere a porta |
| `DATABASE_PASSWORD é obrigatória no perfil prod` | perfil prod sem senha | injete `DATABASE_PASSWORD` |
| `JWT_SECRET é obrigatória no perfil prod` (ou erro de comprimento < 32) | perfil prod sem/curto segredo | injete `JWT_SECRET` ≥ 32 chars |
| Erro Flyway de checksum / validate | migration editada após aplicada | reverta a edição; crie nova migration; se necessário, restaure o banco do backup (§13) |
| Falha de conexão JDBC | banco inacessível / `DATABASE_URL` errada | confira host/porta/credenciais e o estado do banco (§3: `DATABASE_URL` não tem fail-fast) |
| `IllegalStateException` de origens CORS | wildcard + credenciais | liste origens explícitas |
| Sessão "morre" em prod | backend em HTTP com cookie `Secure` | ponha TLS no proxy (§9) |

## 12. Health e monitoramento do próprio Sentinel

| Endpoint | Auth | Uso |
| --- | --- | --- |
| `GET /api/v1/health` | público | health técnico da aplicação (também materializa o cookie `XSRF-TOKEN`) |
| `GET /actuator/health` | público | health do Actuator, detalhes ocultos (`show-details: never`) |

- Configure o orquestrador para reiniciar o backend quando o health responder erro.
- **Gap**: o Sentinel **não alerta sobre si mesmo** — não há SLO, error budget, métricas de aplicação nem alerta interno (§14). O monitoramento do Sentinel depende de ferramenta externa (ex.: probe HTTP no `/api/v1/health`).

## 13. Rollback

| Falha | Ação |
| --- | --- |
| Migration falhou no startup (banco divergente) | pare o backend; **restaure o backup do banco** (§5) ou reverta para o jar anterior cujo catálogo V1–V12 casa com o schema; suba novamente |
| Jar novo com defeito | pare o processo; volte para o jar da versão anterior (artefato imutável por versão); suba com as mesmas env vars |
| Frontend novo com defeito | redeploy do build anterior (`npm run build` do tag/commit anterior + `npm run start`) — sem estado no servidor |
| Env var errada | corrija no orquestrador e reinicie o backend (o startup é a validação) |

O frontend não persiste dados; o banco é o único estado — backup/teste de restore (§5) é o que torna o rollback de dados confiável.

## 14. Riscos conhecidos antes de publicar (checklist de bloqueadores)

Cada item: 1 linha de impacto + mitigação sugerida. Nenhum destes itens foi resolvido pela homologação P18.

| # | Risco | Impacto | Mitigação sugerida |
| --- | --- | --- | --- |
| 1 | **Rate limiting ausente** | força bruta em login/refresh e esgotamento de endpoints são possíveis sem teto | rate limit no reverse proxy (ex.: Nginx `limit_req`) enquanto não há filtro próprio |
| 2 | **MFA ausente** | comprometimento de senha única = acesso completo ao tenant | exigir SSO/VPN na frente do app; priorizar TOTP em roadmap |
| 3 | **Reset/verificação de senha ausente** | conta sem recuperação é perdida; e-mail não é provado | fluxo de recuperação + verificação de e-mail antes de produção ampla |
| 4 | **Audit log ausente** | não há trilha "quem alterou o quê" (a timeline de incidentes é domínio, não auditoria) | tabela append-only com `REVOKE DELETE/UPDATE` + retenção |
| 5 | **Observabilidade ausente** (logs estruturados → coletor, métricas, tracing) | em incidente real não há como reconstruir o que aconteceu | OpenTelemetry + coletor (Loki/Datadog/etc.) |
| 6 | **CSP / HSTS / `Referrer-Policy` ausentes** (medido e confirmado no P18) | navegador sem política de conteúdo/transporte dura; app expõe apenas `X-Content-Type-Options` e `X-Frame-Options` | emitir HSTS e CSP no reverse proxy (§9) |
| 7 | **CI não ativada** (workflow [ci.yml](../.github/workflows/ci.yml) preparado localmente; repo ainda sem remote — **zero execuções no GitHub**) | sem run remota, merge que quebra build/testes segue desimpedido até o push/ativação | ativar a CI: criar remote + push + required checks ([continuous-integration.md](continuous-integration.md) §12) |
| 8 | **Backup/restore não exercitados** | backup sem restore testado não é plano de recuperação | executar restore em ambiente de staging e cronometrar RTO |
| 9 | **Pen-test / revisão independente não executados** | guarda SSRF validada apenas por testes próprios | agendar auditoria externa antes de dados reais sensíveis |
| 10 | **SLO / alerta interno não definidos** | sem meta de erro/latência nem alerta do próprio Sentinel | definir SLO mínimo e probe externo no `/api/v1/health` |
| 11 | **Rotação de `JWT_SECRET` sem downtime** | troca do segredo invalida todos os tokens (dor de usuários) | janela de manutenção + aviso prévio; futuro: segredos assinados com sobreposição |
| 12 | **`DATABASE_URL`/`DATABASE_USERNAME` sem fail-fast em prod** | credencial/URL errada em prod só falha ao conectar, não no startup | padrão de deploy que valide a presença (wrapper/systemd `EnvironmentFile` obrigatório) |
| 13 | **UI em browser (E2E/a11y) NOT VERIFIED** | Playwright/jsdom não fazem parte do repo — interações de UI nunca rodaram em navegador real nesta homologação | adicionar infra de testes E2E de frontend antes de confiar em fluxos críticos pela UI |

## 15. Readiness summary (pós-homologação P18)

**Veredito: APTO COM RESSALVAS.**

- Sem **P0/P1/P2 novos** apontados pela homologação final: baseline backend **865/0/0/0**, frontend **207** testes, lint/type-check/build exit 0; fluxo E2E real (identidade → tenant → monitor → alerta → incidente → dashboard → recuperação), isolamento cross-tenant, RBAC, guarda SSRF (34 casos negativos), Flyway 12/12 e smoke de latência local executados com evidência (ver [homologation-readiness.md](homologation-readiness.md) §9).
- **Ressalvas** = todos os itens da §14 (gaps de segurança/operacão pré-existentes, confirmados ou medidos na homologação) + UI em browser **NOT VERIFIED**.
- **Restore de banco não exercitado** (§5) e **pen-test não executado** permanecem condições de produção.
- **Provedor de hospedagem: decisão pendente do responsável** — este documento não escolhe cloud, PaaS nem modo de gestão (VM/container serverless); qualquer um dos caminhos atende à arquitetura da §1 desde que as regras das §§3–10 sejam cumpridas.
