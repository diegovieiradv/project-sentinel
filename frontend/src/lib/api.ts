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

/* -------------------------------------------------------------------------- */
/* Check history (Prompt 10)                                                   */
/* -------------------------------------------------------------------------- */

/**
 * Desfecho PERSISTIDO de uma checagem (enum `CheckOutcome` do backend).
 * - `SUCCESS` → 2xx; `DEGRADED_SIGNAL` → 3xx (redirect não seguido);
 * - `TARGET_FAILURE` → o alvo falhou; `SENTINEL_INTERNAL_FAILURE` → problema
 *   nosso (proteção SSRF/bug) — nunca é evidência do alvo.
 */
export type CheckOutcome =
  | "SUCCESS"
  | "DEGRADED_SIGNAL"
  | "TARGET_FAILURE"
  | "SENTINEL_INTERNAL_FAILURE";

/**
 * Por que a checagem falhou (enum `CheckFailureCategory` do backend) — só
 * existe quando `outcome` é falha; `null` em sucesso/degradado.
 */
export type CheckFailureCategory =
  | "DNS"
  | "CONNECT"
  | "TIMEOUT"
  | "TLS"
  | "HTTP_ERROR"
  | "SSRF_REVALIDATION"
  | "INTERNAL";

/**
 * Uma checagem já executada (contrato `MonitorCheckResponse`) — SÓ resultado,
 * nunca configuração: sem `serviceId`/`projectId`/`organizationId`/`url` e
 * sem corpo, headers, cookies ou tokens (o backend nem os persiste).
 *
 * `httpStatus` é anulável: só existe quando houve resposta (uma falha
 * DNS/timeout não tem status). `message` já vem sanitizada e truncada.
 */
export interface MonitorCheckResponse {
  id: string;
  monitorId: string;
  startedAt: string;
  finishedAt: string;
  durationMillis: number;
  /** `null` quando não houve resposta (falha de rede). */
  httpStatus: number | null;
  outcome: CheckOutcome;
  failureCategory: CheckFailureCategory | null;
  /** Mensagem de domínio curta — nunca corpo de resposta, URL ou secret. */
  message: string;
  createdAt: string;
}

/**
 * Parâmetros do histórico (Spring Page). O backend ordena por
 * `startedAt desc` sozinho — NÃO existe `sort` neste contrato.
 */
export interface MonitorCheckListParams {
  page?: number;
  size?: number;
}

/** Página Spring do histórico (`Page<MonitorCheckResponse>`). */
export interface MonitorCheckPage {
  content: MonitorCheckResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Monta a query string do histórico (helper puro — testável sem rede).
 * Ordem fixa: page, size. Sem params → `""`.
 */
export function buildMonitorCheckParams(params?: MonitorCheckListParams): string {
  if (!params) return "";
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));
  const qs = query.toString();
  return qs ? `?${qs}` : "";
}

/**
 * GET .../services/{serviceId}/monitors/{monitorId}/checks → **200** página
 * Spring com o histórico mais recente primeiro (ordenado por `startedAt desc`
 * no server; não há parâmetro `sort`).
 *
 * Query: `page` (default 0) e `size` (default 20, máx 100 — fora da faixa →
 * **400**). **404** monitor/outro tenant (mesmo 404 genérico da cadeia).
 * Leitura com qualquer papel e mesmo com serviço/projeto pai inativo.
 *
 * O path já carrega a org, então o `apiFetch` NÃO injeta `X-Organization-Id`
 * (mesmo motivo de `listMonitors`).
 */
