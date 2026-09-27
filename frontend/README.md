# Sentinel — Frontend

SPA em **Next.js 16 (App Router)** que consome a API do backend Spring Boot em
`http://localhost:8080`. Telas: home (sessão + organizações, membros,
**projetos** e **serviços**), login e register. O backend é sempre a autoridade de
autenticação e de tenant — o que existe aqui é apenas estado de UI.

**Stack:** Next.js 16.3.6 · React 19 · TypeScript 5 · Tailwind CSS 4 · ESLint 9 · Vitest 3

## Scripts

```bash
npm install       # dependências
npm run dev       # dev server em http://localhost:3000
npm run lint      # eslint
npm run type-check# tsc --noEmit
npm run build     # build de produção
npm run start     # serve o build de produção
npm test          # vitest run — testes unitários de src/lib (environment node)
```

## Configuração

| Variável              | Default                 | Descrição                       |
| --------------------- | ----------------------- | ------------------------------- |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Base URL da API do backend       |

Copie `.env.example` para `.env.local` para sobrescrever. Portas: frontend `3000`,
backend `8080`, PostgreSQL `5433` (docker-compose na raiz do repositório).

## Autenticação (resumo)

- **Access token:** em memória (variável de módulo) — nunca em `localStorage`/`sessionStorage`.
- **Refresh token:** cookie **HttpOnly** emitido e rotacionado pelo backend; o JS não o lê.
- **CSRF:** cookie `XSRF-TOKEN` (legível por JS) reenviado no header `X-XSRF-TOKEN` em POST/PUT/PATCH/DELETE.
- **401** em qualquer chamada dispara `SESSION_EXPIRED_EVENT` → limpa token e organização ativa; **403** não.
- **Organização ativa:** UUID em memória + `localStorage` (`sentinel.activeOrganizationId`) — é
  preferência de UI, sempre validada contra a lista de orgs do usuário e com formato UUID conferido.
  O backend continua sendo quem decide membership/tenant.

Toda a lógica fica em [`src/lib/api.ts`](src/lib/api.ts).

## Projetos (Prompt 06)

Seção de projetos na home, visível apenas com organização selecionada: lista
paginada (anterior/próxima + total), busca por nome com debounce, filtro de
status, criação, edição inline (expansão da linha) e ativar/desativar.
`role = VIEWER` é somente-leitura no cliente (controles mutáveis bloqueados);
um **403** vira mensagem de erro na UI — nunca logout. Cliente:
`listProjects`, `createProject`, `getProject`, `updateProject` e
`updateProjectStatus` em [`src/lib/api.ts`](src/lib/api.ts).

## Serviços (Prompt 07)

Serviços ficam **dentro do projeto expandido** na home (organização ativa →
projetos → linha de detalhe do projeto → `ServicesPanel`): lista paginada
(anterior/próxima + total), busca por nome com debounce, filtros de ativação e
de status, criação `{name, description?}`, edição inline e ativar/desativar via
`PATCH .../services/{serviceId}/activation` (**idempotente** — mexe em `active`,
nunca no `status` operacional). Badges são distintos: `active`/`Inactive`
(ciclo de vida) × `Operational`/`Degraded`/`Unavailable`/`Unknown` (o `status`
real que a API devolve — exibido como chega, sem simular disponibilidade).
Projeto inativo mantém a leitura e exibe aviso; mutações bloqueadas pelo
backend aparecem como **409** `PROJECT_INACTIVE`/`SERVICE_NAME_DUPLICATE` na
UI, e **403** vira mensagem — nunca logout. Cliente: `listServices`,
`createService`, `getService`, `updateService` e `activateService` em
[`src/lib/api.ts`](src/lib/api.ts).

## Estrutura

```
src/
├── app/            # rotas: page.tsx (home: sessão, organizações, membros, projetos e serviços), login/, register/
├── components/     # AuthStatus (sessão), HealthStatus (backend up?), ServicesPanel (serviços do projeto)
└── lib/            # api.ts (cliente) + errors.ts (helpers de erro) + api/projects/services.test.ts (vitest)
```

Documentação do projeto: [README raiz](../README.md) e [docs/](../docs/)
(`identity.md` = contrato de auth, `organizations.md` = contrato multi-tenant).
