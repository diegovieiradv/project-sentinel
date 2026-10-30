import type { NextConfig } from "next";

// O cliente agora é sempre same-origin via proxy /api/* do Next.js.
// NEXT_PUBLIC_API_URL define o destino do proxy /api/* (dev: localhost:8080; prod: Render).

const isDev = process.env.NODE_ENV === "development";

const nextConfig: NextConfig = {
  /* config options here */
  async rewrites() {
    const apiTarget = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
    if (process.env.NEXT_PUBLIC_API_URL === undefined && process.env.NODE_ENV === "production") {
      console.warn("[Sentinel] NEXT_PUBLIC_API_URL não definida em produção — o proxy /api/* cairá em localhost:8080 (apenas dev). Defina a env para apontar o destino do proxy no Render.");
    }
    return [
      {
        source: "/api/:path*",
        destination: `${apiTarget}/api/:path*`,
      },
    ];
  },
  async headers() {
    return [
      {
        source: '/(.*)',
        headers: [
          { key: 'Content-Security-Policy', value: `default-src 'self'; script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ''}; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none';` },
          { key: 'Strict-Transport-Security', value: 'max-age=31536000; includeSubDomains; preload' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), accelerometer=(), gyroscope=(), magnetometer=(), usb=(), payment=()' },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'X-Frame-Options', value: 'DENY' },
        ],
      },
    ];
  },
};

export default nextConfig;