export async function listMonitorChecks(
  organizationId: string,
  projectId: string,
  serviceId: string,
  monitorId: string,
  params?: MonitorCheckListParams
): Promise<MonitorCheckPage> {
  return apiFetch<MonitorCheckPage>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/monitors/${monitorId}/checks${buildMonitorCheckParams(params)}`
  );
}

/* -------------------------------------------------------------------------- */
/* History & metrics (Prompt 11)                                              */
/* -------------------------------------------------------------------------- */

/**
 * Faixa temporal de métrica — literais do contrato de `range`
 * (`1h | 6h | 24h | 7d | 30d`). Cada faixa fixa o tamanho do bucket e a
 * quantidade de buckets no servidor; o cliente só escolhe a faixa.
 */
export type MetricsRange = "1h" | "6h" | "24h" | "7d" | "30d";

/**
 * Contagens brutas + taxas derivadas — o mesmo shape de `MetricsSummary`,
 * `MetricsBucket` e `ServiceMetricsBreakdown` (todos espelham o record
 * `MetricsSummary` do backend).
 *
 * `availability`/`successRate` são anuláveis: `null` significa "sem dados
 * na janela" (zero checks relevantes) — o cliente NUNCA reconstrói
 * porcentagem nem converte `null` em `0`. `sentinelInternalFailure` conta
 * em `totalChecks`, mas fica FORA dos denominadores das taxas (falha do
 * Sentinel não é evidência do alvo).
 *
 * `avgLatencyMs`/`minLatencyMs`/`maxLatencyMs` medem o tempo de resposta
 * SÓ nos checks `SUCCESS`/`DEGRADED_SIGNAL` (falhas ficam de fora) e são
 * `null` quando não há nenhum — nunca `0`. A média vem com 2 casas;
 * min/max são inteiros em ms.
 */
export interface MetricsCounts {
  totalChecks: number;
  success: number;
  degraded: number;
  targetFailure: number;
  sentinelInternalFailure: number;
  availability: number | null;
  successRate: number | null;
  avgLatencyMs: number | null;
  minLatencyMs: number | null;
  maxLatencyMs: number | null;
}

/** Resumo da janela meia-aberta `[from, to)` — campos do `MetricsSummary`. */
export type MetricsSummary = MetricsCounts;

/**
 * Um bucket da série temporal — grade completa (o servidor devolve TODOS os
 * buckets da faixa, mesmo sem check: contagens zeradas e taxas `null`).
 */
export interface MetricsBucket extends MetricsCounts {
  /** Início do intervalo (ISO-8601/UTC), truncado no tamanho do bucket. */
  bucketStart: string;
}

/** Resposta de `GET .../monitors/{monitorId}/metrics`. */
export interface MonitorMetricsResponse {
  monitorId: string;
  range: MetricsRange;
  /** Janela EXATA usada no cálculo, meia-aberta `[from, to)` (ISO-8601). */
  from: string;
  to: string;
  bucketSeconds: number;
  summary: MetricsSummary;
  /** Sempre `bucketCount` elementos, em ordem crescente de `bucketStart`. */
  buckets: MetricsBucket[];
}

/**
 * Uma linha do breakdown por monitor de `GET .../services/{serviceId}/metrics`
 * — monitores sem nenhum check na janela NÃO aparecem (a soma das linhas
 * fecha com o `summary` do serviço). Campos espelhados (planos), ordenados
 * por nome no servidor.
 */
export interface ServiceMetricsBreakdown extends MetricsCounts {
  monitorId: string;
  monitorName: string;
}

/** Resposta de `GET .../services/{serviceId}/metrics` — três camadas. */
export interface ServiceMetricsResponse {
  serviceId: string;
  range: MetricsRange;
  /** Janela EXATA usada no cálculo, meia-aberta `[from, to)` (ISO-8601). */
  from: string;
  to: string;
  bucketSeconds: number;
  summary: MetricsSummary;
  buckets: MetricsBucket[];
  /** Breakdown por monitor, ordenado por nome — só monitores com check. */
  monitors: ServiceMetricsBreakdown[];
}

/**
 * Monta a query string do parâmetro `range` (helper puro — testável sem
 * rede). Faixa ausente → `""` (o servidor aplica o default `24h`); valor
 * presente → `?range=...` (o servidor valida e responde **400** para valor
 * fora do contrato).
 */
export function buildMetricsRangeParams(range?: MetricsRange): string {
  return range ? `?range=${range}` : "";
}

/**
 * GET .../monitors/{monitorId}/metrics → **200** métricas agregadas do
 * monitor sobre a janela pedida (resumo + grade de buckets; nunca linhas
 * cruas nem configuração). Query: `range=1h|6h|24h|7d|30d` opcional
 * (ausente → default `24h`; inválido → **400** `VALIDATION_ERROR`).
 * **404** monitor de outro serviço/tenant (mesmo 404 genérico da cadeia).
 *
 * Leitura com qualquer papel e mesmo com o serviço/projeto pai inativo.
 * O path já carrega a org, então o `apiFetch` NÃO injeta `X-Organization-Id`
 * (mesmo motivo de `listMonitors`).
 */
export async function getMonitorMetrics(
  organizationId: string,
  projectId: string,
  serviceId: string,
  monitorId: string,
  range?: MetricsRange
): Promise<MonitorMetricsResponse> {
  return apiFetch<MonitorMetricsResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/monitors/${monitorId}/metrics${buildMetricsRangeParams(range)}`
  );
}

/**
 * GET .../services/{serviceId}/metrics → **200** métricas agregadas do
 * serviço (união dos checks de TODOS os monitores, ativos e inativos):
 * resumo + grade de buckets + breakdown por monitor, tudo sobre a MESMA
 * janela. Query: `range` opcional (idem acima). **404** serviço de outro
 * projeto/tenant (mesmo 404 genérico da cadeia).
 *
 * Leitura com qualquer papel e mesmo com o serviço/projeto pai inativo.
 * `Service.status` NÃO vem aqui — este endpoint é histórico, não estado.
 */
