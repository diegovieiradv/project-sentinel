/**
 * Minimal API client for Sentinel frontend.
 *
 * Auth (Prompt 02): sessão HTTP por cookie — TODAS as chamadas usam
 * `credentials: "include"` para enviar/receber o JSESSIONID cross-origin
 * (localhost:3000 → localhost:8080, CORS com allowCredentials).
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

export interface AuthResponse {
  user: UserResponse;
  tokenType?: string;
}

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

async function apiFetch<T>(
  path: string,
  init: RequestInit = {}
): Promise<T> {
  const res = await fetch(`${API_URL}${path}`, {
    ...init,
    cache: "no-store",
    credentials: "include",
    headers: {
      Accept: "application/json",
      ...init.headers,
    },
  });

  if (!res.ok) {
    let body: ApiErrorBody | null = null;
    try {
      body = (await res.json()) as ApiErrorBody;
    } catch {
      // corpo não-JSON (ex.: 502 de proxy) — cai no fallback abaixo
    }
    throw new ApiError(
      res.status,
      body?.code ?? "UNKNOWN",
      body?.message ?? `Request failed (HTTP ${res.status})`,
      body?.path
    );
  }

  return (await res.json()) as T;
}

/** POST /api/v1/auth/register → {user} (201). */
export async function register(input: RegisterInput): Promise<{
  user: UserResponse;
}> {
  return apiFetch("/api/v1/auth/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

/** POST /api/v1/auth/login → {user, tokenType:"session"} + cookie de sessão. */
export async function login(input: LoginInput): Promise<AuthResponse> {
  return apiFetch("/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

/**
 * GET /api/v1/auth/me → usuário da sessão atual.
 * Sem sessão → lança ApiError com status 401 (não há token no client).
 */
export async function me(): Promise<UserResponse> {
  return apiFetch<UserResponse>("/api/v1/auth/me");
}

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
      return { status: "down", error: `HTTP ${res.status}: ${errText || "unreachable" }` };
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
