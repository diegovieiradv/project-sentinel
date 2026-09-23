/**
 * Minimal API client for Sentinel frontend.
 * Fetches the backend health endpoint.
 *
 * Usage in a client component:
 *   import { checkHealth } from '@/lib/api';
 *   const status = await checkHealth();
 */

export const API_URL =
  process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export async function checkHealth(): Promise<{
  status: "up" | "down" | "unreachable";
  error?: string;
}> {
  try {
    const res = await fetch(`${API_URL}/api/v1/health`, {
      cache: "no-store",
      headers: {
        Accept: "application/json",
      },
    });

    if (!res.ok) {
      const errText = await res.text();
      return { status: "down", error: `HTTP ${res.status}: ${errText || "unreachable" }` };
    }

    const data = (await res.json()) as {
      status?: string;
      timestamp?: string;
      version?: string;
    };

    // Backend emite "UP"/"DOWN" (maiúsculo, contrato documentado).
    // Normaliza o case antes de comparar para aceitar "UP"/"up"/etc.
    const rawStatus = data.status?.toLowerCase();

    // Map known statuses
    const mapped: "up" | "down" | "unreachable" =
      rawStatus === "down" || rawStatus === "degraded"
        ? "down"
        : rawStatus === "up"
        ? "up"
        : "unreachable";

    return { status: mapped };
  } catch (err) {
    if (err instanceof Error) {
      return { status: "unreachable", error: err.message };
    }
    return { status: "unreachable", error: String(err) };
  }
}