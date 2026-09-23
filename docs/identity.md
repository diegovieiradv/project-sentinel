# Identity — Sentinel

> Prompt 02 de 18. Este documento descreve **o que está construído**: autenticação por sessão HTTP transitória, cadastro de usuários e checagem de sessão. Autorização (RBAC), multi-tenancy, JWT/refresh e organizações continuam fora do escopo.

## 1. Escopo implementado

| Item | Estado |
| --- | --- |
| Migration `V2__create_users_table.sql` (tabela `users`) | Construído |
| `POST /api/v1/auth/register` (BCrypt, sem sessão) | Construído |
| `POST /api/v1/auth/login` (sessão `JSESSIONID`) | Construído |
| `GET /api/v1/auth/me` (sessão atual) | Construído |
| 404 padronizado para rota desconhecida (sem autenticar) | Construído |
| CORS com `allowCredentials` + `credentials: "include"` no FE | Construído |
| JWT / refresh token / Organization / Membership / RBAC / dashboard | **Fora do escopo** |

## 2. Endpoints

Base: `http://localhost:8080`

### `POST /api/v1/auth/register` → `201`

Request:

```json
{ "name": "Ada Lovelace", "email": "ada@example.com", "password": "secret123" }
```

Response `201`:

```json
{ "user": { "id": "uuid", "name": "Ada Lovelace", "email": "ada@example.com" } }
```

- Não cria sessão — o cliente deve chamar `login` em seguida.
- Email é normalizado (`trim` + `toLowerCase`) e gravado único no banco.
- Política de senha: **mín. 8 caracteres, ≥1 letra e ≥1 dígito**.

### `POST /api/v1/auth/login` → `200`

Request:

```json
{ "email": "ada@example.com", "password": "secret123" }
```

Response `200` (sessão iniciada via cookie `Set-Cookie: JSESSIONID=...; HttpOnly; Path=/`):

```json
{ "user": { "id": "uuid", "name": "Ada Lovelace", "email": "ada@example.com" }, "tokenType": "session" }
```

- Falha (senha errada, usuário inexistente ou inativo) → **sempre** `401 INVALID_CREDENTIALS` com a mesma mensagem — sem enumeração de contas.

### `GET /api/v1/auth/me` → `200` | `401`

- Com sessão válida → `200` `{ "id", "name", "email" }`.
- Sem sessão (ou sessão expirada) → `401 UNAUTHORIZED`.
- Nenhum campo de senha/hash é retornado (`@JsonIgnore` na entidade + DTO explícito).

## 3. Contrato de erro

Shape padrão (já estabelecido no Prompt 01):

```json
{ "code": "EMAIL_ALREADY_EXISTS", "message": "...", "timestamp": "...", "path": "/api/v1/auth/register" }
```

| Código | HTTP | Quando |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Payload inválido (email em branco/`@Email`, name em branco, etc.) |
| `INVALID_PASSWORD` | 400 | Senha fora da política (8+, letra + dígito) |
| `INVALID_CREDENTIALS` | 401 | Login com senha errada, usuário inexistente ou inativo |
| `UNAUTHORIZED` | 401 | `GET /me` sem sessão; qualquer rota protegida sem sessão (entry point) |
| `EMAIL_ALREADY_EXISTS` | 409 | Email já cadastrado |
| `NOT_FOUND` | 404 | Rota desconhecida (handler cobre `NoResourceFoundException` e `NoHandlerFoundException`) |
| `INTERNAL` | 500 | Erros inesperados |

Mensagens de credenciais são **idênticas** para todos os casos de login inválido: `"Invalid email or password"`.

## 4. Sessão, segurança e CORS

- **Sessão HTTP transitória**: Spring Security grava o `SecurityContext` via `SecurityContextRepository` explícito no login (cookie `JSESSIONID`, `HttpOnly`). Não há JWT nem refresh token nesta fase.
- **CSRF desabilitado** nesta fase com justificativa no código: o único mutating público é o próprio login/register com JSON via `fetch`, e o cookie de sessão não é enviado em chamadas cross-site de navegador (não há ainda endpoints que mudam estado para terceiros). Será revisitado quando houver sessão com valor real de mutação por terceiros.
- **BCrypt** (`BCryptPasswordEncoder`) — senha nunca sai do backend (nem em resposta, nem em log).
- **CORS** único no `SecurityConfig` (`corsConfigurationSource`): origem exata `http://localhost:3000`, `allowCredentials(true)`, métodos `GET, POST, PUT, DELETE, OPTIONS`, preflight cache 3600s. Preflight `OPTIONS` e requisições de origem estrangeira são tratadas pelo `DefaultCorsProcessor` (origem não permitida → 403 "Invalid CORS request", esperado).
- **Frontend** usa `credentials: "include"` em todas as chamadas autenticadas (`api.ts`).

## 5. Estrutura de código (backend)

```
com.sentinel
├── identity
│   ├── SecurityConfig.java          # filter chain, CORS, entry point 401/403, beans
│   ├── auth
│   │   ├── AuthController.java      # /register /login /me
│   │   ├── RegisterRequest.java     # DTOs de entrada (@Valid)
│   │   ├── LoginRequest.java
│   │   ├── UserResponse.java        # DTOs de saída (sem senha/hash)
│   │   └── AuthResponse.java
│   └── user
│       ├── User.java                # entidade `users` + UserDetails
│       ├── UserRepository.java
│       └── UserService.java         # register (política/BCrypt/409) + authenticate (401)
├── error/GlobalExceptionHandler.java  # + handler 404
└── config/                          # async (WebMvcConfig/CORS antigo removido)
```

- Ciclo evitado: `AuthController` chama `UserService.authenticate` diretamente (usa `PasswordEncoder.matches`), **não** `AuthenticationManager` — sessão é estabelecida no controller via `SecurityContextRepository.saveContext`.
- `User implements UserDetails`: `active=false` → `isEnabled()=false` → login rejeitado como credencial inválida.

## 6. Frontend

| Rota | Comportamento |
| --- | --- |
| `/` | Home — `AuthStatus` consulta `GET /me` real (loading → anônimo/authed/erro) e `HealthStatus` |
| `/register` | Formulário → `POST /register` → redireciona para `/login` |
| `/login` | Formulário → `POST /login` (cookie) → redireciona para `/` |

`src/lib/api.ts` centraliza `register/login/me/checkHealth` com `credentials: "include"` e converte o shape de erro em `ApiError { status, code, message }`.

## 7. Testes

- `mvn test` — 32 testes, 0 falhas:
  - `AuthIntegrationTest` (20): fluxos register/login/me, normalização de email, BCrypt no banco, 401/404/400/409, sessão, CORS preflight e origem estrangeira, `/me` sem sessão.
  - `UserRepositoryTest` (5): persistência, `existsByEmail`, violação de unique (`DataIntegrityViolationException`), `@PreUpdate` de `updated_at`.
  - `GlobalExceptionHandlerTest` (5): Prompt 01 + cobertura dos dois tipos de 404.
  - Prompt 01: `HealthControllerTest`, `SentinelApplicationTests` — inalterados.
- Frontend: `npm run lint && npm run type-check && npm run build` — todos em 0.

## 8. Fora do escopo (prompts futuros)

- JWT / refresh token / revogação de sessão persistente.
- RBAC (papéis/permissoes), recuperação de senha, verificação de email, MFA.
- Organization / Membership / multi-tenancy, dashboard fake.
