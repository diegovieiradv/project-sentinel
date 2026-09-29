/**
 * Minimal API client for Sentinel frontend.
 *
 * Auth (Prompt 03): access token em memória + refresh token em cookie HttpOnly + CSRF.
 * Multi-tenancy (Prompt 04): org ativa em memória + UUID persistido no localStorage
 * (`sentinel.activeOrganizationId`), sempre validado contra a lista de orgs do usuário.
 * O valor (memória e storage) também precisa ter formato UUID válido — malformado é
 * tratado como ausente e removido do storage (saneamento, não autorização).
 *
 * - Access token: guardado em variável de módulo (memória somente). NUNCA em localStorage/sessionStorage/cookie próprio.
 * - Refresh token: cookie HttpOnly do navegador — o JS nunca o lê nem manipula.
 * - CSRF: cookie `XSRF-TOKEN` (legível por JS, HttpOnly=false, Path=/). Todo POST/PUT/DELETE/PATCH deve enviar header `X-XSRF-TOKEN`.
 *   Se o cookie não existir em document.cookie, chama `ensureCsrfToken()` que faz `GET /api/v1/health` para materializá-lo.
 *   O header `Authorization: Bearer <accessToken>` é injetado pelo `apiFetch` quando houver token.
 * Todas as chamadas utilizam `credentials: "include"` (padrão do `apiFetch`).
 *
 * Erros da API no shape `{code, message, timestamp, path}` são expostos
 * como `ApiError` (status + code + message legível).
 */

export const API_URL =
  process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

/** Contrato de saída de usuário — backend nunca envia password/hash. */
export interface UserResponse {
  id: string;
  name: string;
  email: string;
}

/** Papéis de organização — espelha o enum `Role` do backend (ADMIN/OPERATOR/VIEWER). */
export type OrganizationRole = "ADMIN" | "OPERATOR" | "VIEWER";

/** Resposta de organização do backend (contrato de `OrganizationResponse` do backend). */
export interface OrganizationResponse {
  id: string;
  name: string;
  createdAt: string;
  updatedAt: string;
  /** Papel do usuário autenticado NA organização (obrigatório no contrato). */
  role: OrganizationRole;
}

/** Resposta de membro de organização (payload admin-only com e-mails). */
export interface MemberResponse {
  membershipId: string;
  userId: string;
  name: string;
  email: string;
  role: OrganizationRole;
  createdAt: string;
}

/** Resposta de projeto do backend (contrato de `ProjectResponse`). */
export interface ProjectResponse {
  id: string;
  organizationId: string;
  name: string;
  description: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  /** Papel do usuário autenticado NA organização dona do projeto. */
  role: OrganizationRole;
}

/**
 * Corpo de criação — APENAS `name`/`description`.
 * id, organizationId, active e timestamps NUNCA são aceitos no body (o backend
 * os preenche a partir do path/autenticação).
 */
export interface ProjectCreateRequest {
  name: string;
  description?: string;
}

/** Corpo de edição (PATCH) — parcial: campos ausentes permanecem inalterados. */
export interface ProjectUpdateRequest {
  name?: string;
  description?: string;
}

/** Corpo do PATCH .../status — idempotente. */
export interface ProjectStatusRequest {
  active: boolean;
}

/**
 * Parâmetros da listagem (Spring Page). `undefined`/string vazia é omitido da
 * query string; boolean vira `"true"`/`"false"`.
 */
export interface ProjectListParams {
  page?: number;
  size?: number;
  active?: boolean;
  name?: string;
  sort?: string;
}

