"use client";
import Image from "next/image";

export default function AuthDecorative() {
  return (
    <aside className="hidden lg:flex lg:w-1/2 relative overflow-hidden bg-[#050505] items-center justify-center">
      {/* Gradientes abstratos laranja/roxo fluídos */}
      <div className="absolute inset-0 z-0">
        <div className="absolute -top-[20%] -left-[20%] w-[140%] h-[140%] rounded-full bg-gradient-to-br from-[#F97316]/30 via-[#FB923C]/20 to-transparent blur-3xl" />
        <div className="absolute -bottom-[30%] -right-[30%] w-[120%] h-[120%] rounded-full bg-gradient-to-tl from-[#7C3AED]/40 via-[#9333EA]/20 to-transparent blur-3xl" />
        <div className="absolute top-[30%] right-[10%] w-[60%] h-[60%] rounded-full bg-gradient-to-br from-[#F97316]/20 to-[#9333EA]/25 blur-2xl" />
        <div className="absolute bottom-[10%] left-[20%] w-[50%] h-[50%] rounded-full bg-gradient-to-tr from-[#FB923C]/15 to-[#F97316]/10 blur-2xl" />
      </div>

      {/* Formas curvas decorativas */}
      <svg className="absolute inset-0 z-10 w-full h-full opacity-60" viewBox="0 0 800 600" preserveAspectRatio="none" aria-hidden="true">
        <defs>
          <linearGradient id="curveGrad" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#F97316" stopOpacity="0.9" />
            <stop offset="60%" stopColor="#FB923C" stopOpacity="0.6" />
            <stop offset="100%" stopColor="#9333EA" stopOpacity="0.5" />
          </linearGradient>
        </defs>
        <path d="M 0 400 Q 200 200 400 450 T 800 380" stroke="url(#curveGrad)" strokeWidth="60" fill="none" strokeLinecap="round" />
        <path d="M 800 100 Q 500 -50 200 150 T -100 300" stroke="url(#curveGrad)" strokeWidth="40" fill="none" strokeLinecap="round" opacity="0.7" />
      </svg>

      {/* Logo oficial centralizada */}
      <div className="relative z-20 flex flex-col items-center gap-6">
        <Image
          src="/logo-login.png"
          alt="Sentinel - Monitoramento Inteligente"
          width={780}
          height={300}
          className="w-[105%] max-w-[630px] h-auto drop-shadow-[0_0_40px_rgba(249,115,22,0.35)]"
          priority
        />
      </div>
    </aside>
  );
}