export async function getServiceMetrics(
  organizationId: string,
  projectId: string,
  serviceId: string,
  range?: MetricsRange
): Promise<ServiceMetricsResponse> {
  return apiFetch<ServiceMetricsResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/metrics${buildMetricsRangeParams(range)}`
  );
}

/* -------------------------------------------------------------------------- */
/* Alert Rules (Prompt 12)                                                    */
/* -------------------------------------------------------------------------- */

/**
 * Tipo da condição — espelha o enum `AlertRuleType` do backend. Cada tipo
 * define o threshold permitido (percentual, latência, contagem ou status) e
 * o conjunto de operadores válidos (a matriz type × operator é validada no
 * backend; o cliente só espelha para feedback imediato).
 */
export type AlertRuleType =
  | "AVAILABILITY"
  | "SUCCESS_RATE"
  | "AVERAGE_LATENCY"
  | "TARGET_FAILURE_COUNT"
  | "SERVICE_STATUS";

/** Operador da comparação — espelha o enum `AlertOperator`. */
export type AlertOperator =
  | "LESS_THAN"
  | "LESS_THAN_OR_EQUAL"
  | "GREATER_THAN"
  | "GREATER_THAN_OR_EQUAL"
  | "EQUAL";

/** Janela de avaliação — espelha o enum `EvaluationWindow` (só configuração; a avaliação é do Prompt 13). */
export type EvaluationWindow =
  | "FIVE_MINUTES"
  | "FIFTEEN_MINUTES"
  | "ONE_HOUR"
  | "SIX_HOURS"
  | "TWENTY_FOUR_HOURS";

/** Severidade da regra — espelha o enum `AlertSeverity` (SEV1..SEV4). */
export type AlertSeverity = "SEV1" | "SEV2" | "SEV3" | "SEV4";

/**
 * Resposta de regra de alerta (contrato de `AlertRuleResponse` do backend) —
 * SÓ configuração: sem campo de resultado/firing (o Alert Engine é o
 * Prompt 13 e não existe neste contrato).
 *
 * `monitorId: null` = Service-level (métrica agregada do serviço); com valor =
 * Monitor-level (aquele monitor do MESMO serviço). O nome do monitor não vem
 * aqui — a UI resolve via listagem de monitores. `thresholdNumeric` e
 * `thresholdStatus` são mutuamente exclusivos (só um é preenchido, conforme
 * o `type`).
 */
export interface AlertRuleResponse {
  id: string;
  organizationId: string;
  projectId: string;
  serviceId: string;
  monitorId: string | null;
  name: string;
  type: AlertRuleType;
  operator: AlertOperator;
  thresholdNumeric: number | null;
  thresholdStatus: ServiceStatus | null;
  window: EvaluationWindow;
  severity: AlertSeverity;
  active: boolean;
  /**
   * Flag da automação (Prompt 15): quando `true`, a primeira disparada desta
   * regra abre um incidente e vincula o alerta a ele.
   */
  autoCreateIncident: boolean;
  createdAt: string;
  updatedAt: string;
  /** Papel do usuário autenticado NA organização dona do serviço. */
  role: OrganizationRole;
}

/**
 * Corpo de criação — APENAS campos de configuração: `name`/`type`/`operator`/
 * `window`/`severity` obrigatórios; `thresholdNumeric`/
 * `thresholdStatus`/`monitorId` conforme o tipo. id, organizationId,
 * projectId, serviceId, active e timestamps NUNCA entram no body.
 */
export interface AlertRuleCreateRequest {
  name: string;
  type: AlertRuleType;
  operator: AlertOperator;
  thresholdNumeric?: number | null;
  thresholdStatus?: ServiceStatus | null;
  window: EvaluationWindow;
  severity: AlertSeverity;
  monitorId?: string | null;
  /** Ausente o server trata como `false` (automação é opt-in). */
  autoCreateIncident?: boolean;
}

/**
 * Corpo de edição (PATCH) — parcial: campo ausente permanece inalterado.
 * Ao trocar o `type`, envie `type`+`operator`+threshold juntos (o backend
 * revalida a condição efetiva completa); `monitorId` presente revalida o
 * ownership (envie sempre o escopo escolhido no form).
 */
export interface AlertRuleUpdateRequest {
  name?: string;
  type?: AlertRuleType;
  operator?: AlertOperator;
  thresholdNumeric?: number;
  thresholdStatus?: ServiceStatus;
  window?: EvaluationWindow;
  severity?: AlertSeverity;
  monitorId?: string | null;
  /** Ausente (ou `null`) = não altera a flag — mesmo regime dos demais campos. */
  autoCreateIncident?: boolean;
}

/** Corpo do PATCH `.../alert-rules/{ruleId}/activation` — idempotente. */
export interface AlertRuleActivationRequest {
  active: boolean;
}

/**
 * Parâmetros da listagem de regras (Spring Page). `undefined`/string vazia é
 * omitido da query string; boolean vira `"true"`/`"false"`; `type`, `severity`
 * e `monitorId` filtram o escopo/condição.
 */
export interface AlertRuleListParams {
  page?: number;
  size?: number;
  active?: boolean;
  type?: AlertRuleType;
  severity?: AlertSeverity;
  monitorId?: string;
  name?: string;
  sort?: string;
}

/** Página Spring (`Page<AlertRuleResponse>`): fatia atual + totais. */
export interface AlertRulePage {
  content: AlertRuleResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Monta a query string da listagem de regras (helper puro — testável sem rede).
 *
 * Regras: `undefined` e string vazia são omitidos (nunca `page=undefined`);
 * boolean serializa como `"true"`/`"false"`; números entram decimais (`page=0`
 * inclusive). Ordem fixa: page, size, active, type, severity, monitorId,
 * name, sort. Retorna `""` sem params.
 */
export function buildAlertRuleListParams(params?: AlertRuleListParams): string {
  if (!params) return "";
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));
  if (params.active !== undefined) query.set("active", String(params.active));
  if (params.type) query.set("type", params.type);
  if (params.severity) query.set("severity", params.severity);
  if (params.monitorId) query.set("monitorId", params.monitorId);
  if (params.name) query.set("name", params.name);
  if (params.sort) query.set("sort", params.sort);
  const qs = query.toString();
  return qs ? `?${qs}` : "";
}

/**
 * Body de criação com APENAS os campos informados — nunca id, organizationId,
 * projectId, serviceId, active ou timestamps (vêm do path/autenticação).
 * `undefined` nunca vira `null` no JSON (thresholds ausentes ≠ explícitos).
 */
function alertRuleCreateBody(
  request: AlertRuleCreateRequest
): AlertRuleCreateRequest {
  const body: AlertRuleCreateRequest = {
    name: request.name,
    type: request.type,
    operator: request.operator,
    window: request.window,
    severity: request.severity,
  };
  if (request.thresholdNumeric !== undefined) {
    body.thresholdNumeric = request.thresholdNumeric;
  }
  if (request.thresholdStatus !== undefined) {
    body.thresholdStatus = request.thresholdStatus;
  }
  if (request.monitorId !== undefined) body.monitorId = request.monitorId;
  if (request.autoCreateIncident !== undefined) {
    body.autoCreateIncident = request.autoCreateIncident;
  }
  return body;
}

/**
 * Body de edição (PATCH) com APENAS os campos informados — um campo ausente
 * é herdado pelo backend (a troca de `type` envia a condição completa).
 */
function alertRuleUpdateBody(
  request: AlertRuleUpdateRequest
): AlertRuleUpdateRequest {
  const body: AlertRuleUpdateRequest = {};
  if (request.name !== undefined) body.name = request.name;
  if (request.type !== undefined) body.type = request.type;
  if (request.operator !== undefined) body.operator = request.operator;
  if (request.thresholdNumeric !== undefined) {
    body.thresholdNumeric = request.thresholdNumeric;
  }
  if (request.thresholdStatus !== undefined) {
    body.thresholdStatus = request.thresholdStatus;
  }
  if (request.window !== undefined) body.window = request.window;
  if (request.severity !== undefined) body.severity = request.severity;
  if (request.monitorId !== undefined) body.monitorId = request.monitorId;
  if (request.autoCreateIncident !== undefined) {
    body.autoCreateIncident = request.autoCreateIncident;
  }
  return body;
}

/**
 * GET .../services/{serviceId}/alert-rules → **200** página Spring. Query:
 * `page` (default 0), `size` (default 20, máx 100), `sort` (default do server),
 * `active=true|false`, `type`, `severity`, `monitorId` e `name` (busca
 * case-insensitive). **403** papel insuficiente | **404** cadeia do tenant.
 *
 * O path já carrega a org, então o `apiFetch` NÃO injeta `X-Organization-Id`
 * (mesmo motivo de `listMonitors`). Leitura funciona mesmo com serviço/
 * projeto pai inativo.
 */
export async function listAlertRules(
  organizationId: string,
  projectId: string,
  serviceId: string,
  params?: AlertRuleListParams
): Promise<AlertRulePage> {
  return apiFetch<AlertRulePage>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/alert-rules${buildAlertRuleListParams(params)}`
  );
}