/** Página Spring (`Page<ProjectResponse>`): fatia atual + totais. */
export interface ProjectPage {
  content: ProjectResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** Status operacional de um serviço — espelha o enum `ServiceStatus` do backend. */
export type ServiceStatus = "OPERATIONAL" | "DEGRADED" | "UNAVAILABLE" | "UNKNOWN";

/** Resposta de serviço do backend (contrato de `ServiceResponse`). */
export interface ServiceResponse {
  id: string;
  organizationId: string;
  projectId: string;
  name: string;
  description: string | null;
  active: boolean;
  /** Status operacional REAL vindo da API — o cliente nunca o simula. */
  status: ServiceStatus;
  createdAt: string;
  updatedAt: string;
  /** Papel do usuário autenticado NA organização dona do serviço. */
  role: OrganizationRole;
}

/**
 * Corpo de criação — APENAS `name`/`description`.
 * id, organizationId, projectId, active, status e timestamps NUNCA entram no
 * body (o backend os preenche a partir do path/autenticação).
 */
export interface ServiceCreateRequest {
  name: string;
  description?: string;
}

/** Corpo de edição (PATCH) — parcial: campos ausentes permanecem inalterados. */
export interface ServiceUpdateRequest {
  name?: string;
  description?: string;
}

/**
 * Corpo do PATCH `.../services/{serviceId}/activation` — idempotente.
 * `active` é o ciclo de vida do cadastro, NÃO o `status` operacional
 * (por isso o endpoint se chama `activation`).
 */
export interface ServiceActivationRequest {
  active: boolean;
}

/**
 * Parâmetros da listagem de serviços (Spring Page). `undefined`/string vazia é
 * omitido da query string; boolean vira `"true"`/`"false"`; `status` filtra o
 * status operacional (`active` é o filtro de ciclo de vida — coisas distintas).
 */
export interface ServiceListParams {
  page?: number;
  size?: number;
  active?: boolean;
  status?: ServiceStatus;
  name?: string;
  sort?: string;
}

/** Página Spring (`Page<ServiceResponse>`): fatia atual + totais. */
export interface ServicePage {
  content: ServiceResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** Inputs */
export interface RegisterInput {
  name: string;
  email: string;
  password: string;
}

export interface LoginInput {
  email: string;
  password: string;
}

/** Shape padrão de erro da API: {code, message, timestamp, path}. */
interface ApiErrorBody {
  code?: string;
  message?: string;
  timestamp?: string;
  path?: string;
}

/**
 * Erro de API — única classe do módulo (os consumidores testam `err instanceof ApiError`).
 * Uma herança dupla (pai + filha) faria o `throw` da pai falhar nesse teste.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly path?: string;

  constructor(status: number, code: string, message: string, path?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.path = path;
  }
}

/**
 * Access token em memória — único lugar onde o token "vivo" existe no cliente.
 * É lido por `apiFetch` (via `getAccessToken()`) e atualizado por `login()`/`refreshSession()`.
 */
let accessToken: string | null = null;

/** Obtém o token atual (para o apiFetch injetar no header Authorization). */
export function getAccessToken(): string | null {
  return accessToken;
}

/** Define o token em memória (chamado por login/refresh após sucesso). */
export function setAccessToken(token: string | null): void {
  accessToken = token;
}

/** Limpa o token em memória (chamado por logout e após refresh 401). */
export function clearAccessToken(): void {
  accessToken = null;
}

/** UUID da organização ativa em memória (Prompt 04). */
let activeOrganizationId: string | null = null;

/** Chave no localStorage para persistir a org ativa entre sessões. */
const ACTIVE_ORG_LOCAL_STORAGE_KEY = "sentinel.activeOrganizationId";

/**
 * Formato canônico de UUID (8-4-4-4-12 hex, qualquer versão/variante) — o mesmo
 * que `UUID.fromString` do backend aceita.
 */
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Valida o **formato** de um UUID de organização (helper puro — sem window,
 * storage nem rede). Aceita qualquer versão/variante; `typeof` cobre entradas
 * não-string vindas de fora do TypeScript.
 *
 * Isso é saneamento de dados, NÃO um mecanismo de segurança: o backend é a
 * autoridade de membership/tenant (`TenantInterceptor` valida org/path/header).
 */
export function isValidOrganizationId(value: unknown): boolean {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

/**
 * Evento de browser disparado por `apiFetch` quando a API responde **401**.
 * `AuthStatus` o escuta para limpar a sessão em memória e assumir estado anônimo
 * (um 401 em qualquer chamada derruba a sessão inteira, não só a chamada que falhou).
 */
export const SESSION_EXPIRED_EVENT = "sentinel:session-expired";

/** Persiste o UUID da org ativa no localStorage (ou remove). */
function persistActiveOrgId(id: string | null): void {
  if (typeof window === "undefined") return;
  if (id) {
    try {
      localStorage.setItem(ACTIVE_ORG_LOCAL_STORAGE_KEY, id);
    } catch {
      // Ignorar erros de storage (ex.: modo privado sem persistência)
    }
  } else {
    try {
      localStorage.removeItem(ACTIVE_ORG_LOCAL_STORAGE_KEY);
    } catch {
      // Ignorar erros de storage
    }
  }
}

/**
 * Obtém a org ativa: primeiro memória, depois localStorage.
 * Valores malformados são tratados como ausentes (retornam `null`) e removidos
 * do localStorage — limpeza defensiva contra entrada corrompida/editada à mão.
 */
export function getOrganizationId(): string | null {
  if (activeOrganizationId) {
    if (isValidOrganizationId(activeOrganizationId)) return activeOrganizationId;
    // Malformado em memória → ausente, e limpa também a chave persistida.
    activeOrganizationId = null;
    persistActiveOrgId(null);
    return null;
  }
  if (typeof window !== "undefined") {
    try {
      const stored = localStorage.getItem(ACTIVE_ORG_LOCAL_STORAGE_KEY);
      if (stored) {
        if (isValidOrganizationId(stored)) return stored;
        // Valor malformado persistido → descarta e remove a chave.
        localStorage.removeItem(ACTIVE_ORG_LOCAL_STORAGE_KEY);
      }
    } catch {
      // Ignorar erros de leitura de storage
    }
  }
  return null;
}

/**
 * Define a org ativa em memória e persiste no localStorage.
 * Entrada malformada nunca entra na memória — equivale a limpar (`null`).
 */
export function setOrganizationId(id: string | null): void {
  if (id !== null && !isValidOrganizationId(id)) {
    clearOrganizationId();
    return;
  }
  activeOrganizationId = id;
  persistActiveOrgId(id);
}

/** Limpa a org ativa da memória e do localStorage. */
export function clearOrganizationId(): void {
  activeOrganizationId = null;
  persistActiveOrgId(null);
}

/**
 * Lê `document.cookie` no momento da chamada e devolve o mapa nome→valor.
 * Sempre reler aqui: `document.cookie` é snapshot da leitura anterior e muda
 * após cada `Set-Cookie` (ex.: XSRF-TOKEN emitido pelo /health).
 */
function readCookieMap(): Record<string, string> {
  const cookies: Record<string, string> = {};
  const cookieList = (document.cookie || "").split(";");
  for (const cookie of cookieList) {
    const [name, ...valueParts] = cookie.split("=");
    const trimmedName = name.trim();
    if (trimmedName) {
      cookies[trimmedName] = valueParts.join("=");
    }
  }
  return cookies;
}

/**
 * Obtém o valor (URL-decodificado) do cookie XSRF-TOKEN — o Spring pode gravá-lo
 * encoded. Retorna `null` quando o cookie não está presente.
 */
function readXsrfToken(): string | null {
  const raw = readCookieMap()["XSRF-TOKEN"];
  if (!raw) return null;
  return decodeURIComponent(raw);
}

/**
 * Garante que o cookie XSRF-TOKEN está presente em document.cookie.
 * Se não estiver, faz GET /api/v1/health (o backend o emite em todas as respostas MVC).
 * Retorna o valor do token para uso imediato no header X-XSRF-TOKEN.
 */
export async function ensureCsrfToken(): Promise<string> {
  const existing = readXsrfToken();
  if (existing) {
    return existing;
  }

  // Cookie não existe — tenta materializar fazendo uma requisição que o backend
  // responde com o set-cookie XSRF-TOKEN (todos os endpoints MVC o emitem, inclusive /health).
  const res = await fetch(`${API_URL}/api/v1/health`, {
    cache: "no-store",
    credentials: "include",
    headers: {
      Accept: "application/json",
    },
  });

  if (!res.ok) {
    throw new Error(
      `Could not obtain XSRF-TOKEN: health check failed (HTTP ${res.status})`
    );
  }

  // Após o fetch o cookie acabou de ser setado — relê `document.cookie` FRESCO
  // (o snapshot capturado antes do fetch é anterior ao Set-Cookie e nunca o conteria).
  const issued = readXsrfToken();
  if (!issued) {
    throw new Error("XSRF-TOKEN cookie not found after health check");
  }

  return issued;
}

/**
 * Request padrão: credentials: "include" + Accept JSON.
 */
function defaultInit(): RequestInit {
  return {
    cache: "no-store",
    credentials: "include",
    headers: {
      Accept: "application/json",
    },
  };
}

/**
 * Fetch aprimorado que:
 * - Injeta `Authorization: Bearer <token>` quando há token em memória.
 * - Injeta `X-XSRF-TOKEN` em métodos não-safe (POST PUT DELETE PATCH) usando o valor de `document.cookie`.
 * - Mantém `credentials: "include"` (refresh cookie enviado automaticamente pelo navegador).
 */
async function apiFetch<T>(
  path: string,
  init: RequestInit = {}
): Promise<T> {
  const headers = {
    ...defaultInit().headers,
    ...init.headers,
  } as Record<string, string>;

  // Injeta Authorization Bearer quando há token em memória
  const token = getAccessToken();
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  // Injeta X-XSRF-TOKEN para métodos não-safe (POST, PUT, DELETE, PATCH)
  const method = (init.method || "GET").toUpperCase();
  if (method !== "GET" && method !== "HEAD") {
    // Garante que o token CSRF está disponível
    const xsrfToken = await ensureCsrfToken();
    headers["X-XSRF-TOKEN"] = xsrfToken;
  }

  // Injeta X-Organization-Id apenas quando o path NÃO já carrega a org.
  // O `TenantInterceptor` do backend trata o path como fonte principal e responde
  // 404 quando path e header divergem — enviar o header ao lado de um {organizationId}
  // diferente da org ativa abriria esse 404. Assim o header fica reservado para os
  // endpoints tenant-scoped SEM org no path (complementar/futuro) e nunca conflita
  // com os atuais (`GET/POST /organizations` não são @TenantScoped e o ignoram).
  const orgId = getOrganizationId();
  const pathHasOrganization = /\/api\/v1\/organizations\/[^/?]+/.test(path);
  if (orgId && !pathHasOrganization) {
    headers["X-Organization-Id"] = orgId;
  }

  const res = await fetch(`${API_URL}${path}`, {
    ...defaultInit(),
    ...init,
    headers,
    cache: "no-store",
  });

  if (!res.ok) {
    let body: ApiErrorBody | null = null;
    try {
      body = (await res.json()) as ApiErrorBody;
    } catch {
      // corpo não-JSON (ex.: 502 de proxy) — cai no fallback abaixo
    }
    // 401 = sessão inválida/expirada em QUALQUER chamada → avisa os ouvintes
    // (AuthStatus limpa o token e assume estado anônimo).
    if (res.status === 401 && typeof window !== "undefined") {
      window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
    }
    throw new ApiError(
      res.status,
      body?.code ?? "UNKNOWN",
      body?.message ?? `Request failed (HTTP ${res.status})`,
      body?.path
    );
  }

  // 204 (DELETE de membro) e respostas sem corpo não têm JSON a parsear.
  if (res.status === 204) {
    return undefined as T;
  }
  const raw = await res.text();
  if (!raw) {
    return undefined as T;
  }
  return JSON.parse(raw) as T;
}

/** GET /api/v1/health → status do backend. */
export async function checkHealth(): Promise<{
  status: "up" | "down" | "unreachable";
  error?: string;
}> {
  try {
    const res = await fetch(`${API_URL}/api/v1/health`, {
      cache: "no-store",
      headers: {
        Accept: "application/json",
      },
    });

    if (!res.ok) {
      const errText = await res.text();
      return { status: "down", error: `HTTP ${res.status}: ${errText || "unreachable"}` };
    }

    const data = (await res.json()) as {
      status?: string;
      timestamp?: string;
      version?: string;
    };

    // Backend emite "UP"/"DOWN" (maiúsculo, contrato documentado).
    // Normaliza o case antes de comparar para aceitar "UP"/"up"/etc.
    const rawStatus = data.status?.toLowerCase();

    // Map known statuses
    const mapped: "up" | "down" | "unreachable" =
      rawStatus === "down" || rawStatus === "degraded"
        ? "down"
        : rawStatus === "up"
        ? "up"
        : "unreachable";

    return { status: mapped };
  } catch (err) {
    if (err instanceof Error) {
      return { status: "unreachable", error: err.message };
    }
    return { status: "unreachable", error: String(err) };
  }
}

/** POST /api/v1/auth/register → {user} (201). */
export async function register(input: RegisterInput): Promise<{
  user: UserResponse;
}> {
  return apiFetch("/api/v1/auth/register", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(input),
  });
}

/** POST /api/v1/auth/login → {user, accessToken} + cookie refresh HttpOnly. */
export async function login(input: LoginInput): Promise<{
  user: UserResponse;
  accessToken: string;
}> {
  const result = await apiFetch<{
    user: UserResponse;
    accessToken: string;
  }>("/api/v1/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(input),
  });

