"use client";
import Image from "next/image";

/**
 * UI-UX-V2 — Coluna decorativa do login (redesign verde).
 *
 * Fundo verde-petróleo #192526 com gradientes #B0F35C sutis, curvas
 * luminosas, grade de pontos e globo pontilhado — todo o ornamento é
 * CSS/SVG (nenhum bitmap decorativo); o único raster é o logo oficial
 * recortado da referência (sentinel-logo.png). O recorte carrega o fundo
 * escuro original, então usamos mix-blend-screen para fundi-lo no painel
 * sem halo retangular.
 */
export default function AuthDecorative() {
  return (
    <aside
      className="relative hidden items-center justify-center overflow-hidden bg-[#192526] lg:flex lg:w-1/2"
      aria-label="Sentinel"
    >
      {/* Gradientes verdes sutis (CSS puro) */}
      <div aria-hidden="true" className="absolute inset-0 z-0">
        <div
          className="absolute -top-[30%] -left-[20%] h-[85%] w-[90%] rounded-full blur-3xl"
          style={{
            background:
              "radial-gradient(closest-side, rgba(176, 243, 92, 0.14), rgba(176, 243, 92, 0.05) 55%, transparent 75%)",
          }}
        />
        <div
          className="absolute -bottom-[35%] -right-[15%] h-[110%] w-[110%] rounded-full blur-3xl"
          style={{
            background:
              "radial-gradient(closest-side, rgba(62, 142, 90, 0.20), rgba(25, 37, 38, 0) 70%)",
          }}
        />
        <div
          className="absolute bottom-[5%] left-[8%] h-[45%] w-[55%] rounded-full blur-2xl"
          style={{
            background:
              "radial-gradient(closest-side, rgba(176, 243, 92, 0.08), transparent 75%)",
          }}
        />
      </div>

      {/* Grade de pontos (CSS pattern + máscara radial) */}
      <div
        aria-hidden="true"
        className="absolute inset-0 z-[1] opacity-25"
        style={{
          backgroundImage:
            "radial-gradient(rgba(176, 243, 92, 0.8) 1px, transparent 1.4px)",
          backgroundSize: "26px 26px",
          maskImage:
            "radial-gradient(ellipse 75% 65% at 22% 25%, black 25%, transparent 72%)",
          WebkitMaskImage:
            "radial-gradient(ellipse 75% 65% at 22% 25%, black 25%, transparent 72%)",
        }}
      />
      <div
        aria-hidden="true"
        className="absolute inset-0 z-[1] opacity-20"
        style={{
          backgroundImage:
            "radial-gradient(rgba(176, 243, 92, 0.7) 1px, transparent 1.4px)",
          backgroundSize: "32px 32px",
          maskImage:
            "radial-gradient(ellipse 60% 55% at 80% 88%, black 20%, transparent 70%)",
          WebkitMaskImage:
            "radial-gradient(ellipse 60% 55% at 80% 88%, black 20%, transparent 70%)",
        }}
      />

      {/* Globo pontilhado (SVG — dots via pattern, fade via mask) */}
      <svg
        aria-hidden="true"
        viewBox="0 0 400 400"
        className="absolute right-[-6%] top-[4%] z-[5] w-[52%] max-w-[440px] opacity-80"
      >
        <defs>
          <pattern
            id="auth-globe-dots"
            width="12"
            height="12"
            patternUnits="userSpaceOnUse"
          >
            <circle cx="2" cy="2" r="1.4" fill="#B0F35C" />
          </pattern>
          <radialGradient id="auth-globe-fade" cx="0.35" cy="0.35" r="0.85">
            <stop offset="0%" stopColor="#ffffff" stopOpacity="0.9" />
            <stop offset="55%" stopColor="#666666" stopOpacity="0.45" />
            <stop offset="100%" stopColor="#000000" stopOpacity="0" />
          </radialGradient>
          <mask id="auth-globe-mask">
            <circle cx="200" cy="200" r="170" fill="url(#auth-globe-fade)" />
          </mask>
        </defs>
        <g mask="url(#auth-globe-mask)">
          <circle cx="200" cy="200" r="170" fill="url(#auth-globe-dots)" />
          <ellipse
            cx="200"
            cy="200"
            rx="170"
            ry="60"
            fill="none"
            stroke="#B0F35C"
            strokeOpacity="0.3"
            strokeDasharray="3 9"
          />
          <ellipse
            cx="200"
            cy="200"
            rx="170"
            ry="118"
            fill="none"
            stroke="#B0F35C"
            strokeOpacity="0.2"
            strokeDasharray="3 9"
          />
          <ellipse
            cx="200"
            cy="200"
            rx="60"
            ry="170"
            fill="none"
            stroke="#B0F35C"
            strokeOpacity="0.2"
            strokeDasharray="3 9"
          />
          <ellipse
            cx="200"
            cy="200"
            rx="118"
            ry="170"
            fill="none"
            stroke="#B0F35C"
            strokeOpacity="0.2"
            strokeDasharray="3 9"
          />
        </g>
        <circle
          cx="200"
          cy="200"
          r="170"
          fill="none"
          stroke="#B0F35C"
          strokeOpacity="0.28"
          strokeWidth="1.2"
        />
      </svg>

      {/* Curvas luminosas (SVG com glow) */}
      <svg
        aria-hidden="true"
        viewBox="0 0 900 700"
        preserveAspectRatio="none"
        className="absolute inset-0 z-10 h-full w-full"
      >
        <defs>
          <linearGradient id="auth-curve" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#B0F35C" stopOpacity="0.05" />
            <stop offset="45%" stopColor="#B0F35C" stopOpacity="0.55" />
            <stop offset="100%" stopColor="#3E8E5A" stopOpacity="0.25" />
          </linearGradient>
          <linearGradient id="auth-curve-b" x1="1" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#B0F35C" stopOpacity="0.4" />
            <stop offset="60%" stopColor="#3E8E5A" stopOpacity="0.18" />
            <stop offset="100%" stopColor="#B0F35C" stopOpacity="0.05" />
          </linearGradient>
          <filter
            id="auth-glow"
            x="-40%"
            y="-40%"
            width="180%"
            height="180%"
          >
            <feGaussianBlur stdDeviation="12" />
          </filter>
        </defs>
        <path
          d="M -60 540 Q 260 250 520 420 T 960 240"
          stroke="url(#auth-curve)"
          strokeWidth="80"
          fill="none"
          strokeLinecap="round"
          filter="url(#auth-glow)"
          opacity="0.35"
        />
        <path
          d="M -60 540 Q 260 250 520 420 T 960 240"
          stroke="url(#auth-curve)"
          strokeWidth="2.5"
          fill="none"
          strokeLinecap="round"
          opacity="0.85"
        />
        <path
          d="M 950 120 Q 620 -40 340 130 T -80 210"
          stroke="url(#auth-curve-b)"
          strokeWidth="48"
          fill="none"
          strokeLinecap="round"
          filter="url(#auth-glow)"
          opacity="0.3"
        />
        <path
          d="M 950 120 Q 620 -40 340 130 T -80 210"
          stroke="url(#auth-curve-b)"
          strokeWidth="2"
          fill="none"
          strokeLinecap="round"
          opacity="0.7"
        />
      </svg>

      {/* Logo oficial centralizada (bitmap recortado da referência) */}
      <div className="relative z-20 flex flex-col items-center">
        <Image
          src="/sentinel-logo.png"
          alt="Sentinel — Monitoramento e Observabilidade"
          width={835}
          height={330}
          preload
          className="h-auto w-[min(56%,480px)] mix-blend-screen"
        />
      </div>
    </aside>
  );
}