/**
 * GET .../alert-rules/{ruleId} → **200** regra | **404** inexistente/outro
 * tenant (mesmo 404 genérico da cadeia).
 */
export async function getAlertRule(
  organizationId: string,
  projectId: string,
  serviceId: string,
  ruleId: string
): Promise<AlertRuleResponse> {
  return apiFetch<AlertRuleResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/alert-rules/${ruleId}`
  );
}

/**
 * POST .../alert-rules → **201** regra criada (nasce `active=true`).
 * Body APENAS campos de configuração. **400** `VALIDATION_ERROR` (matriz
 * type × operator, faixa de threshold, nome) | **403** papel insuficiente |
 * **404** serviço/projeto/org | **409** `ALERT_RULE_NAME_DUPLICATE`
 * (mesmo nome normalizado no MESMO serviço, ativo OU inativo),
 * `PROJECT_INACTIVE` ou `SERVICE_INACTIVE`.
 */
export async function createAlertRule(
  organizationId: string,
  projectId: string,
  serviceId: string,
  request: AlertRuleCreateRequest
): Promise<AlertRuleResponse> {
  return apiFetch<AlertRuleResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/alert-rules`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(alertRuleCreateBody(request)),
    }
  );
}

/**
 * PATCH .../alert-rules/{ruleId} → **200** regra atualizada. Body parcial:
 * só o que for informado muda (campo ausente é herdado). **400**
 * `VALIDATION_ERROR` (condição efetiva revalidada por inteiro) | **403** |
 * **404** | **409** `ALERT_RULE_NAME_DUPLICATE`, `PROJECT_INACTIVE` ou
 * `SERVICE_INACTIVE`.
 */