  // Sucesso: guarda o access token em memória (o refresh token fica no cookie HttpOnly do navegador)
  if (result.accessToken) {
    setAccessToken(result.accessToken);
  }

  return result;
}

/**
 * GET /api/v1/auth/me → usuário da sessão atual.
 * Usa o access token da memória via header Authorization.
 * Sem sessão (sem token ou inválido) → lança ApiError status 401.
 */
export async function me(): Promise<UserResponse> {
  return apiFetch<UserResponse>("/api/v1/auth/me");
}

/**
 * POST /api/v1/auth/refresh → novo access token usando o refresh token do cookie HttpOnly.
 * Sucesso → atualiza o token em memória e retorna {user, accessToken}.
 * 401 (REFRESH_TOKEN_INVALID/EXPIRED/REVOKED) → limpa token e lança erro para o frontend sair da sessão.
 */
export async function refreshSession(): Promise<{
  user: UserResponse;
  accessToken: string;
}> {
  const result = await apiFetch<{
    user: UserResponse;
    accessToken: string;
  }>("/api/v1/auth/refresh", {
    method: "POST",
    // Nenhum body necessário; o refresh token é enviado automaticamente via cookie HttpOnly + credentials: "include".
    // O header X-XSRF-TOKEN é injetado por apiFetch.
  });

  // Sucesso: rotação do refresh token feita pelo backend; atualiza o access token em memória.
  if (result.accessToken) {
    setAccessToken(result.accessToken);
  }

  return result;
}

/**
 * POST /api/v1/auth/logout → logout limpo. Sempre limpa o token em memória
 * mesmo se a rede falhar (o backend já limpa o cookie refresh do lado dele).
 */
export async function logout(): Promise<{
  message: string;
}> {
  try {
    await apiFetch("/api/v1/auth/logout", {
      method: "POST",
    });
  } catch {
    // Se a rede falhar, ainda assim limpa o token local para evitar ficar "preso" em estado autenticado.
  } finally {
    clearAccessToken();
    clearOrganizationId();
  }

  // O backend já limpa o cookie refresh (Max-Age=0). Retornamos o que conseguimos.
  return { message: "Logged out" };
}

/** GET /api/v1/organizations → **200** lista APENAS das orgs do usuário, cada uma com `role`. */
export async function listOrganizations(): Promise<OrganizationResponse[]> {
  return apiFetch<OrganizationResponse[]>("/api/v1/organizations");
}

/** POST /api/v1/organizations → **201** organização (id, name, createdAt, updatedAt + seu papel). **400** nome vazio/>128. */
export async function createOrganization(name: string): Promise<OrganizationResponse> {
  return apiFetch<OrganizationResponse>("/api/v1/organizations", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ name }),
  });
}

