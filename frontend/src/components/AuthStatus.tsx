"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { me, ApiError, type UserResponse } from "@/lib/api";

type AuthState =
  | { kind: "loading" }
  | { kind: "anon" }
  | { kind: "authed"; user: UserResponse }
  | { kind: "error"; message: string };

/**
 * Checagem real de sessão: chama GET /api/v1/auth/me com o cookie
 * (credentials: "include"). 401 → anônimo; sucesso → sessão ativa;
 * outros erros (backend fora do ar, CORS) → estado de erro.
 */
export default function AuthStatus() {
  const [state, setState] = useState<AuthState>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;

    me()
      .then((user) => {
        if (!cancelled) setState({ kind: "authed", user });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          setState({ kind: "anon" });
          return;
        }
        setState({
          kind: "error",
          message:
            err instanceof Error
              ? err.message
              : "Could not verify session",
        });
      });

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

  if (state.kind === "authed") {
    return (
      <div className="flex flex-col items-center gap-1 text-sm">
        <span className="text-green-500">
          Signed in as{" "}
          <span className="font-semibold text-white">{state.user.name}</span>
        </span>
        <span className="text-(--muted)">{state.user.email}</span>
      </div>
    );
  }

  if (state.kind === "error") {
    return (
      <div className="flex flex-col items-center gap-1 text-sm">
        <span className="text-yellow-500">Session check failed</span>
        <span className="text-xs text-(--muted)">{state.message}</span>
        <div className="mt-1 flex items-center gap-3">
          <Link href="/login" className="text-(--accent) hover:underline">
            Sign in
          </Link>
          <Link href="/register" className="text-(--accent) hover:underline">
            Create account
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