export async function updateAlertRule(
  organizationId: string,
  projectId: string,
  serviceId: string,
  ruleId: string,
  request: AlertRuleUpdateRequest
): Promise<AlertRuleResponse> {
  return apiFetch<AlertRuleResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/alert-rules/${ruleId}`,
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(alertRuleUpdateBody(request)),
    }
  );
}

/**
 * PATCH .../alert-rules/{ruleId}/activation → **200** regra com o novo
 * `active` (idempotente; desativar é sempre permitido — fail-safe).
 * **403** papel insuficiente | **404** inexistente | **409** `PROJECT_INACTIVE`
 * ou `SERVICE_INACTIVE` ao ATIVAR com o pai inativo.
 */
export async function activateAlertRule(
  organizationId: string,
  projectId: string,
  serviceId: string,
  ruleId: string,
  active: boolean
): Promise<AlertRuleResponse> {
  const body: AlertRuleActivationRequest = { active };
  return apiFetch<AlertRuleResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/alert-rules/${ruleId}/activation`,
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
/* Alerts (Prompt 13)                                                         */
/* -------------------------------------------------------------------------- */

/**
 * Status do ciclo de vida de UM alerta — espelha o enum `AlertStatus` do
 * backend. `FIRING` = condição vale agora; `RESOLVED` = foi encerrado (a
 * mensagem do disparo permanece intacta no histórico).
 */
export type AlertStatus = "FIRING" | "RESOLVED";

/**
 * Motivo de resolução — espelha `AlertResolutionReason`. `AUTO` = métrica
 * voltou ao normal; `RULE_DEACTIVATED` = regra desligada com alerta aberto;
 * `PARENT_INACTIVE` = serviço/projeto congelado. `null` enquanto FIRING.
 */
export type AlertResolutionReason = "AUTO" | "RULE_DEACTIVATED" | "PARENT_INACTIVE";

/**
 * Resposta de UM alerta disparado (contrato de `AlertResponse` do backend) —
 * leitura pura: não existe POST/PATCH/DELETE de alerta na API.
 *
 * Os campos `rule*` são o SNAPSHOT da condição na época do disparo (editar a
 * regra depois não reescreve o alerta). `monitorId: null` = escopo
 * Service-level. `triggerValue` é a evidência numérica que estourou (pode ser
 * `null`, ex.: `SERVICE_STATUS`); `message` é a do disparo em QUALQUER
 * desfecho — a resolução só preenche `resolutionReason`/`resolvedAt`.
 */
export interface AlertResponse {
  id: string;
  organizationId: string;
  projectId: string;
  serviceId: string;
  alertRuleId: string;
  monitorId: string | null;
  ruleName: string;
  status: AlertStatus;
  severity: AlertSeverity;
  resolutionReason: AlertResolutionReason | null;
  startedAt: string;
  resolvedAt: string | null;
  lastEvaluatedAt: string;
  triggerValue: number | null;
  message: string;
  ruleType: AlertRuleType;
  ruleOperator: AlertOperator;
  ruleThresholdNumeric: number | null;
  ruleThresholdStatus: ServiceStatus | null;
  ruleWindow: EvaluationWindow;
  createdAt: string;
  updatedAt: string;
  /** Papel do usuário autenticado NA organização dona do serviço. */
  role: OrganizationRole;
}

/**
 * Parâmetros da listagem de alertas (Spring Page). Só existem os dois
 * filtros do contrato — `status` e `severity` (a ordem é fixa no server,
 * não há `sort`); `undefined`/string vazia é omitido.
 */
export interface AlertListParams {
  page?: number;
  size?: number;
  status?: AlertStatus;
  severity?: AlertSeverity;
}

/** Página Spring (`Page<AlertResponse>`): fatia atual + totais. */
export interface AlertPage {
  content: AlertResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Monta a query string da listagem de alertas (helper puro — testável sem
 * rede). Regras iguais à das regras: `undefined` omitido, números decimais
 * (`page=0` inclusive), ordem fixa page, size, status, severity. `""` sem
 * params.
 */
export function buildAlertListParams(params?: AlertListParams): string {
  if (!params) return "";
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));
  if (params.status) query.set("status", params.status);
  if (params.severity) query.set("severity", params.severity);
  const qs = query.toString();
  return qs ? `?${qs}` : "";
}

/**
 * GET .../services/{serviceId}/alerts → **200** página Spring. Query:
 * `page` (default 0), `size` (default 20, máx 100), `status`
 * (`FIRING|RESOLVED`), `severity` (`SEV1`..`SEV4`) — valor de enum inválido
 * vira **400** no server. Ordem fixa: `startedAt desc, id desc`.
 *
 * O path já carrega a org, então o `apiFetch` NÃO injeta `X-Organization-Id`
 * (mesmo motivo de `listAlertRules`). Leitura funciona com serviço/projeto
 * pai inativo (histórico é histórico).
 */
export async function listAlerts(
  organizationId: string,
  projectId: string,
  serviceId: string,
  params?: AlertListParams
): Promise<AlertPage> {
  return apiFetch<AlertPage>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/alerts${buildAlertListParams(params)}`
  );
}

/**
 * GET .../alerts/{alertId} → **200** alerta | **404** inexistente/outro
 * tenant (mesmo 404 genérico da cadeia).
 */
export async function getAlert(
  organizationId: string,
  projectId: string,
  serviceId: string,
  alertId: string
): Promise<AlertResponse> {
  return apiFetch<AlertResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/alerts/${alertId}`
  );
}