/** GET /api/v1/organizations/{id} → **200** org + role | **404** (não membro/inexistente). */
export async function getOrganization(id: string): Promise<OrganizationResponse> {
  return apiFetch<OrganizationResponse>(`/api/v1/organizations/${id}`);
}

/**
 * GET /api/v1/organizations/{organizationId}/members → **200** lista de membros.
 * **403** se o papel do usuário na org não for ADMIN | **404** se não for membro.
 * Admin-only no backend: é o único payload que expõe e-mails de outros usuários.
 */
export async function listMembers(organizationId: string): Promise<MemberResponse[]> {
  return apiFetch<MemberResponse[]>(`/api/v1/organizations/${organizationId}/members`);
}

/**
 * POST /api/v1/organizations/{organizationId}/members → **201** membro criado.
 * **404** e-mail não cadastrado (genérico) | **409** `MEMBERSHIP_ALREADY_EXISTS` | **403** não ADMIN.
 */
export async function addMember(
  organizationId: string,
  email: string,
  role: OrganizationRole
): Promise<MemberResponse> {
  return apiFetch<MemberResponse>(`/api/v1/organizations/${organizationId}/members`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ email, role }),
  });
}

/**
 * PATCH /api/v1/organizations/{organizationId}/members/{membershipId} → **200** membro atualizado.
 * **409** `LAST_ADMIN_REQUIRED` | **403** não ADMIN | **404** membership inexistente/outro tenant.
 */
