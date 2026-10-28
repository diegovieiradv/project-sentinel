"use client";

import { FormEvent, useRef, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { register, ApiError } from "@/lib/api";

export default function RegisterPage() {
  const router = useRouter();
  const [name, setName] = useState("");
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
      await register({ name, email, password });
      router.push("/login");
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
      {/* Mobile: logo simplificada no topo */}
      <div className="lg:hidden flex flex-col items-center justify-center px-6 pt-14 pb-4">
        <Image src="/sentinel-logo.png" alt="Sentinel - Monitoramento e Observabilidade" width={320} height={128} className="w-[55%] max-w-[240px] h-auto opacity-90" />
      </div>

      {/* Painel decorativo esquerdo (mesma estrutura do login aprovado) */}
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
        <svg className="absolute inset-0 z-10 w-full h-full opacity-60" viewBox="0 0 800 600" preserveAspectRatio="none" aria-hidden="true">
          <defs>
            <linearGradient id="curveGrad" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stopColor="#B0F35C" stopOpacity="0.8" />
              <stop offset="100%" stopColor="#6ee07a" stopOpacity="0.3" />
            </linearGradient>
          </defs>
          <path d="M 0 380 Q 200 180 400 420 T 800 360" stroke="url(#curveGrad)" strokeWidth="50" fill="none" strokeLinecap="round" />
          <path d="M 800 120 Q 500 -50 200 180 T -100 300" stroke="url(#curveGrad)" strokeWidth="30" fill="none" strokeLinecap="round" opacity="0.7" />
        </svg>
        {/* Logo + wordmark */}
        <div className="relative z-20 w-[min(560px,82%)] mix-blend-screen">
          <Image
            src="/sentinel-logo.png"
            alt="Sentinel - Monitoramento Inteligente"
            width={835}
            height={330}
            preload
            className="h-auto w-full drop-shadow-[0_0_30px_rgba(176,243,92,0.25)]"
          />
        </div>
      </aside>
      {/* Coluna funcional — formulário */}
      <section className="flex w-full flex-col items-center justify-center px-6 py-8 lg:w-1/2 lg:px-10 lg:py-0">
        <div className="w-full max-w-sm rounded-2xl border border-white/[0.10] bg-[#202D2E]/70 backdrop-blur-md p-8 shadow-[0_24px_60px_-24px_rgba(0,0,0,0.8)]">
          <div className="mb-6 text-center">
            <h1 className="text-3xl font-extrabold tracking-tight text-[#F5F7F5]">Create account</h1>
            <p className="mt-1.5 text-sm text-[#A7B3B3]">Start using Sentinel</p>
          </div>

          <form onSubmit={onSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <label htmlFor="name" className="text-sm text-[#A7B3B3] font-medium tracking-wide">Name</label>
              <input
                id="name"
                type="text"
                required
                autoComplete="name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Ada Lovelace"
                className="w-full rounded-xl border border-white/[0.10] bg-[#2D4143] px-4 py-3.5 min-h-[48px] text-sm text-[#F5F7F5] placeholder:text-[#A7B3B3]/30 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#B0F35C] focus-visible:ring-offset-2 focus-visible:ring-offset-[#2D4143] transition-all duration-200"
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="email" className="text-sm text-[#A7B3B3] font-medium tracking-wide">Email</label>
              <input
                id="email"
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
                className="w-full rounded-xl border border-white/[0.10] bg-[#2D4143] px-4 py-3.5 min-h-[48px] text-sm text-[#F5F7F5] placeholder:text-[#A7B3B3]/30 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#B0F35C] focus-visible:ring-offset-2 focus-visible:ring-offset-[#2D4143] transition-all duration-200"
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="password" className="text-sm text-[#A7B3B3] font-medium tracking-wide">Password</label>
              <input
                id="password"
                type="password"
                required
                autoComplete="new-password"
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="min. 8 chars, 1 letter, 1 digit"
                className="w-full rounded-xl border border-white/[0.10] bg-[#2D4143] px-4 py-3.5 min-h-[48px] text-sm text-[#F5F7F5] placeholder:text-[#A7B3B3]/30 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#B0F35C] focus-visible:ring-offset-2 focus-visible:ring-offset-[#2D4143] transition-all duration-200"
              />
              <p className="text-xs text-[#A7B3B3]/40 pl-0.5">At least 8 characters, including a letter and a digit.</p>
            </div>

            {error && (
              <div role="alert" className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-3 text-sm text-rose-300 shadow-inner shadow-rose-900/5">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="mt-1 w-full rounded-xl bg-[#B0F35C] px-5 py-3.5 min-h-[48px] text-sm font-extrabold text-[#192526] shadow-xl shadow-[#B0F35C]/25 hover:shadow-[#B0F35C]/50 hover:brightness-110 active:scale-[0.99] transition-all duration-200 disabled:opacity-40 disabled:cursor-not-allowed disabled:shadow-none"
            >
              {loading ? "Creating…" : "Create account"}
            </button>
          </form>

          <p className="mt-6 text-center text-sm text-[#A7B3B3]/50">
            Already have an account?{" "}
            <Link href="/login" className="inline-flex items-center gap-1 px-2 -mx-2 rounded-lg min-h-[44px] text-[#B0F35C] font-bold hover:text-white hover:underline underline-offset-4 transition-colors duration-200">
              Sign in
            </Link>
          </p>
        </div>

        <p className="mt-6 text-sm text-[#A7B3B3]/30 text-center">
          <Link href="/" className="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl hover:bg-white/[0.04] hover:text-[#F5F7F5] transition-colors duration-200">
            <span aria-hidden="true" className="text-[#B0F35C]/60">←</span> Back to home
          </Link>
        </p>
      </section>

    </main>
  );
}
