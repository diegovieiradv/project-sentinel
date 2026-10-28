"use client";
import { FormEvent, useRef, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { login, ApiError } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
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
    <main className="min-h-screen bg-[#192526] flex flex-col lg:flex-row items-stretch overflow-hidden font-[Poppins,sans-serif]">
      {/* Mobile: logo simplificada no topo (mix-blend-screen funde o recorte no fundo) */}
      <div className="flex flex-col items-center px-6 pb-2 pt-10 lg:hidden">
        <Image
          src="/sentinel-logo.png"
          alt="Sentinel — Monitoramento e Observabilidade"
          width={835}
          height={330}
          preload
          className="h-auto w-[58%] max-w-[240px] mix-blend-screen"
        />
      </div>

      {/* Painel decorativo esquerdo */}
      <aside className="hidden lg:flex lg:w-1/2 relative overflow-hidden items-center justify-center bg-gradient-to-br from-[#152829] via-[#192526] to-[#0f1a18]" aria-label="Identidade visual" role="img">
        {/* Gradientes luminosos */}
        <div className="absolute inset-0 z-0 pointer-events-none">
          <div className="absolute -top-[10%] -left-[10%] w-[120%] h-[120%] rounded-full bg-gradient-to-br from-[#B0F35C]/20 via-transparent to-transparent blur-3xl" />
          <div className="absolute -bottom-[20%] -right-[20%] w-[140%] h-[140%] rounded-full bg-gradient-to-tl from-[#B0F35C]/15 via-transparent to-transparent blur-3xl" />
          <div className="absolute top-[20%] left-[10%] w-[50%] h-[50%] rounded-full bg-[#B0F35C]/10 blur-2xl" />
        </div>
        {/* Grade de pontos */}
        <svg className="absolute inset-0 w-full h-full z-10 opacity-20" aria-hidden="true">
          <defs>
            <pattern id="dotGrid" width="28" height="28" patternUnits="userSpaceOnUse">
              <circle cx="2" cy="2" r="1" fill="#B0F35C" />
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#dotGrid)" />
        </svg>
        {/* Curvas decorativas */}
        <svg className="absolute inset-0 z-10 w-full h-full opacity-60" viewBox="0 0 800 600" preserveAspectRatio="none" aria-hidden="true">          <defs>
            <linearGradient id="curveGrad" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stopColor="#B0F35C" stopOpacity="0.8" />
              <stop offset="100%" stopColor="#6ee07a" stopOpacity="0.3" />
            </linearGradient>
          </defs>
          <path d="M 0 380 Q 200 180 400 420 T 800 360" stroke="url(#curveGrad)" strokeWidth="50" fill="none" strokeLinecap="round" />
          <path d="M 800 120 Q 500 -50 200 180 T -100 300" stroke="url(#curveGrad)" strokeWidth="30" fill="none" strokeLinecap="round" opacity="0.7" />
        </svg>
        {/* Logo + wordmark (blend no wrapper: z-index criaria stacking context que isola o blend do img) */}
        <div className="relative z-20 w-[min(560px,82%)] mix-blend-screen">
          <Image
            src="/sentinel-logo.png"
            alt="Sentinel - Monitoramento Inteligente"
            width={835}
            height={330}
            className="h-auto w-full drop-shadow-[0_0_30px_rgba(176,243,92,0.25)]"
            preload
          />
        </div>
      </aside>

      {/* Coluna funcional — formulário */}
      <section className="flex w-full lg:w-1/2 flex-col items-center justify-center px-6 py-12 lg:px-14 lg:py-0">
        <div className="w-full max-w-md rounded-3xl border border-white/[0.08] bg-[#202D2E]/70 backdrop-blur-xl p-8 lg:p-10 shadow-[0_24px_60px_-24px_rgba(0,0,0,0.6)]">
          <div className="mb-8 text-center">
            <h1 className="text-4xl font-extrabold tracking-tight text-[#F5F7F5]">Sign in</h1>
            <p className="mt-2 text-base text-[#A7B3B3]">Access your Sentinel workspace</p>
          </div>

          <form onSubmit={onSubmit} className="flex flex-col gap-5">
            <div className="flex flex-col gap-1.5">
              <label htmlFor="email" className="text-sm text-[#A7B3B3] font-semibold tracking-wide">Email</label>
              <div className="relative">
                <span className="absolute left-3.5 top-1/2 -translate-y-1/2 text-[#A7B3B3]/60" aria-hidden="true">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="5" width="20" height="14" rx="2"/><polyline points="2 7 12 13 22 7"/></svg>
                </span>
                <input
                  id="email"
                  type="email"
                  required
                  autoComplete="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@example.com"
                  className="w-full rounded-xl border border-white/[0.10] bg-[#2D4143] pl-11 pr-4 py-3.5 min-h-[48px] text-sm text-[#F5F7F5] placeholder:text-[#A7B3B3]/50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#B0F35C] focus-visible:ring-offset-2 focus-visible:ring-offset-[#192526] transition-all duration-200"
                />
              </div>
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="password" className="text-sm text-[#A7B3B3] font-semibold tracking-wide">Password</label>
              <div className="relative">
                <span className="absolute left-3.5 top-1/2 -translate-y-1/2 text-[#A7B3B3]/60" aria-hidden="true">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="10" width="18" height="12" rx="2"/><path d="M7 10V6a5 5 0 0 1 10 0v4"/></svg>
                </span>
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  required
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••"
                  className="w-full rounded-xl border border-white/[0.10] bg-[#2D4143] pl-11 pr-10 py-3.5 min-h-[48px] text-sm text-[#F5F7F5] placeholder:text-[#A7B3B3]/50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#B0F35C] focus-visible:ring-offset-2 focus-visible:ring-offset-[#192526] transition-all duration-200"
                />
                <button
                  type="button"
                  aria-label={showPassword ? "Hide password" : "Show password"}
                  aria-pressed={showPassword}
                  onClick={() => setShowPassword((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-[#A7B3B3]/60 hover:text-[#B0F35C] transition-colors duration-200 min-h-[44px] min-w-[44px] flex items-center justify-center"
                >
                  {showPassword ? (
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M17.94 17.94A10.06 10.06 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                  ) : (
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                  )}
                </button>
              </div>
            </div>

            {error && (
              <div role="alert" className="rounded-xl border border-rose-400/20 bg-rose-400/10 px-4 py-3 text-sm text-rose-300 shadow-inner shadow-rose-900/5">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="mt-1 w-full rounded-xl bg-[#B0F35C] px-5 py-3.5 min-h-[48px] text-sm font-extrabold text-[#192526] shadow-xl shadow-[#B0F35C]/25 hover:shadow-[#B0F35C]/50 hover:brightness-110 active:scale-[0.99] transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed disabled:shadow-none"
            >
              {loading ? "Signing in…" : "Sign in"}
            </button>
          </form>

          <p className="mt-6 text-center text-sm text-[#A7B3B3]/80">
            No account yet?{" "}
            <Link href="/register" className="inline-flex items-center gap-1 px-2 -mx-2 rounded-lg min-h-[44px] text-[#B0F35C] font-extrabold hover:text-white hover:underline underline-offset-4 transition-colors duration-200">
              Create one
            </Link>
          </p>
        </div>

        <p className="mt-6 text-sm text-[#A7B3B3]/50 text-center">
          <Link href="/" className="inline-flex items-center gap-1.5 px-3 py-3 rounded-xl hover:bg-white/[0.06] hover:text-[#F5F7F5] transition-colors duration-200">
            <span aria-hidden="true" className="text-[#B0F35C]/80">←</span> Back to home
          </Link>
        </p>
      </section>
    </main>
  );
}