export async function updateMemberRole(
  organizationId: string,
  membershipId: string,
  role: OrganizationRole
): Promise<MemberResponse> {
  return apiFetch<MemberResponse>(
    `/api/v1/organizations/${organizationId}/members/${membershipId}`,
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ role }),
    }
  );
}

/**
 * DELETE /api/v1/organizations/{organizationId}/members/{membershipId} → **204** sem corpo.
 * **409** `LAST_ADMIN_REQUIRED` | **403** não ADMIN | **404** membership inexistente/outro tenant.
 */
export async function removeMember(
  organizationId: string,
  membershipId: string
): Promise<void> {
  await apiFetch<void>(
    `/api/v1/organizations/${organizationId}/members/${membershipId}`,
    {
      method: "DELETE",
    }
  );
}

/* -------------------------------------------------------------------------- */
/* Projetos (Prompt 06)                                                       */
/* -------------------------------------------------------------------------- */

/**
 * Monta a query string da listagem de projetos (helper puro — testável sem rede).
 *
 * Regras: `undefined` e string vazia são omitidos (nunca `page=undefined`);
 * boolean serializa como `"true"`/`"false"`; números entram decimais (`page=0`
 * inclusive). Ordem fixa: page, size, active, name, sort. Retorna `""` sem params.
 */
export function buildProjectListParams(params?: ProjectListParams): string {
  if (!params) return "";
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));
  if (params.active !== undefined) query.set("active", String(params.active));
  if (params.name) query.set("name", params.name);
  if (params.sort) query.set("sort", params.sort);
  const qs = query.toString();
  return qs ? `?${qs}` : "";
}

/**
 * Body de projeto com APENAS os campos informados — nunca id/organizationId/
 * active/timestamps (o backend os ignora/rejeita; a org vem do path).
 */
function projectRequestBody(
  name: string | undefined,
  description: string | undefined
): ProjectUpdateRequest {
  const body: ProjectUpdateRequest = {};
  if (name !== undefined) body.name = name;
  if (description !== undefined) body.description = description;
  return body;
}

/**
 * GET /api/v1/organizations/{organizationId}/projects → **200** página Spring.
 * Query: `page` (default 0), `size` (default 20, máx 100), `sort` (default do
 * server: `createdAt,asc`), `active=true|false` opcional e `name` (busca
 * case-insensitive). **403** papel insuficiente | **404** não membro.
 *
 * O path já carrega a org, então o `apiFetch` NÃO injeta `X-Organization-Id`
 * (o `TenantInterceptor` do backend valida o path — mandar header divergente
 * abriria 404).
 */
