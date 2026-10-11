# CI (GitHub Actions) — Sentinel

> **Etapa Pós-Roadmap 02.2** — como o pipeline de CI está montado, o que ele cobre, como validá-lo localmente e como ativá-lo. Documento **operacional**, coerente com o workflow implementado em [`.github/workflows/ci.yml`](../.github/workflows/ci.yml). Status de homologação: [homologation-readiness.md](homologation-readiness.md) §4 #11 e §9.2 #11; risco de produção: [production-checklist.md](production-checklist.md) §14 #7.

## 1. Objetivo e status

**Objetivo:** impedir merge que quebra build/testes ao rodar, a cada push em `main` e a cada pull request para `main`, os mesmos gates que hoje só existem localmente: testes de backend (Maven/JUnit/Testcontainers), lint + type-check + testes unitários + build do frontend e — em PRs — a suíte E2E Playwright contra aplicação real.

**Status: preparação local. A CI NÃO está ativada no GitHub.**

| Fato | Estado |
| --- | --- |
| Workflow versionado no repo (`.github/workflows/ci.yml`) | ✅ sim |
| Validado localmente (actionlint + shellcheck + PyYAML) | ✅ sim (§10) |
| Repo com remote no GitHub | ❌ **não** (nenhum remote configurado) |
| Execução remota no GitHub Actions | ❌ **nenhuma** — zero runs, zero evidência remota de sucesso |
| `playwright.config.ts` pronto para CI (chromium pinado quando `CI=true`) | ✅ sim (§7) |

Ou seja: o pipeline existe e está pronto, mas **só passa a valer depois da ativação** (§12). Até lá, nada no GitHub executa — nenhum status de check, nenhum artefato, nenhum bloqueio de merge automático.

## 2. Arquitetura: um workflow, três jobs

Workflow único `CI` (`.github/workflows/ci.yml`), três jobs paralelos onde possível:

```
push/PR → main
   ├── backend-test   (mvn -B test, ~11 min com cache frio de build)
   ├── frontend-test  (npm ci + lint + type-check + vitest + next build)
   └── e2e            (needs: [backend-test, frontend-test]; SOMENTE em pull_request)
```

| Job | O que faz | Timeout | Por quê assim |
| --- | --- | --- | --- |
| `backend-test` | `mvn -B test` em `backend/` — 867 testes, Testcontainers sobe `postgres:17-alpine` próprio | 20 min | cache Maven via `setup-java` (`cache: maven`); Surefire anexado como artefato só em falha (7 dias) |
| `frontend-test` | em `frontend/`: `npm ci` → `lint` → `type-check` → `test` (Vitest) → `build` (Next.js) | 10 min | gate barato e rápido; build sem env obrigatória (fallback `localhost:8080`) |
| `e2e` | sobe PostgreSQL (compose raiz) + backend (perfil `test`) + frontend **production**, roda os 8 specs/14 testes do Playwright | 25 min | é o job mais caro e mais sensível a ambiente — só roda em **PR**, e só depois dos dois jobs verdes (`needs`) |

**Rationale:**

- **Jobs separados backend × frontend** dão feedback rápido e paralelo (quem só mexe em `frontend/` não paga 11 min de Maven, e vice-versa). Não há matriz/split por módulo — o repo tem exatamente um backend e um frontend.
- **`e2e` atrás de `needs`**: não adianta subir 3 processos + banco para rodar E2E de uma mudança que já reprovou lint ou teste unitário. Em push para `main` o E2E **não roda** (só em PR) — o gate de merge é o PR; push direto em `main` ainda recebe backend-test + frontend-test.
- **Sem job de deploy, sem packages, sem publicação**: este workflow só **valida**. Deploy continua sendo decisão manual/operacional ([production-checklist.md](production-checklist.md)).

## 3. Eventos e concorrência

