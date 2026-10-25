"use client";

import { useEffect, useState } from "react";
import { checkHealth, API_URL } from "@/lib/api";

/** Status de saúde exposto ao componente pai (o card "Backend" da home). */
export type HealthStatusValue = "up" | "down" | "unreachable";

interface HealthStatusProps {
  /** Optional override for the API URL (useful for testing/staging) */
  apiUrl?: string;
  /**
   * Reporta o status real de saúde ao componente pai.
   *
   * Quando informada o componente vira **headless** (mesmo padrão do
   * `AuthStatus`): o `checkHealth()` continua rodando UMA vez aqui, o chip
   * não é renderizado e o valor aparece na apresentação do pai — assim não há
   * chip duplicado nem segundo `checkHealth()` na mesma tela.
   * Sem a prop, o comportamento (chip "Backend: UP/DOWN/unreachable") é o
   * mesmo de sempre.
   */
  onStatusChange?: (status: HealthStatusValue) => void;
}

export default function HealthStatus({
  apiUrl,
  onStatusChange,
}: HealthStatusProps = {}) {
  const [status, setStatus] = useState<HealthStatusValue | null>(null);

  // Dependency extracted for ESLint exhaustive-deps check
  const checkDeps = apiUrl || API_URL;

  useEffect(() => {
    let cancelled = false;

    checkHealth()
      .then((result) => {
        if (!cancelled) {
          setStatus(result.status as "up" | "down" | "unreachable");
        }
      })
      .catch(() => {
        if (!cancelled) {
          setStatus("unreachable");
        }
      });

    return () => {
      cancelled = true;
    };
  }, [checkDeps]);

  /**
   * Reporta o status ao pai (quando a prop existe) — efeito separado do fetch
   * para a identidade do callback não re-disparar o `checkHealth()`.
   */
  useEffect(() => {
    if (status === null) return;
    onStatusChange?.(status);
  }, [status, onStatusChange]);

  // Headless: o pai é quem apresenta o status (card "Backend" da home).
  if (onStatusChange) {
    return null;
  }

  if (status === null) {
    return null; // loading state: nothing rendered until first fetch completes
  }

  const statusLabels: Record<
    "up" | "down" | "unreachable",
    { label: string; className: string; bgClass: string }
  > = {
    up: {
      label: "Backend: UP",
      className: "text-green-600 dark:text-green-500",
      bgClass: "bg-green-600/10 dark:bg-green-500/10 border-green-600/10",
    },
    down: {
      label: "Backend: DOWN",
      className: "text-red-600 dark:text-red-400",
      bgClass: "bg-red-600/10 dark:bg-red-500/10 border-red-600/10",
    },
    unreachable: {
      label: "Backend: unreachable",
      className: "text-yellow-600 dark:text-yellow-400",
      bgClass: "bg-yellow-600/10 dark:bg-yellow-500/10 border-yellow-600/10",
    },
  };

  const s = statusLabels[status];

  return (
    <div
      className={`mb-4 px-3 py-1.5 rounded-md text-sm font-medium gap-2 align-baseline ${s.bgClass} ${s.className}`}
    >
      <span>{s.label}</span>
    </div>
  );
}