export async function listProjects(
  organizationId: string,
  params?: ProjectListParams
): Promise<ProjectPage> {
  return apiFetch<ProjectPage>(
    `/api/v1/organizations/${organizationId}/projects${buildProjectListParams(params)}`
  );
}

/**
 * POST /api/v1/organizations/{organizationId}/projects → **201** projeto criado.
 * Body APENAS `{name, description?}`. **400** VALIDATION_ERROR |
 * **403** papel insuficiente | **409** `PROJECT_NAME_DUPLICATE`.
 */
export async function createProject(
  organizationId: string,
  request: ProjectCreateRequest
): Promise<ProjectResponse> {
  return apiFetch<ProjectResponse>(
    `/api/v1/organizations/${organizationId}/projects`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(
        projectRequestBody(request.name, request.description)
      ),
    }
  );
}

/** GET /api/v1/organizations/{organizationId}/projects/{projectId} → **200** projeto | **404**. */
export async function getProject(
  organizationId: string,
  projectId: string
): Promise<ProjectResponse> {
  return apiFetch<ProjectResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}`
  );
}

/**
 * PATCH /api/v1/organizations/{organizationId}/projects/{projectId} → **200** projeto atualizado.
 * Body parcial: só o que for informado é alterado.
 * **400** VALIDATION_ERROR | **403** papel insuficiente | **404** inexistente |
 * **409** `PROJECT_NAME_DUPLICATE`.
 */
export async function updateProject(
  organizationId: string,
  projectId: string,
  request: ProjectUpdateRequest
): Promise<ProjectResponse> {
  return apiFetch<ProjectResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}`,
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(
        projectRequestBody(request.name, request.description)
      ),
    }
  );
}

/**
 * PATCH /api/v1/organizations/{organizationId}/projects/{projectId}/status →
 * **200** projeto com o novo estado (idempotente).
 * **403** papel insuficiente | **404** inexistente.
 */
export async function updateProjectStatus(
  organizationId: string,
  projectId: string,
  active: boolean
): Promise<ProjectResponse> {
  const body: ProjectStatusRequest = { active };
  return apiFetch<ProjectResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/status`,
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    }
  );
}

/* -------------------------------------------------------------------------- */
/* Serviços (Prompt 07)                                                       */
/* -------------------------------------------------------------------------- */

/**
 * Monta a query string da listagem de serviços (helper puro — testável sem rede).
 *
 * Regras: `undefined` e string vazia são omitidos (nunca `page=undefined`);
 * boolean serializa como `"true"`/`"false"`; números entram decimais (`page=0`
 * inclusive). Ordem fixa: page, size, active, status, name, sort. Retorna `""`
 * sem params.
 */
export function buildServiceListParams(params?: ServiceListParams): string {
  if (!params) return "";
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));
  if (params.active !== undefined) query.set("active", String(params.active));
  if (params.status) query.set("status", params.status);
  if (params.name) query.set("name", params.name);
  if (params.sort) query.set("sort", params.sort);
  const qs = query.toString();
  return qs ? `?${qs}` : "";
}

/**
 * Body de serviço com APENAS os campos informados — nunca ids, organizationId,
 * projectId, active, status ou timestamps (a org/projeto vêm do path).
 */
function serviceRequestBody(
  name: string | undefined,
  description: string | undefined
): ServiceUpdateRequest {
  const body: ServiceUpdateRequest = {};
  if (name !== undefined) body.name = name;
  if (description !== undefined) body.description = description;
  return body;
}

/**
 * GET /api/v1/organizations/{organizationId}/projects/{projectId}/services →
 * **200** página Spring. Query: `page` (default 0), `size` (default 20, máx 100),
 * `sort` (default do server: `createdAt,asc`), `active=true|false`,
 * `status=OPERATIONAL|DEGRADED|UNAVAILABLE|UNKNOWN` e `name` (busca).
 * **403** papel insuficiente | **404** projeto/org inexistente.
 *
 * O path já carrega a org, então o `apiFetch` NÃO injeta `X-Organization-Id`
 * (o `TenantInterceptor` do backend valida o path — mandar header divergente
 * abriria 404). Leitura funciona mesmo com o projeto inativo.
 */
export async function listServices(
  organizationId: string,
  projectId: string,
  params?: ServiceListParams
): Promise<ServicePage> {
  return apiFetch<ServicePage>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services${buildServiceListParams(params)}`
  );
}

/**
 * POST /api/v1/organizations/{organizationId}/projects/{projectId}/services →
 * **201** serviço criado. Body APENAS `{name, description?}`.
 * **400** VALIDATION_ERROR | **403** papel insuficiente | **404** projeto/org |
 * **409** `SERVICE_NAME_DUPLICATE` ou `PROJECT_INACTIVE` (projeto inativo).
 */
export async function createService(
  organizationId: string,
  projectId: string,
  request: ServiceCreateRequest
): Promise<ServiceResponse> {
  return apiFetch<ServiceResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(
        serviceRequestBody(request.name, request.description)
      ),
    }
  );
}