```yaml
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

- **Push em `main`**: regressão pós-merge detectada mesmo depois que o PR sumiu da lista.
- **Pull request para `main`**: o gate de revisão — é o único que roda o job `e2e`.
- **`concurrency` por ref com `cancel-in-progress: true`**: um novo push no mesmo PR/branch cancela a execução anterior ainda em andamento — evita fila presa e gasto de runner com commits obsoletos. O grupo é por `github.ref`, então PRs distintos rodam em paralelo sem se atropelar.

## 4. Permissões e segurança do pipeline

| Prática | Como está aplicada |
| --- | --- |
| Menor privilégio | `permissions: contents: read` no nível do workflow — o token do Actions **não** escreve no repo, não cria releases, não publica packages |
| Sem `pull_request_target` | o evento é `pull_request` (código do fork não recebe secrets do repo) |
| Sem secrets reais | o workflow não referencia `secrets.*`; os valores de ambiente do job `e2e` são **fixtures descartáveis** (§9) |
| Artefatos expiram | `retention-days: 7` nos dois uploads (`surefire-reports`, `playwright-report`) |
| Banco efêmero | o PostgreSQL do `e2e` é destruído com `docker compose down -v` em `if: always()` — volume nomeado removido a cada job |
| Processos encerrados em falha | os steps de parada (backend/frontend/PostgreSQL) rodam com `if: always()` — melhor esforço; o runner é efêmero de qualquer forma |
| Sem deploy/escrita | nenhuma permissão `contents: write`, nenhum `id-token`, nenhum push |

Nada de credencial de produção entra no workflow, e o backend do `e2e` usa o perfil `test` (rate-limit e monitoring desligados — §7), não `prod`.

## 5. Versões e política de pinning

| Componente | Versão | Origem/política |
| --- | --- | --- |
| `actions/checkout` | `@v4` | oficial GitHub |
| `actions/setup-java` | `@v4` | oficial GitHub |
| `actions/setup-node` | `@v4` | oficial GitHub |
| `actions/upload-artifact` | `@v4` | oficial GitHub |
| Java | **21** (Temurin) | mesmo LTS do `backend/pom.xml` |
| Maven | **3.9.x** do runner (`mvn` direto) | o repo **não** tem Maven wrapper — o runner usa o Maven pré-instalado |
| Node | **22** | toolchain do `frontend/` |
| npm cache | `cache: npm` + `cache-dependency-path: frontend/package-lock.json` | lockfile é a fonte de verdade |
| Playwright | **1.64.0** (devDependency) | `npx playwright install --with-deps chromium` instala o Chromium **versionado pelo próprio Playwright** |
| PostgreSQL | `postgres:17-alpine` | mesma imagem do `docker-compose.yml` raiz |

**Política de tags majores (`@v4`)**: as ações são fixadas por **tag major**, não por SHA. Justificativa: tags majores recebem patches de segurança e correções automaticamente; pinar SHA exige cadência de atualização manual (renovate/bot) que é desproporcional ao estágio atual do projeto — ainda sem remote e sem histórico de execução. Nenhuma ação de terceiros é usada (só as oficiais `actions/*`). Se o projeto ganhar bot de update (Dependabot/Renovate), migrar para SHA+comentário vira um passo natural.

## 6. Testcontainers e Docker no CI

O `backend-test` **não** declara `services: postgres`. Os 867 testes usam **Testcontainers**: cada teste/classe que precisa de banco sobe um `postgres:17-alpine` **próprio**, na porta aleatória que o Testcontainers escolher, com lifecycle travado ao teste.

| Pergunta | Resposta |
| --- | --- |
| Por que **não** usar `services:` do Actions? | O Testcontainers **não** consome service containers: ele gerencia seus próprios containers via Docker socket. Declarar um serviço separado só adicionaria um banco ocioso. |
| O runner tem Docker? | Sim — `ubuntu-latest` traz Docker pré-instalado e em execução; é pré-requisito do job (é o que o Testcontainers usa). |
| E o `e2e`? | Ali o banco **não** é do Testcontainers — o backend roda em processo normal (perfil `test`) e precisa de um Postgres estável na `5433`. Por isso o `e2e` usa o **`docker-compose.yml` da raiz** (`docker compose up -d --wait`, que aguarda o healthcheck `pg_isready`), com `down -v` no fim. |

## 7. Playwright no CI (browser + orquestração)

### 7.1 Local × CI (por que `channel` some no CI)

| Ambiente | Browser | Por quê |
| --- | --- | --- |
| **Local** | Chrome do sistema (`channel: "chrome"`) | os browsers empacotados do Playwright não estão em `~/.cache/ms-playwright` nesta versão (1.64.0 espera `chromium-1248`/`chromium_headless_shell-1248`) e o projeto **não** baixa browser no SO |
| **CI** | Chromium **pinado pelo Playwright** (`npx playwright install --with-deps chromium`) | o runner Linux não tem Chrome do sistema; a revisão do browser passa a ser determinística (a do Playwright 1.64.0) |

A chave é `CI=true` (setada pelo GitHub Actions): em [`frontend/playwright.config.ts`](../frontend/playwright.config.ts), `...(isCI ? {} : { channel: "chrome" })` remove o `channel` apenas no CI. Localmente nada muda — quem roda `npm run test:e2e` continua no Chrome do sistema.

### 7.2 Orquestração de serviços no job `e2e`

O config **não** tem `webServer` (decisão da Etapa Pós-Roadmap 01, mantida) — no CI o workflow sobe tudo na mão, nesta ordem:

1. `docker compose up -d --wait` — PostgreSQL 17 com healthcheck (raiz);
2. `mvn -B spring-boot:run` em background (`nohup`, PID gravado em `backend.pid`), perfil **`test`**;
3. **Readiness do backend por `curl`** em `/actuator/health` (procurando `"status":"UP"`) — até 90 × 2 s = 180 s, **sem `sleep` fixo**; em falha, despeja `backend-run.log` no log do step;
4. `npm ci` + `npm run build` com `NEXT_PUBLIC_API_URL=http://localhost:8080` (production build);
5. `npm run start` em background (`frontend.pid`) — o Next.js production server;
6. **Readiness do frontend** por `curl http://localhost:3000` — até 30 × 2 s = 60 s;
7. `npx playwright install --with-deps chromium`;
8. `npm run test:e2e`.

**Rate limit desligado**: o perfil `test` (`application-test.yml`) já seta `app.security.rate-limit.enabled: false` e `sentinel.monitoring.enabled: false` — por isso o workflow **não** seta `APP_SECURITY_RATE_LIMIT_ENABLED`: a suíte (login, registro, troca de organização em rajada) não esbarra em 429, e nenhum `@Scheduled` do motor de monitoramento disputa CPU com o teste. É o mesmo perfil que os testes de backend já usam com Testcontainers.

Os passos de start/readiness usam `working-directory: backend` / `frontend` (os comandos `mvn`/`npm` só funcionam de dentro dessas pastas), e os logs/PID são gravados na **raiz do workspace** via `../` — os steps de parada os leem de lá. O `kill` é **melhor esforço**: `mvn spring-boot:run` pode deixar um processo filho órfão, mas o runner é destruído ao fim do job.

## 8. Comandos locais equivalentes

Reproduzir localmente o que a CI faz (exit 0 em cada bloco):

```bash
# backend-test
cd backend && mvn -B test          # ~11 min, requer Docker (Testcontainers)

# frontend-test
cd frontend && npm ci && \
  npm run lint && \
  npm run type-check && \
  npm run test && \
  npm run build
```

E2E (o mais próximo do job `e2e`, com servidores de pé — ver [frontend-e2e.md](frontend-e2e.md) §§2–3):

```bash
docker compose up -d --wait                       # PostgreSQL :5433
cd backend && SPRING_PROFILES_ACTIVE=test mvn -B spring-boot:run   # :8080
# (noutro terminal)
cd frontend && npm ci && NEXT_PUBLIC_API_URL=http://localhost:8080 npm run build && npm run start   # :3000
# (noutro terminal)
cd frontend && npm run test:e2e                   # usa Chrome do sistema localmente
```

Diferença local × CI: local usa `dev`/Chrome do sistema e o banco do compose continua de pé entre execuções; no CI é `start` (production), Chromium pinado e banco destruído a cada job.

## 9. Variáveis de teste do job `e2e` (e por que são seguras)

| Variável | Valor no workflow | Papel | Por que é segura |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `test` | desliga rate-limit e monitoring; mantém Flyway ligado | perfil de teste já usado pela suíte de backend; nunca é `prod` |
| `DATABASE_PASSWORD` | `sentinel` | senha do Postgres do compose | fixture que casa com o default do `docker-compose.yml` (`${DATABASE_PASSWORD:-sentinel}`); o container é **destruído com `-v`** ao fim do job |
| `JWT_SECRET` | `ci-e2e-test-secret-not-for-production-0123456789` | fixa o segredo HS256 (sem o warning de efêmero) | literal descartável, sem valor real, gravada só no log efêmero do runner; **não é secret do GitHub** e não protege nada — o banco e o processo morrem com o job |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | permite o browser do E2E falar com a API | mesmo valor do default; origem local efêmera |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | embute a URL da API no build do Next | endpoint local do próprio job |

Nenhuma dessas variáveis usa `secrets.*`; nenhuma credencial de produção existe no workflow. Os **usuários E2E** são criados pela própria suíte via `/register` com e-mail descartável `e2e-…@test.local` (regras em [frontend-e2e.md](frontend-e2e.md) §5).

## 10. Validação local dos YAMLs (actionlint)

Como o workflow foi validado nesta etapa (sem remote, sem run remoto):

| Verificação | Ferramenta | Resultado |
| --- | --- | --- |
| Lint do workflow | **actionlint 1.7.7** — binário oficial baixado de `https://github.com/rhysd/actionlint/releases/download/v1.7.7/actionlint_1.7.7_linux_amd64.tar.gz` para `/tmp/actionlint/` (**fora** do repo) | **0 erros, exit 0** |
| Scripts `run:` (bash) | **ShellCheck 0.10.0** — binário oficial (`koalaman/shellcheck` releases) integrado ao actionlint via `PATH` | **0 erros** após correções (ver abaixo) |
| Sintaxe YAML | `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"` | válido (atenção: no YAML 1.1 do PyYAML a chave `on` vira `True` — parser-only, sem efeito no GitHub) |
| Config do Playwright | `npx playwright test --list` com `CI` ausente **e** com `CI=true` | 8 specs / 14 testes listados nos dois modos, exit 0 |
| Frontend após o ajuste | `npm run type-check` + `npm run test` | exit 0 / **207 passed** |

Correções aplicadas para deixar o actionlint **limpo** (derivadas do ShellCheck, sem mudar o comportamento):

1. `for i in $(seq …)` → `for _ in $(seq …)` nos dois loops de readiness (SC2034 — `i` nunca era usado);
2. `[ -f x ] && kill … || true` → `if [ -f x ]; then kill … || true; fi` no step de parada (SC2015 — `A && B || C` não é if-then-else).

**Limitação**: o `pyflakes` não estava disponível no ambiente da validação (irrelevante — não há Python no workflow). Nenhuma validação foi executada **no GitHub**: não existe remote.

## 11. Limitações conhecidas

| Limitação | Detalhe |
| --- | --- |
| **Não executado no GitHub** | zero runs — o workflow foi validado só localmente (§10); a primeira execução remota ainda pode revelar diferenças de ambiente (versão do Maven/Compose do runner, cota de minutos) |
| Surefire *fork-kill warning* | o `mvn -B test` local emite o aviso preexistente de fork/kill do Surefire (~30 s de espera ao final) — **pré-existente**, não introduzido pela CI; não falha o build |
| `npm audit` com vulnerabilidades | o `audit` do frontend aponta vulnerabilidades **pré-existentes** em dependências (baseline desta etapa: **11** — 1 moderate, 8 high, 2 critical, majoritariamente via cadeia de devDependencies) — fora do escopo desta etapa; a CI **não** roda `audit` como gate (por enquanto) |
| E2E só em PR | `if: github.event_name == 'pull_request'` — push direto em `main` não roda E2E; push sem PR também não tem o "só merged green" garantido pela plataforma sem branch protection |
| Artefato `playwright-report` pode vir vazio | o `reporter` do config é apenas `[["list"]]` — sem o reporter HTML não há `frontend/playwright-report/`; o step usa `if-no-files-found: ignore` e por isso **não falha**. Adicionar o reporter HTML (e o reporter `blob`/`github`) é candidato a melhoria futura, fora do escopo desta etapa |
| Sem cache de `~/.npm` além do setup-node | o cache npm do `setup-node` cobre o download de tarballs; o `next build` e o `playwright install` não têm cache dedicado (instalação de chromium ~1–2 min por run do `e2e`) |
| `kill` melhor esforço | encerrar `mvn spring-boot:run`/`next start` por PID pode deixar filhos órfãos até o runner morrer — aceito num runner efêmero |
| Branch protection não configurada | a CI sozinha **não** bloqueia merge — é preciso marcar os jobs como required checks no GitHub depois da ativação (§12) |

## 12. Como ativar a CI futuramente (passos de alto nível — **não executados**)

Nada abaixo foi feito nesta etapa (não há remote e o usuário não autorizou push):

1. Criar o repositório no GitHub (privado recomendado) e copiar a URL.
2. Localmente: `git remote add origin <url>` e `git push -u origin main` (autorização explícita necessária).
3. Acompanhar a primeira execução na aba **Actions** do repo (§13).
4. Em **Settings → Branches → Branch protection** (main): marcar `Backend Test`, `Frontend Test` (e `E2E (Playwright)` quando já houver histórico verde em PR) como **Required status checks** + "Require status checks to pass before merging". Sem esse passo, a CI roda mas **não bloqueia** nada.
5. (Opcional, natural depois) ativar Dependabot/Renovate para a política de pinning da §5 e adicionar reporter HTML ao Playwright para enriquecer o artefato da §11.

## 13. Como verificar a primeira execução remota

Após o push (§12):

1. **Aba Actions** do repositório: o workflow `CI` deve aparecer com 2 execuções (push em `main` e/ou o PR) — na do PR, os três jobs; na do push, dois.
2. Em cada job: os steps na ordem do §2/§7; step vermelho ⇒ abrir o log; no `e2e`, em falha do readiness o log do backend aparece no step (`backend-run.log`).
3. **Artefatos** no fim da execução: `surefire-reports` (só se o backend falhou) e `playwright-report` (pode vir vazio — §11); ambos expiram em 7 dias.
4. Na página do PR: os checks devem refletir os mesmos três jobs; com branch protection ativa, merge fica bloqueado enquanto qualquer um estiver vermelho.
5. Esperado na primeira execução fria: `backend-test` ~10–15 min (pull da imagem + download de dependências), `frontend-test` ~3–5 min, `e2e` ~12–20 min (compose + build + chromium + suíte).

## 14. Troubleshooting

| Sintoma | Causa provável | Ação |
| --- | --- | --- |
| E2E falha com **429** ou erros de autenticação em rajada | backend sem o perfil `test` (rate-limit ligado) | confirmar `SPRING_PROFILES_ACTIVE=test` no step "Start backend"; localmente, o mesmo perfil (`mvn spring-boot:run -Dspring-boot.run.profiles=test`) |
| Playwright: **browser type 'chrome' not found** / `executable doesn't exist` | rodando com `CI=true` sem instalar chromium, ou local sem Chrome do sistema | CI: o step `npx playwright install --with-deps chromium` falhou/foi pulado; local: instalar o Chrome **ou** unsetar `CI` |
| Step "Wait for backend readiness" estoura 180 s | backend não subiu | ler `backend-run.log` despejado no step; verificar porta 8080 ocupada, migration Flyway divergente, Docker/Postgres de pé |
| `docker compose up -d --wait` falha | compose v1 no PATH, ou healthcheck nunca healthy | runners `ubuntu-latest` trazem Compose v2; fallback manual: `docker compose up -d` + loop de `pg_isready -U sentinel -d sentinel` na `5433` |
| Testcontainers não acha o Docker / porta | Docker daemon indisponível | no GitHub não é esperado; localmente, subir o Docker antes do `mvn -B test` |
| Job `backend-test` ou `e2e` atinge o `timeout-minutes` | cache Maven frio + 867 testes, ou build + chromium + suíte num run congestionado | re-run (cache quente resolve a maior parte); se persistir, aumentar o timeout do job de forma consciente |
| `npm ci` falha | lockfile divergente do `package.json` | regenerar o `package-lock.json` localmente e commitar — a CI usa `npm ci` (reprodutibilidade estrita) |
| Checks rodam mas o merge continua livre | branch protection não configurada | §12 passo 4 |
