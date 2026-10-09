"use client";

import { FormEvent, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { login, ApiError } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // FE-02 — trava síncrona do duplo submit: `loading` só muda no render
  // seguinte, enquanto este ref já é lido/escrito no MESMO tick do clique.
  const submittingRef = useRef(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // FE-02 — guarda de duplo submit: `setLoading(true)` só re-renderiza no
    // próximo tick, então dois cliques/Enter rápidos disparariam `login()`
    // duas vezes. `preventDefault` fica antes (senão o form navegaria) e este
    // `return` impede a segunda chamada antes de tocar na rede; o
    // `submittingRef` fecha a janela de mesmo tick que `loading` não cobre.
    if (loading) return;
    if (submittingRef.current) return;
    submittingRef.current = true;
    setLoading(true);
    setError(null);

    try {
      await login({ email, password });
      // Login guardou o access token em memória (o refresh token ficou no
      // cookie HttpOnly). Volta para a home, que usa o token para
      // consultar /api/v1/auth/me e exibir o usuário.
      router.push("/");
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.message
          : "Could not reach the backend. Is it running on port 8080?"
      );
    } finally {
      submittingRef.current = false;
      setLoading(false);
    }
  }

  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-4 py-8">
      <div className="w-full max-w-sm rounded-xl border border-(--border) bg-(--card) p-6 shadow-sm">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-bold text-white">Sign in</h1>
          <p className="mt-1 text-sm text-(--muted)">
            Access your Sentinel workspace
          </p>
        </div>

        <form onSubmit={onSubmit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <label htmlFor="email" className="text-sm text-(--muted)">
              Email
            </label>
            <input
              id="email"
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              className="w-full rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white placeholder:text-(--muted) focus:border-(--accent) focus:outline-none"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label htmlFor="password" className="text-sm text-(--muted)">
              Password
            </label>
            <input
              id="password"
              type="password"
              required
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              className="w-full rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white placeholder:text-(--muted) focus:border-(--accent) focus:outline-none"
            />
          </div>

          {error && (
            <div
              role="alert"
              className="rounded-md border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-400"
            >
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="mt-2 w-full rounded-md bg-(--accent) px-4 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
          >
            {loading ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <p className="mt-4 text-center text-sm text-(--muted)">
          No account yet?{" "}
          <Link href="/register" className="text-(--accent) hover:underline">
            Create one
          </Link>
        </p>
      </div>

      <p className="mt-4 text-sm">
        <Link href="/" className="text-(--muted) hover:text-white">
          ← Back to home
        </Link>
      </p>
    </main>
  );
}
