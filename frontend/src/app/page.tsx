/* Sentinel Prompt 03 — Identity */
import HealthStatus from "@/components/HealthStatus";
import AuthStatus from "@/components/AuthStatus";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-4 py-8">
      <div className="flex flex-col items-center gap-4 text-center">
        <h1 className="font-extrabold text-4xl sm:text-5xl text-white">
          Sentinel
        </h1>
        <p className="text-(--muted) text-lg">
          Observability & Incident Management
        </p>
        <div className="flex items-center gap-2 text-sm text-(--muted)">
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded text-(--muted)/80 bg-(--card)/50 dark:bg-(--card)/80">
            <span>Prompt 03 — Identity</span>
          </span>
        </div>
      </div>

      {/* Real session check via GET /api/v1/auth/me */}
      <div className="mt-6">
        <AuthStatus />
      </div>

      {/* Discreet backend health status */}
      <HealthStatus />
    </main>
  );
}