/* -------------------------------------------------------------------------- */
/* Incidentes (Prompt 14)                                                     */
/* -------------------------------------------------------------------------- */

/** Estado do ciclo de vida manual do incidente (máquina de estados do server). */
export type IncidentStatus =
  | "OPEN"
  | "INVESTIGATING"
  | "IDENTIFIED"
  | "RESOLVED";

/**
 * Origem do incidente (`IncidentSource`, Prompt 15): `MANUAL` nasceu pela
 * rota de criação de um humano; `ALERT_AUTOMATION` nasceu sozinho quando a
 * regra com `autoCreateIncident` disparou — `createdByUserId` nulo acompanha
 * a origem automática.
 */
export type IncidentSource = "MANUAL" | "ALERT_AUTOMATION";

/** Vocabulário fechado da timeline append-only (`IncidentEventType`). */
export type IncidentEventType =
  | "CREATED"
  | "STATUS_CHANGED"
  | "SEVERITY_CHANGED"
  | "TITLE_CHANGED"
  | "DESCRIPTION_CHANGED"
  | "ALERT_LINKED"
  | "ALERT_UNLINKED";

/**
 * Resposta de UM incidente (`IncidentResponse`) — cadeia inteira no mesmo
 * arranjo do `AlertResponse`, conteúdo editável, estado do ciclo de vida,
 * audit de primeira linha e `version` (escudo de concorrência do `@Version`:
 * um `409` de PATCH é "atualizado por outra pessoa").
 */
export interface IncidentResponse {
  id: string;
  organizationId: string;
  projectId: string;
  serviceId: string;
  title: string;
  description: string | null;
  severity: AlertSeverity;
  status: IncidentStatus;
  /** Origem — diz "humano ou flag da regra" (Prompt 15). */
  source: IncidentSource;
  startedAt: string;
  resolvedAt: string | null;
  /**
   * Só o UUID — nenhuma resposta deste recurso carrega e-mail. Nulo para
   * incidente de `ALERT_AUTOMATION` (não existe humano por trás).
   */
  createdByUserId: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  /** Papel do usuário autenticado NA organização dona do serviço. */
  role: OrganizationRole;
}

/**
 * UMA linha da timeline (`IncidentEventResponse`) — log append-only, sem
 * `updatedAt` (fato histórico não é editável) e com `actorUserId` nulo quando
 * o autor não está mais no contexto.
 */
export interface IncidentEventResponse {
  id: string;
  incidentId: string;
  type: IncidentEventType;
  message: string;
  actorUserId: string | null;
  createdAt: string;
}

/**
 * Payload de POST `.../incidents` — os campos do contrato. Os escopos
 * (`organizationId`/`projectId`/`serviceId`) vêm do path e os atributos de
 * servidor (`status`, `resolvedAt`, `createdByUserId`, `startedAt` implícito)
 * não existem aqui de propósito.
 */
export interface IncidentCreateRequest {
  title: string;
  description?: string | null;
  severity: AlertSeverity;
  /** Opcional: ausente o server usa o relógio do domínio ("agora"). */
  startedAt?: string;
}

/**
 * Payload de PATCH `.../incidents/{incidentId}` — atualização PARCIAL.
 * Campo omitido = não altera; `description: ""` limpa (vira `null`).
 * Server-owned (`status`, `startedAt`, `resolvedAt`, `version`) não existe
 * aqui: o estado só muda pela rota `/status`.
 */
export interface IncidentUpdateRequest {
  title?: string;
  description?: string;
  severity?: AlertSeverity;
}

/** Payload de PATCH `.../incidents/{incidentId}/status` — destino obrigatório. */
export interface IncidentStatusRequest {
  status: IncidentStatus;
}

/** Filtros da listagem de incidentes (`page`/`size` + os dois enums do server). */
export interface IncidentListParams {
  page?: number;
  size?: number;
  status?: IncidentStatus;
  severity?: AlertSeverity;
}

/** Página Spring (`Page<IncidentResponse>`): fatia atual + totais. */
export interface IncidentPage {
  content: IncidentResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Monta a query string da listagem de incidentes (helper puro — testável sem
 * rede). Mesmas regras das demais listagens: `undefined` omitido, ordem fixa
 * page, size, status, severity; `""` sem params.
 */
export function buildIncidentListParams(params?: IncidentListParams): string {
  if (!params) return "";
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));
  if (params.status) query.set("status", params.status);
  if (params.severity) query.set("severity", params.severity);
  const qs = query.toString();
  return qs ? `?${qs}` : "";
}

/** Base do sub-recurso — os três níveis já vêm do path. */
function incidentsPath(
  organizationId: string,
  projectId: string,
  serviceId: string
): string {
  return `/api/v1/organizations/${organizationId}/projects/${projectId}/services/${serviceId}/incidents`;
}

/**
 * POST `.../incidents` → **201** incidente nascedo `OPEN`. Campos ausentes
 * caem nas regras do server: `title: must not be blank`, `severity: must not
 * be null`, `startedAt` fora da janela (−30 dias … +5 min) → **400**.
 */
