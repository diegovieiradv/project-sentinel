"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  me,
  refreshSession,
  logout,
  getAccessToken,
  clearAccessToken,
  ApiError,
  type UserResponse,
} from "@/lib/api";

type AuthState =
  | { kind: "loading" }
  | { kind: "anon" }
  | { kind: "authed"; user: UserResponse }
  | { kind: "error"; message: string };

/**
 * Bootstrap de sessão Prompt 03:
 * - Se houver access token em memória → chama me() para confirmar.
 * - Se não houver token → tenta refreshSession() (que usa o cookie HttpOnly do refresh)
 *   e depois me() para confirmar a sessão.
 * - 401 em qualquer etapa → estado anônimo.
 * - Erro de rede/5xx → estado de erro (não finge que o usuário está autenticado).
 * - Durante todo o bootstrap mantém loading — não mostra "Signed in" antes de confirmar.
 */
export default function AuthStatus() {
  const [state, setState] = useState<AuthState>({ kind: "loading" });
  // Evita duplo clique e desabilita o botão enquanto o POST /logout está em voo.
  const [loggingOut, setLoggingOut] = useState(false);

  /**
   * Sign out: aguarda `logout()` terminar (revogação do refresh token no backend)
   * antes de exibir o estado anônimo — um fire-and-forget cancelaria o POST
   * (reload/fechamento rápido) e o próximo bootstrap "ressuscitaria" a sessão
   * via refresh. `logout()` já limpa o access token em `finally` e engole
   * erro de rede, então aqui sempre caímos em "anon" após a tentativa completar.
   */
  async function onSignOut(): Promise<void> {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      setState({ kind: "anon" });
      setLoggingOut(false);
    }
  }

  useEffect(() => {
    let cancelled = false;

    async function bootstrap(): Promise<void> {
      try {
        const token = getAccessToken();

        if (token) {
          // Já tem token em memória — confirma a sessão com me()
          const user = await me();
          if (!cancelled) {
            setState({
              kind: "authed",
              user,
            });
          }
        } else {
          // Nenhum token em memória — tenta refreshSession usando o cookie HttpOnly do refresh
          await refreshSession();
          // Depois do refresh, tenta me() novamente para confirmar
          const user = await me();
          if (!cancelled) {
            setState({
              kind: "authed",
              user,
            });
          }
        }
      } catch (err) {
        // 401 = sessão expirada/inválida → anômimo
        // Qualquer outro erro → estado de erro (não finge autenticação)
        if (cancelled) return;

        if (
          err instanceof ApiError &&
          err.status === 401
        ) {
          // Sessão expirada ou token inválido — limpa token e vai para anônimo
          clearAccessToken();
          if (!cancelled) setState({ kind: "anon" });
          return;
        }

        // Erro de rede, 5xx, etc — não fingir que o usuário está autenticado
        if (!cancelled) {
          setState({
            kind: "error",
            message:
              err instanceof Error
                ? err.message
                : "Could not verify session",
          });
        }
      }
    }

    bootstrap();

    return () => {
      cancelled = true;
    };
  }, []);

  if (state.kind === "loading") {
    return (
      <div className="flex items-center gap-3 text-sm text-(--muted)">
        <span>Checking session…</span>
      </div>
    );
  }

  if (state.kind === "anon") {
    return (
      <div className="flex flex-col items-center gap-1 text-sm">
        <span className="text-(--muted)">Signed out</span>
        <div className="mt-2 flex gap-3">
          <Link
            href="/login"
            className="rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white"
          >
            Sign in
          </Link>
          <Link
            href="/register"
            className="rounded-md bg-(--accent) px-3 py-1.5 font-semibold text-white transition hover:opacity-90"
          >
            Create account
          </Link>
        </div>
      </div>
    );
  }

  if (state.kind === "authed") {
    return (
      <div className="flex flex-col items-center gap-1 text-sm">
        <span className="text-green-500">
          Signed in as{" "}
          <span className="font-semibold text-white">{state.user.name}</span>
        </span>
        <span className="text-(--muted)">{state.user.email}</span>
        <div className="mt-2">
          <button
            onClick={onSignOut}
            disabled={loggingOut}
            className="mt-1 rounded-md bg-(--destructive) px-3 py-1.5 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
          >
            {loggingOut ? "Signing out…" : "Sign out"}
          </button>
        </div>
      </div>
    );
  }

  if (state.kind === "error") {
    return (
      <div className="flex flex-col items-center gap-1 text-sm">
        <span className="text-yellow-500">Session check failed</span>
        <span className="text-xs text-(--muted)">{state.message}</span>
        <div className="mt-1 flex items-center gap-3">
          <Link
            href="/login"
            className="text-(--accent) hover:underline"
          >
            Try sign in
          </Link>
          <Link
            href="/"
            className="text-(--accent) hover:underline"
          >
            Go to home
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3 text-sm">
      <Link
        href="/login"
        className="rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white"
      >
        Sign in
      </Link>
      <Link
        href="/register"
        className="rounded-md bg-(--accent) px-3 py-1.5 font-semibold text-white transition hover:opacity-90"
      >
        Create account
      </Link>
    </div>
  );
}