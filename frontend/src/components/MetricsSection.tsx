/* Sentinel Prompt 11 — Seção de métricas históricas (monitor ou serviço) */
"use client";

import { useEffect, useId, useState } from "react";
import {
  getServiceMetrics,
  getMonitorMetrics,
  type MetricsRange,
  type MonitorMetricsResponse,
  type ServiceMetricsResponse,
} from "@/lib/api";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";
import {
  METRICS_RANGE_OPTIONS,
  formatAvgMillis,
  formatPercent,
  hasAnyMetricsData,
} from "@/lib/metrics";
import MetricsChart from "@/components/MetricsChart";

type MetricsStatus = "idle" | "loading" | "ready" | "error";

/**
 * Estado de carregamento de UMA seção de métricas. Os IDs são props
 * primitivas (não uma função `load`): assim os `useEffect` do fetch têm deps
 * estáveis e não há refetch em loop por identidade de função em cada render.
 *
 * O pai monta a seção dentro do detalhe expandido com `key` próprio
 * (`metrics-{id}`), então o estado nasce limpo ao trocar de monitor/serviço
 * — mesmo padrão do `MonitorsPanel`/`ChecksPanel`.
 *
 * - `monitorId` presente → `GET .../monitors/{monitorId}/metrics`;
 * - `monitorId` ausente → `GET .../services/{serviceId}/metrics`.
 */
export default function MetricsSection({
  title,
  organizationId,
  projectId,
  serviceId,
  monitorId,
  anchorId,
}: {
  title: string;
  organizationId: string;
  projectId: string;
  serviceId: string;
  monitorId?: string;
  /**
   * `id` aplicado na raiz da seção — alvo da âncora da navegação central da
   * home (`#metrics`). Só a instância de MÉTRICAS DE SERVIÇO recebe
   * `anchorId="metrics"` (a instância de monitor, dentro do `MonitorsPanel`,
   * não recebe): como apenas um serviço fica expandido por vez, o id é único.
   */
  anchorId?: string;
}) {
  const rangeSelectId = useId();

  const [range, setRange] = useState<MetricsRange>("24h");
  const [data, setData] = useState<
    MonitorMetricsResponse | ServiceMetricsResponse | null
  >(null);
  const [status, setStatus] = useState<MetricsStatus>("idle");
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  // Fetch — recarrega em mudança de faixa/reload e de alvo (ids nas deps).
  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      setStatus("loading");
      setError(null);
      try {
        const result = monitorId
          ? await getMonitorMetrics(
              organizationId,
              projectId,
              serviceId,
              monitorId,
              range
            )
          : await getServiceMetrics(organizationId, projectId, serviceId, range);
        if (cancelled) return;
        setData(result);
        setStatus("ready");
      } catch (err) {
        if (cancelled) return;
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        setStatus("error");
        setError(messageOf(err, "Could not load metrics"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [organizationId, projectId, serviceId, monitorId, range, reload]);

  const summary = data?.summary;

  return (
    <div id={anchorId} className="mt-2 border-t border-(--border) pt-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs font-semibold uppercase tracking-wide text-(--muted)">
          {title}
        </p>
        <span className="flex items-center gap-2">
          <label htmlFor={rangeSelectId} className="sr-only">
            Metrics time range
          </label>
          <select
            id={rangeSelectId}
            value={range}
            onChange={(event) => setRange(event.target.value as MetricsRange)}
            disabled={status === "loading"}
            className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-xs text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
          >
            {METRICS_RANGE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </span>
      </div>

      {status === "loading" || status === "idle" ? (
        <p className="mt-2 text-sm text-(--muted)" role="status">Loading metrics…</p>
      ) : status === "error" ? (
        <div className="mt-2 rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-2 text-sm">
          <p className="text-(--destructive)">
            {error ?? "Could not load metrics"}
          </p>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            className="mt-2 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white"
          >
            Retry
          </button>
        </div>
      ) : (
        data &&
        summary && (
          <>
            {/* Resumo da janela — taxas vêm do servidor; null → "—", nunca 0 */}
            <dl className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <div className="rounded-md border border-(--border) bg-(--surface) px-2 py-1.5">
                <dt className="text-xs text-(--muted)">Availability</dt>
                <dd className="text-sm font-semibold text-white">
                  {formatPercent(summary.availability)}
                </dd>
              </div>
              <div className="rounded-md border border-(--border) bg-(--surface) px-2 py-1.5">
                <dt className="text-xs text-(--muted)">Success rate</dt>
                <dd className="text-sm font-semibold text-white">
                  {formatPercent(summary.successRate)}
                </dd>
              </div>
              <div className="rounded-md border border-(--border) bg-(--surface) px-2 py-1.5">
                <dt className="text-xs text-(--muted)">Avg latency</dt>
                <dd className="text-sm font-semibold text-white">
                  {formatAvgMillis(summary.avgLatencyMs)}
                </dd>
              </div>
              <div className="rounded-md border border-(--border) bg-(--surface) px-2 py-1.5">
                <dt className="text-xs text-(--muted)">Checks</dt>
                <dd className="text-sm font-semibold text-white">
                  {summary.totalChecks}
                </dd>
              </div>
            </dl>

            {/* Contagens brutas — somam exatamente totalChecks (contrato) */}
            <p className="mt-1.5 text-xs text-(--muted)">
              {summary.success} success · {summary.degraded} degraded ·{" "}
              {summary.targetFailure} target failures ·{" "}
              {summary.sentinelInternalFailure} internal failures
            </p>

            {hasAnyMetricsData(data.buckets) ? (
              <MetricsChart
                buckets={data.buckets}
                rangeLabel={
                  METRICS_RANGE_OPTIONS.find((option) => option.value === data.range)
                    ?.label ?? data.range
                }
              />
            ) : (
              <p className="mt-2 text-sm text-(--muted)">
                No checks recorded in this period.
              </p>
            )}

            {/* Breakdown por monitor — só existe no escopo de SERVIÇO */}
            {"monitors" in data && data.monitors.length > 0 && (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-(--border) text-left text-(--muted)">
                      <th className="py-1 pr-2 font-medium">Monitor</th>
                      <th className="py-1 pr-2 text-right font-medium">Checks</th>
                      <th className="py-1 pr-2 text-right font-medium">
                        Availability
                      </th>
                      <th className="py-1 pr-2 text-right font-medium">
                        Success rate
                      </th>
                      <th className="py-1 text-right font-medium">Avg</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.monitors.map((row) => (
                      <tr
                        key={row.monitorId}
                        className="border-b border-(--border)/30"
                      >
                        <td className="py-1 pr-2 font-medium text-white">
                          {row.monitorName}
                        </td>
                        <td className="py-1 pr-2 text-right text-(--muted)">
                          {row.totalChecks}
                        </td>
                        <td className="py-1 pr-2 text-right text-(--muted)">
                          {formatPercent(row.availability)}
                        </td>
                        <td className="py-1 pr-2 text-right text-(--muted)">
                          {formatPercent(row.successRate)}
                        </td>
                        <td className="py-1 text-right text-(--muted)">
                          {formatAvgMillis(row.avgLatencyMs)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* Janela exata usada no cálculo — meia-aberta [from, to) */}
            <p className="mt-2 text-xs text-(--muted)">
              Window [{data.from}, {data.to}) · bucket {data.bucketSeconds}s
            </p>
          </>
        )
      )}
    </div>
  );
}
