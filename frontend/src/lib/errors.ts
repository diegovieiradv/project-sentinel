/**
 * Helpers de tratamento de erro compartilhados pela home (`src/app/page.tsx`)
 * e pelo painel de serviços (`src/components/ServicesPanel.tsx`).
 *
 * Mantidos num módulo próprio para que a regra de sessão exista em UM lugar
 * só: 401 limpa a sessão local (o `apiFetch` já dispara `SESSION_EXPIRED_EVENT`),
 * 403/409/400 viram mensagem de UI — NUNCA logout.
 */
import { ApiError, clearAccessToken, clearOrganizationId } from "./api";

/** Mensagem legível de qualquer erro capturado. */
export function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError || err instanceof Error) {
    return err.message;
  }
  return fallback;
}

/**
 * 401 em qualquer chamada → limpa a sessão local.
 * O próprio `apiFetch` já dispara `SESSION_EXPIRED_EVENT`, que leva o `AuthStatus`
 * a assumir estado anônimo (e a home desmonta a seção de organizações).
 */
export function handleUnauthorized(): void {
  clearAccessToken();
  clearOrganizationId();
}

export function isUnauthorized(err: unknown): boolean {
  return err instanceof ApiError && err.status === 401;
}
