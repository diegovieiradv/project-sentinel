"use client";

import { FormEvent, useRef, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { login, ApiError } from "@/lib/api";
import AuthDecorative from "@/components/AuthDecorative";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submittingRef = useRef(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loading) return;
    if (submittingRef.current) return;
    submittingRef.current = true;
    setLoading(true);
    setError(null);

    try {
      await login({ email, password });
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
    <main className="min-h-screen bg-[#090909] flex flex-col lg:flex-row items-stretch overflow-hidden">
      {/* Mobile: logo simplificada no topo */}
      <div className="lg:hidden flex flex-col items-center justify-center px-6 pt-12 pb-4">
        <Image
          src="/logo-login.png"
          alt="Sentinel - Monitoramento Inteligente"
          width={320}
          height={128}
          className="w-[55%] max-w-[240px] h-auto opacity-90"
        />
      </div>

      <AuthDecorative />
      {/* Coluna funcional — formulário */}
      <section className="flex w-full flex-col items-center justify-center px-6 py-8 lg:w-1/2 lg:px-10 lg:py-0">
        <div className="w-full max-w-sm rounded-2xl border border-white/[0.06] bg-[#111111]/80 backdrop-blur-md p-8 shadow-[0_24px_60px_-24px_rgba(0,0,0,0.8)]">
          <div className="mb-6 text-center">
            <h1 className="text-3xl font-extrabold tracking-tight text-white">Sign in</h1>
            <p className="mt-1.5 text-sm text-[#cbd5e1]/70">Access your Sentinel workspace</p>
          </div>

          <form onSubmit={onSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <label htmlFor="email" className="text-sm text-[#cbd5e1]/60 font-medium tracking-wide">Email</label>
              <input
                id="email"
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
                className="w-full rounded-xl border border-white/[0.08] bg-[#181818] px-4 py-3.5 min-h-[48px] text-sm text-white placeholder:text-[#cbd5e1]/30 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#0ea5e9] focus-visible:ring-offset-2 focus-visible:ring-offset-[#181818] transition-all duration-200"
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="password" className="text-sm text-[#cbd5e1]/60 font-medium tracking-wide">Password</label>
              <input
                id="password"
                type="password"
                required
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                className="w-full rounded-xl border border-white/[0.08] bg-[#181818] px-4 py-3.5 min-h-[48px] text-sm text-white placeholder:text-[#cbd5e1]/30 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#0ea5e9] focus-visible:ring-offset-2 focus-visible:ring-offset-[#181818] transition-all duration-200"
              />
            </div>

            {error && (
              <div role="alert" className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-3 text-sm text-rose-300 shadow-inner shadow-rose-900/5">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="mt-1 w-full rounded-xl bg-[#F97316] px-5 py-3.5 min-h-[48px] text-sm font-extrabold text-white shadow-xl shadow-[#F97316]/25 hover:shadow-[#F97316]/50 hover:brightness-110 active:scale-[0.99] transition-all duration-200 disabled:opacity-40 disabled:cursor-not-allowed disabled:shadow-none"
            >
              {loading ? "Signing in…" : "Sign in"}
            </button>
          </form>

          <p className="mt-6 text-center text-sm text-[#cbd5e1]/50">
            No account yet?{" "}
            <Link href="/register" className="inline-flex items-center gap-1 px-2 -mx-2 rounded-lg min-h-[44px] text-[#F97316] font-bold hover:text-[#FB923C] hover:underline underline-offset-4 transition-colors duration-200">
              Create one
            </Link>
          </p>
        </div>

        <p className="mt-6 text-sm text-[#cbd5e1]/40 text-center">
          <Link href="/" className="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl hover:bg-white/[0.04] hover:text-white transition-colors duration-200">
            <span aria-hidden="true" className="text-[#F97316]/60">←</span> Back to home
          </Link>
        </p>
      </section>

      {/* Desktop: logo decorativa centralizada */}
      <aside className="hidden lg:flex lg:flex-1 lg:relative lg:overflow-hidden lg:items-center lg:justify-center lg:border-l lg:border-[#1F1F1F] lg:bg-gradient-to-br lg:from-[#060505] lg:via-[#0d0b14] lg:to-[#140b08]" aria-label="Logo decorativa" role="img">
        <div className="absolute left-1/2 top-1/2 w-[72%] max-w-[580px] -translate-x-1/2 -translate-y-1/2">
          <Image
            src="/logo-login.png"
            alt="Sentinel - Monitoramento Inteligente"
            width={2172}
            height={724}
            className="h-auto w-full"
          />
        </div>
      </aside>
    </main>
  );
}
