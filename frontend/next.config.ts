import type { NextConfig } from "next";

// Mesmo fallback de src/lib/api.ts. A API roda em outra origem (cross-origin);
// sem ela em `connect-src` o fetch do frontend é bloqueado pela própria CSP.
const apiOrigin = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

const isDev = process.env.NODE_ENV === "development";

const nextConfig: NextConfig = {
  /* config options here */
  async headers() {
    return [
      {
        source: '/(.*)',
        headers: [
          { key: 'Content-Security-Policy', value: `default-src 'self'; script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ''}; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self' ${apiOrigin}; frame-ancestors 'none';` },
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
