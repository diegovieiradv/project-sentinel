# Frontend E2E (Playwright) — Sentinel

> **Etapa Pós-Roadmap 01** — como executar, revisar e manter a suíte E2E de navegador do frontend. Documento **operacional**: o que é, o que ela cobre, o que ela precisa para rodar e onde ela **para**. Status detalhado da etapa (correções de a11y, gate, ressalvas) em [homologation-readiness.md](homologation-readiness.md) §10.

## 1. O que é

Suíte **end-to-end de navegador** do Sentinel, rodando com **Playwright** (`@playwright/test@1.64.0`, devDependency) contra a aplicação **real**: frontend Next.js dev em `http://localhost:3000` falando com o **backend Spring Boot real** em `http://localhost:8080` (banco real, auth real, CSRF real). Diferente dos 207 testes unitários (`npm run test`, Vitest em `environment: "node"` — sem renderização de DOM), aqui o que é exercitado é o **fluxo no navegador**: digitar nos forms, navegar por teclado (Tab), trocar de organização, expandir projeto — com as correções de acessibilidade da etapa protegidas por asserção (A11Y-01 focus-visible, A11Y-02 alvos ≥ 44 px, A11Y-03 `prefers-reduced-motion`).

- **Config**: [`frontend/playwright.config.ts`](../frontend/playwright.config.ts) — `testDir ./e2e`, `baseURL http://localhost:3000`, `channel: "chrome"`, `retries: 1`, `workers: 2`.
- **Specs**: [`frontend/e2e/`](../frontend/e2e/) — **8 specs / 14 testes**.
- **Scripts** (em `frontend/package.json`): `test:e2e`, `test:e2e:headed`, `test:e2e:ui`.
- **Sem `webServer` no config**: os servidores sobem **fora** do Playwright (§2). O runner só aponta para o `baseURL`.

## 2. Pré-requisitos

| # | Requisito | Detalhe |
| --- | --- | --- |
| 1 | **Node 20.9+** | requisito do toolchain do frontend (ambiente da etapa: Node 22) |
| 2 | **Backend de pé em `:8080`** | `docker compose up -d` (PostgreSQL 17 na `5433`) + `cd backend && mvn spring-boot:run` |
| 3 | **Frontend dev em `:3000`** | `cd frontend && npm run dev` |
| 4 | **Chrome do sistema instalado** | a config usa `channel: "chrome"` — o Playwright **não** baixa browser próprio; ele usa o Chrome já instalado no SO |

Sem **todos** os quatro, a suíte falha por infraestrutura (connection refused / browser não encontrado), não por defeito de produto.

## 3. Como executar

```bash
cd frontend

npm run test:e2e          # headless (padrão)
npm run test:e2e:headed   # abre o Chrome visível
npm run test:e2e:ui       # UI interativa do Playwright
```

**Checagem de estabilidade** (sem retry, repetindo cada teste 3× — é assim que o gate da etapa foi fechado):

```bash
npx playwright test --retries=0 --repeat-each=3
```

Resultado no gate da etapa: **14/14** em execução única e **42/42** estável (14 × 3) — ver [homologation-readiness.md](homologation-readiness.md) §10.

Filtros úteis: `npx playwright test e2e/a11y` (só a11y), `npx playwright test login.spec.ts` (só um arquivo).

## 4. Estrutura da suíte (8 specs / 14 testes)

| Spec | Testes | Cenários cobertos |
| --- | --- | --- |
| `e2e/auth/register.spec.ts` | 2 | registro válido redireciona para `/login` (sem `role="alert"` residual); senha fraca rejeitada pela política do backend (`INVALID_PASSWORD`) com a mensagem exata e permanência em `/register` |
| `e2e/auth/login.spec.ts` | 2 | credenciais válidas entram na home autenticada (`Signed in as …`, botão `Sign out`, seção Organizations); credenciais inválidas mostram `Invalid email or password` e **permanecem** em `/login` |
| `e2e/auth/logout.spec.ts` | 1 | `Sign out` devolve a home ao estado anônimo imediato + **recarga** segue anônimo (refresh token revogado/limpo) |
| `e2e/a11y/focus-visible.spec.ts` | 2 | **A11Y-01**: Tab no form de login aplica o anel `focus-visible` (e o outline continua removido, sem "anel duplo"); sanidade em runtime — nenhum elemento fica com `outline: none` **sem** expor `box-shadow` ao receber foco por teclado |
| `e2e/a11y/touch-targets.spec.ts` | 3 | **A11Y-02**: altura real ≥ 44 px de todo elemento interativo em `/login` e em `/register` (medição da `boundingBox` dentro de `<main>`); viewport mobile 375 px sem overflow horizontal nas duas rotas |
| `e2e/a11y/reduced-motion.spec.ts` | 2 | **A11Y-03**: com `prefers-reduced-motion: reduce`, transições caem para ~0,01 ms (controle sem a preferência = 150 ms) e `scroll-behavior` vira `auto` |
| `e2e/orgs/select-org.spec.ts` | 1 | criar 2ª organização **não** troca a ativa; selecionar a segunda re-renderiza `Members — {org}` / `Projects — {org}` com ela; a primeira continua listada |
| `e2e/projects/expand-project.spec.ts` | 1 | botão `Details` (`aria-expanded` false → true) monta `Dashboard` e `Services — {project}` inline; recolher desmonta os painéis |