/**
 * GET /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}
 * → **200** serviço | **404** inexistente/outro tenant.
 */
export async function getService(
  organizationId: string,
  projectId: string,
  serviceId: string
): Promise<ServiceResponse> {
  return apiFetch<ServiceResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}`
  );
}

/**
 * PATCH /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}
 * → **200** serviço atualizado. Body parcial: só o que for informado muda.
 * **400** VALIDATION_ERROR | **403** papel insuficiente | **404** inexistente |
 * **409** `SERVICE_NAME_DUPLICATE` ou `PROJECT_INACTIVE`.
 */
export async function updateService(
  organizationId: string,
  projectId: string,
  serviceId: string,
  request: ServiceUpdateRequest
): Promise<ServiceResponse> {
  return apiFetch<ServiceResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}`,
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(
        serviceRequestBody(request.name, request.description)
      ),
    }
  );
}

/**
 * PATCH /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/activation
 * → **200** serviço com o novo `active` (idempotente) — NÃO mexe no `status`
 * operacional. **403** papel insuficiente | **404** inexistente |
 * **409** `PROJECT_INACTIVE` (projeto inativo não admite mutação).
 */
export async function activateService(
  organizationId: string,
  projectId: string,
  serviceId: string,
  active: boolean
): Promise<ServiceResponse> {
  const body: ServiceActivationRequest = { active };
  return apiFetch<ServiceResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/activation`,
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    }
  );
}

/* -------------------------------------------------------------------------- */
/* Monitors (Prompt 08)                                                       */
/* -------------------------------------------------------------------------- */

/**
 * Tipo de monitor — o contrato de hoje só admite `HTTP` (a execução/resultado
 * do monitor é assunto de outro prompt; aqui só o cadastro e o ciclo de vida).
 */
export type MonitorType = "HTTP";

/** Método HTTP permitido no contrato de monitor. */
export type MonitorMethod = "GET" | "HEAD";

/** Resposta de monitor do backend (contrato de `MonitorResponse`). */
export interface MonitorResponse {
  id: string;
  organizationId: string;
  projectId: string;
  serviceId: string;
  name: string;
  /** Tipo do monitor — hoje sempre `HTTP`. */
  type: MonitorType;
  url: string;
  method: MonitorMethod;
  /** Intervalo entre checks agendados, em segundos (30..86400). */
  intervalSeconds: number;
  /** Timeout de cada check, em milissegundos (100..30000). */
  timeoutMillis: number;
  /**
   * Ciclo de vida do cadastro (ativado/desativado) — NÃO é um status
   * operacional de execução (não existe resultado/uptime neste contrato).
   */
  active: boolean;
  createdAt: string;
  updatedAt: string;
  /** Papel do usuário autenticado NA organização dona do serviço. */
  role: OrganizationRole;
}

/**
 * Corpo de criação — APENAS campos editáveis: `name`/`url` obrigatórios e
 * `method`/`intervalSeconds`/`timeoutMillis` opcionais (o backend aplica os
 * defaults `GET`, `60` e `5000`). id, organizationId, projectId, serviceId,
 * type, active e timestamps NUNCA entram no body.
 */
export interface MonitorCreateRequest {
  name: string;
  url: string;
  method?: MonitorMethod;
  intervalSeconds?: number;
  timeoutMillis?: number;
}

/** Corpo de edição (PATCH) — parcial: campos ausentes permanecem inalterados. */
export interface MonitorUpdateRequest {
  name?: string;
  url?: string;
  method?: MonitorMethod;
  intervalSeconds?: number;
  timeoutMillis?: number;
}

/**
 * Corpo do PATCH `.../monitors/{monitorId}/activation` — idempotente.
 * Desativar é permitido mesmo com o serviço/projeto pai inativo (fail-safe);
 * ativar com o pai inativo devolve **409**.
 */
export interface MonitorActivationRequest {
  active: boolean;
}

/**
 * Parâmetros da listagem de monitors (Spring Page). `undefined`/string vazia é
 * omitido da query string; boolean vira `"true"`/`"false"`; `method` filtra o
 * método HTTP (`GET`/`HEAD`).
 */
export interface MonitorListParams {
  page?: number;
  size?: number;
  active?: boolean;
  method?: MonitorMethod;
  name?: string;
  sort?: string;
}

/** Página Spring (`Page<MonitorResponse>`): fatia atual + totais. */
export interface MonitorPage {
  content: MonitorResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Monta a query string da listagem de monitors (helper puro — testável sem rede).
 *
 * Regras: `undefined` e string vazia são omitidos (nunca `page=undefined`);
 * boolean serializa como `"true"`/`"false"`; números entram decimais (`page=0`
 * inclusive). Ordem fixa: page, size, active, method, name, sort. Retorna `""`
 * sem params.
 */
export function buildMonitorListParams(params?: MonitorListParams): string {
  if (!params) return "";
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));
  if (params.active !== undefined) query.set("active", String(params.active));
  if (params.method) query.set("method", params.method);
  if (params.name) query.set("name", params.name);
  if (params.sort) query.set("sort", params.sort);
  const qs = query.toString();
  return qs ? `?${qs}` : "";
}

/**
 * Body de monitor com APENAS os campos informados — nunca id, organizationId,
 * projectId, serviceId, type, active ou timestamps (o backend os preenche a
 * partir do path/autenticação).
 */
function monitorRequestBody(
  request: MonitorUpdateRequest
): MonitorUpdateRequest {
  const body: MonitorUpdateRequest = {};
  if (request.name !== undefined) body.name = request.name;
  if (request.url !== undefined) body.url = request.url;
  if (request.method !== undefined) body.method = request.method;
  if (request.intervalSeconds !== undefined) {
    body.intervalSeconds = request.intervalSeconds;
  }
  if (request.timeoutMillis !== undefined) body.timeoutMillis = request.timeoutMillis;
  return body;
}

/**
 * GET /api/v1/organizations/{organizationId}/projects/{projectId}/services/
 * {serviceId}/monitors → **200** página Spring. Query: `page` (default 0),
 * `size` (default 20, máx 100), `sort` (default do server: `createdAt,asc`),
 * `active=true|false`, `method=GET|HEAD` e `name` (busca).
 * **403** papel insuficiente | **404** serviço/projeto/org inexistente.
 *
 * O path já carrega a org, então o `apiFetch` NÃO injeta `X-Organization-Id`
 * (o `TenantInterceptor` do backend valida o path — mandar header divergente
 * abriria 404). Leitura funciona mesmo com o serviço/projeto pai inativo.
 */
export async function listMonitors(
  organizationId: string,
  projectId: string,
  serviceId: string,
  params?: MonitorListParams
): Promise<MonitorPage> {
  return apiFetch<MonitorPage>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/monitors${buildMonitorListParams(params)}`
  );
}