export async function createIncident(
  organizationId: string,
  projectId: string,
  serviceId: string,
  request: IncidentCreateRequest
): Promise<IncidentResponse> {
  return apiFetch<IncidentResponse>(
    incidentsPath(organizationId, projectId, serviceId),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    }
  );
}

/**
 * GET `.../incidents` → **200** página Spring, ordem fixa
 * `startedAt desc, id desc`. Leitura funciona com projeto/serviço pai
 * inativo (a história é consultável depois do congelamento).
 */
export async function listIncidents(
  organizationId: string,
  projectId: string,
  serviceId: string,
  params?: IncidentListParams
): Promise<IncidentPage> {
  return apiFetch<IncidentPage>(
    `${incidentsPath(organizationId, projectId, serviceId)}${buildIncidentListParams(params)}`
  );
}

/** GET `.../incidents/{incidentId}` → **200** | **404** genérico de tenant. */
export async function getIncident(
  organizationId: string,
  projectId: string,
  serviceId: string,
  incidentId: string
): Promise<IncidentResponse> {
  return apiFetch<IncidentResponse>(
    `${incidentsPath(organizationId, projectId, serviceId)}/${incidentId}`
  );
}

/**
 * PATCH `.../incidents/{incidentId}` → **200** conteúdo parcial. Chaves
 * server-owned são ignoradas pelo DTO; no-op não gera evento nem avança
 * `updatedAt`. **409 INCIDENT_RESOLVED** depois de resolvido.
 */
export async function updateIncident(
  organizationId: string,
  projectId: string,
  serviceId: string,
  incidentId: string,
  request: IncidentUpdateRequest
): Promise<IncidentResponse> {
  return apiFetch<IncidentResponse>(
    `${incidentsPath(organizationId, projectId, serviceId)}/${incidentId}`,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    }
  );
}

/**
 * PATCH `.../incidents/{incidentId}/status` → **200**. NÃO idempotente:
 * pedir o status vigente responde **409 INCIDENT_STATUS_TRANSITION**
 * (`Cannot transition from X to Y`), como toda transição inválida.
 */
export async function changeIncidentStatus(
  organizationId: string,
  projectId: string,
  serviceId: string,
  incidentId: string,
  status: IncidentStatus
): Promise<IncidentResponse> {
  return apiFetch<IncidentResponse>(
    `${incidentsPath(organizationId, projectId, serviceId)}/${incidentId}/status`,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    }
  );
}

/**
 * GET `.../incidents/{incidentId}/events` → **200** timeline append-only na
 * ordem `createdAt asc, id asc`. Não existe escrita de evento nesta API.
 */
export async function listIncidentEvents(
  organizationId: string,
  projectId: string,
  serviceId: string,
  incidentId: string
): Promise<IncidentEventResponse[]> {
  return apiFetch<IncidentEventResponse[]>(
    `${incidentsPath(organizationId, projectId, serviceId)}/${incidentId}/events`
  );
}

/**
 * GET `.../incidents/{incidentId}/alerts` → **200** página de alertas
 * vinculados (mesmo `page`/`size` do resto da API).
 */
export async function listIncidentAlerts(
  organizationId: string,
  projectId: string,
  serviceId: string,
  incidentId: string,
  params?: Pick<IncidentListParams, "page" | "size">
): Promise<AlertPage> {
  return apiFetch<AlertPage>(
    `${incidentsPath(organizationId, projectId, serviceId)}/${incidentId}/alerts${buildIncidentListParams(params)}`
  );
}

/**
 * PUT `.../incidents/{incidentId}/alerts/{alertId}` → **200** incidente.
 * Idempotente (já nosso → 200 sem evento novo); alerta de OUTRO incidente →
 * **409 ALERT_ALREADY_LINKED**; alerta de outro serviço → **404**.
 */
export async function linkIncidentAlert(
  organizationId: string,
  projectId: string,
  serviceId: string,
  incidentId: string,
  alertId: string
): Promise<IncidentResponse> {
  return apiFetch<IncidentResponse>(
    `${incidentsPath(organizationId, projectId, serviceId)}/${incidentId}/alerts/${alertId}`,
    { method: "PUT" }
  );
}

/**
 * DELETE `.../incidents/{incidentId}/alerts/{alertId}` → **204** sem corpo.
 * Idempotente: soltar o que já está solto também é 204 e não gera evento.
 * Nunca apaga o alerta — só o vínculo.
 */
export async function unlinkIncidentAlert(
  organizationId: string,
  projectId: string,
  serviceId: string,
  incidentId: string,
  alertId: string
): Promise<void> {
  await apiFetch<void>(
    `${incidentsPath(organizationId, projectId, serviceId)}/${incidentId}/alerts/${alertId}`,
    { method: "DELETE" }
  );
}

/* -------------------------------------------------------------------------- */
/* Dashboard do projeto (Prompt 16) — resposta ÚNICA de leitura composta      */
/* -------------------------------------------------------------------------- */

/**
 * Faixa temporal do painel — MESMO vocabulário de `MetricsRange`
 * (`1h`, `6h`, `24h`, `7d`, `30d`). É alias intencional: o painel reusa a
 * janela já canônica do Prompt 11 (e o builder de query) em vez de criar um
 * segundo vocabulário de faixas que o backend teria que validar duas vezes.
 */