Apoio comum: [`frontend/e2e/helpers.ts`](../frontend/e2e/helpers.ts) — `register`, `login`, `registerAndLogin`, `ensureOrganization`, `uniqueEmail`, `uniqueName`, `VALID_PASSWORD`, `WEAK_PASSWORD`.

## 5. Dados de teste (regras de segurança da suíte)

| Regra | Como está implementado |
| --- | --- |
| Usuários **descartáveis**, um por execução | criados via `/register` com e-mail único `e2e-…@test.local` (`uniqueEmail()`, com timestamp + sufixo aleatório) |
| Senha fixture | `SentinelE2e123` (`VALID_PASSWORD`) — satisfaz a política do backend (8+ chars, letra + dígito) |
| Senha fraca de propósito | `abcdefgh` (`WEAK_PASSWORD`) — passa no `minLength` nativo do HTML e é rejeitada pelo backend |
| **NUNCA** credenciais reais | nenhum par de credencial real entra na suíte; o cenário de login inválido usa e-mail inexistente gerado na hora |
| **NUNCA** desabilitar CSRF/CORS/auth/RBAC/SSRF | os testes falam com o backend **real**, com todas as proteções ligadas — desligá-las seria matar justamente o que se quer exercitar |
| Recursos também descartáveis | organizações/projetos recebem nome único `E2E {prefix} {timestamp}` (`uniqueName()`) |

## 6. Screenshots (evidência visual)

- Local: [`frontend/e2e/screenshots/`](../frontend/e2e/screenshots/) — **8 PNGs** versionados como artefato de revisão (desktop + mobile 375 px):

  | # | Arquivo | O que mostra |
  | --- | --- | --- |
  | 01 | `01-login-no-focus.png` | login sem foco (estado neutro) |
  | 02 | `02-login-focus-visible-email.png` | anel `focus-visible` no campo de e-mail |
  | 03 | `03-login-error-alert.png` | `role="alert"` de erro de login |
  | 04 | `04-register-weak-password-alert.png` | alerta de senha fraca no registro |
  | 05 | `05-home-organization-selected.png` | home com organização selecionada |
  | 06 | `06-home-organization-switched.png` | home após trocar de organização |
  | 07 | `07-project-expanded-dashboard-services.png` | projeto expandido com Dashboard + Services |
  | 08 | `08-login-mobile-375.png` | login em viewport mobile 375 px |

- **Regenerar** (servidores de dev já de pé em `:3000`/`:8080`):

  ```bash
  cd frontend && node e2e/capture-screenshots.mjs
  ```

  O script (`playwright-core`) cria usuários/orgs/projetos descartáveis (`e2e-shot-*@test.local`) e grava os PNGs no mesmo diretório.

## 7. Limitações conhecidas

| Limitação | Detalhe |
| --- | --- |
| **Rotas cobertas** | o roteador real do app expõe só `/`, `/login`, `/register` — os painéis (Members, Projects, Services, Dashboard) são exercitados **dentro da home**; não existem rotas independentes por painel |
| **Infraestrutura de pé** | depende de backend `:8080` + frontend dev `:3000` + Chrome do sistema **antes** de rodar; não há `webServer` automático no config |
| **Sem CI** | `.github/` não tem workflow — a suíte roda **só localmente**; nada a executa automaticamente em PR (bloqueador #11 da homologation-readiness §4) |
| **`channel: "chrome"`** | usa o Chrome do sistema em vez do browser empacotado/pinado pelo Playwright — melhora reprodutibilidade vs. download, mas a versão **não é fixada pelo Playwright** (upgrade do Chrome do SO pode mudar o resultado) |
| **Sem `test.skip`** | nenhum teste está skippado; painéis que exigem dados de domínio que a suíte não provisiona (**alerts, incidents, métricas** com checagens reais em andamento) ficaram **fora do escopo da automação** — esses fluxos foram exercitados manualmente na homologação P18 ([homologation-readiness.md](homologation-readiness.md) §9.1), não aqui |
| **Ressalva de UI aceita** | 2 ocorrências de `h-4 w-4` em checkboxes ficam abaixo de 44 px (exceção justificada — ver §10 da homologation-readiness) |
| **Screenshot na falha** | `screenshot: "only-on-failure"` + `trace: "on-first-retry"` — artefatos gerados em `playwright-report/`/`test-results/` (ignorados pelo git); os 8 PNGs versionados são os da §6 |

## 8. Adicionar ao pipeline local (antes do commit)

Ordem sugerida — todos em `frontend/`, exit code 0 em cada etapa:

```bash
cd frontend && \
  npm run lint && \
  npm run type-check && \
  npm run test && \
  npm run build && \
  npm run test:e2e
```

Backend, quando a mudança toca a API:

```bash
cd backend && mvn clean test   # 865 testes, 0 falhas (baseline da etapa)
```

> A suíte E2E precisa dos servidores de pé (§2) — ela é o **último** passo porque é o mais lento e o mais dependente de infraestrutura.