/**
 * GET .../services/{serviceId}/monitors/{monitorId} → **200** monitor |
 * **404** inexistente/outro tenant.
 */
export async function getMonitor(
  organizationId: string,
  projectId: string,
  serviceId: string,
  monitorId: string
): Promise<MonitorResponse> {
  return apiFetch<MonitorResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/monitors/${monitorId}`
  );
}

/**
 * POST .../services/{serviceId}/monitors → **201** monitor criado
 * (defaults: `GET`, 60s, 5000ms, `type=HTTP`, `active=true`).
 * Body APENAS campos editáveis. **400** `VALIDATION_ERROR` ou
 * **400** `MONITOR_TARGET_NOT_ALLOWED` (alvo recusado por segurança — o
 * cliente nunca tenta contornar) | **403** papel insuficiente |
 * **409** `MONITOR_NAME_DUPLICATE`, `PROJECT_INACTIVE` ou `SERVICE_INACTIVE`.
 */
export async function createMonitor(
  organizationId: string,
  projectId: string,
  serviceId: string,
  request: MonitorCreateRequest
): Promise<MonitorResponse> {
  return apiFetch<MonitorResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/monitors`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(monitorRequestBody(request)),
    }
  );
}

/**
 * PATCH .../monitors/{monitorId} → **200** monitor atualizado. Body parcial:
 * só o que for informado muda. **400** `VALIDATION_ERROR` ou
 * **400** `MONITOR_TARGET_NOT_ALLOWED` | **403** papel insuficiente |
 * **404** inexistente | **409** `MONITOR_NAME_DUPLICATE`,
 * `PROJECT_INACTIVE` ou `SERVICE_INACTIVE`.
 */
export async function updateMonitor(
  organizationId: string,
  projectId: string,
  serviceId: string,
  monitorId: string,
  request: MonitorUpdateRequest
): Promise<MonitorResponse> {
  return apiFetch<MonitorResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/monitors/${monitorId}`,
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(monitorRequestBody(request)),
    }
  );
}

/**
 * PATCH .../monitors/{monitorId}/activation → **200** monitor com o novo
 * `active` (idempotente) — NÃO executa nada e NÃO mexe em resultado/uptime.
 * **403** papel insuficiente | **404** inexistente |
 * **409** `SERVICE_INACTIVE`/`PROJECT_INACTIVE` ao ATIVAR com o pai inativo
 * (desativar continua permitido — fail-safe do backend).
 */
export async function activateMonitor(
  organizationId: string,
  projectId: string,
  serviceId: string,
  monitorId: string,
  active: boolean
): Promise<MonitorResponse> {
  const body: MonitorActivationRequest = { active };
  return apiFetch<MonitorResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/monitors/${monitorId}/activation`,
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    }
  );
}