export type DashboardRange = MetricsRange;

/** Saúde dos SERVIÇOS do projeto (inclui inativos — não é janela temporal). */
export interface DashboardHealthSummary {
  totalServices: number;
  operational: number;
  degraded: number;
  unavailable: number;
  unknown: number;
}

/**
 * Disponibilidade da janela `[windowFrom, windowTo)` — fórmulas do Prompt 11
 * agregadas sobre o projeto INTEIRO. Taxas vêm em `null` quando o
 * denominador é zero (sem evidência, nunca `0`).
 */
export interface DashboardAvailabilitySummary {
  availabilityPercent: number | null;
  totalChecks: number;
  relevantChecks: number;
  successRatePercent: number | null;
  avgLatencyMs: number | null;
  minLatencyMs: number | null;
  maxLatencyMs: number | null;
}

/** Um alerta FIRING — o único estado "aberto" de um alerta. */
export interface DashboardActiveAlert {
  alertId: string;
  serviceId: string;
  serviceName: string;
  ruleId: string;
  ruleName: string;
  severity: AlertSeverity;
  firedAt: string;
  /** `null` enquanto o alerta não estiver vinculado a um incidente. */
  incidentId: string | null;
}

/** Contagens de alertas em FIRING (total + por severidade) + top 10. */
export interface DashboardAlertsSummary {
  firingTotal: number;
  sev1: number;
  sev2: number;
  sev3: number;
  sev4: number;
  activeAlerts: DashboardActiveAlert[];
}

/** Incidente ativo (`OPEN`/`INVESTIGATING`/`IDENTIFIED`) — top 10. */
export interface DashboardActiveIncident {
  incidentId: string;
  serviceId: string;
  serviceName: string;
  title: string;
  severity: AlertSeverity;
  status: IncidentStatus;
  source: IncidentSource;
  startedAt: string;
  associatedAlertsCount: number;
}

/** Contagens de incidentes ativos por status + top 10. */
export interface DashboardIncidentsSummary {
  activeTotal: number;
  open: number;
  investigating: number;
  identified: number;
  activeIncidents: DashboardActiveIncident[];
}

/** Tipos de linha da atividade recente (UNIÃO de alertas e incidentes). */
export type DashboardActivityType =
  | "ALERT_FIRED"
  | "ALERT_RESOLVED"
  | "INCIDENT_CREATED"
  | "INCIDENT_STATUS_CHANGED"
  | "INCIDENT_LINKED"
  | "INCIDENT_RESOLVED";

/** Recurso ao qual a linha de atividade aponta. */
export type DashboardActivityResourceType = "ALERT" | "INCIDENT";

/**
 * Uma linha da atividade recente, ordenada por `timestamp` DESC e limitada
 * no banco. `severity`/`title` são `null` só em linha defensiva — cada ramo
 * da união os preenche.
 */
export interface DashboardActivityItem {
  type: DashboardActivityType;
  timestamp: string;
  serviceId: string | null;
  serviceName: string | null;
  severity: AlertSeverity | null;
  title: string | null;
  resourceId: string | null;
  resourceType: DashboardActivityResourceType | null;
}

/** Serviço que pede atenção agora (não-OPERATIONAL, ou alerta/incidente). */
export interface DashboardServiceAttention {
  serviceId: string;
  serviceName: string;
  status: ServiceStatus;
  /** Maior severidade entre alerta FIRING e incidente ativo; `null` = nenhum. */
  highestSeverity: AlertSeverity | null;
  hasActiveIncident: boolean;
  statusChangedAt: string | null;
}

/** Resposta ÚNICA do painel — 10 seções, cada uma composta de dados reais. */
export interface DashboardResponse {
  projectId: string;
  rangeKey: DashboardRange;
  windowFrom: string;
  windowTo: string;
  health: DashboardHealthSummary;
  availability: DashboardAvailabilitySummary;
  alerts: DashboardAlertsSummary;
  incidents: DashboardIncidentsSummary;
  servicesNeedingAttention: DashboardServiceAttention[];
  recentActivity: DashboardActivityItem[];
}

/**
 * GET `.../organizations/{organizationId}/projects/{projectId}/dashboard`
 * → **200** resposta única do painel. Query `range` opcional (ausente →
 * default `24h`; valor fora de `1h|6h|24h|7d|30d` → **400**
 * `VALIDATION_ERROR`). **401** anônimo | **404** projeto/org inexistente ou
 * de outro tenant (mesmo 404 genérico da cadeia).
 *
 * Leitura com qualquer papel (ADMIN/OPERATOR/VIEWER) — o tenant já é provado
 * pelo path, que é a mesma autoridade das demais rotas `@TenantScoped`.
 */
export async function getDashboard(
  organizationId: string,
  projectId: string,
  range?: DashboardRange
): Promise<DashboardResponse> {
  return apiFetch<DashboardResponse>(
    `/api/v1/organizations/${organizationId}/projects/${projectId}/dashboard${buildMetricsRangeParams(range)}`
  );
}
