/* Sentinel Prompt 16 — Painel operacional do projeto (read model) */
"use client";

import { useEffect, useId, useState } from "react";
import {
  getDashboard,
  type DashboardRange,
  type DashboardResponse,
} from "@/lib/api";
import { severityBadgeClasses } from "@/lib/alerts";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";
import {
  DASHBOARD_ACTIVITY_LABELS,
  DASHBOARD_HEALTH_TILES,
  DASHBOARD_INCIDENT_TILES,
  DASHBOARD_SERVICE_STATUS_LABELS,
  activityTitle,
  dashboardKpis,
  formatActivityTime,
  formatWindow,
  hasDashboardData,
  incidentStatusLabel,
  isDashboardRange,
  pluralizeCount,
  rangeLabel,
  severityLabel,
} from "@/lib/dashboard";
import { METRICS_RANGE_OPTIONS } from "@/lib/metrics";

type DashboardStatus = "idle" | "loading" | "ready" | "error";

/**
 * Painel operacional do projeto (Prompt 16) — UMA resposta read-only que
 * compõe saúde, disponibilidade, alertas, incidentes, serviços que pedem
 * atenção e atividade recente, tudo sobre a mesma janela `[from, to)`.
 *
 * O pai monta a seção dentro do detalhe expandido com `key` próprio
 * (`dashboard-{id}`), então o estado nasce limpo ao trocar de projeto —
 * mesmo padrão de `ServicesPanel`/`MetricsSection`.
 *
 * - leitura com qualquer papel: o tenant já é provado pelo path;
 * - refresh MANUAL (botão): sem polling, sem WebSocket, sem SSE — a janela
 *   só muda quando o usuário troca a faixa ou pede um novo carregamento;
 * - `null` do backend (denominador zero) vira `"—"`, nunca `0`;
 * - zero data NÃO é erro: 200 com contagens zeradas → estado vazio.
 */
export default function DashboardPanel({
  organizationId,
  projectId,
}: {
  organizationId: string;
  projectId: string;
}) {
  const rangeSelectId = useId();

  const [range, setRange] = useState<DashboardRange>("24h");
  const [data, setData] = useState<DashboardResponse | null>(null);
  const [status, setStatus] = useState<DashboardStatus>("idle");
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  // Fetch — recarrega em mudança de faixa/reload e de alvo (ids nas deps).
  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      setStatus("loading");
      setError(null);
      try {
        const result = await getDashboard(organizationId, projectId, range);
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
        setError(messageOf(err, "Could not load dashboard"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [organizationId, projectId, range, reload]);

  const sectionTitle = "mt-3 text-xs font-semibold uppercase tracking-wide text-(--muted)";

  return (
    <section className="mt-3 rounded-md border border-(--border) bg-[#202D2E] p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="font-semibold text-[#F5F7F5]">Dashboard</h3>
          <p className="mt-1 text-xs text-(--muted)">
            Operational view composed from services, checks, alerts and
            incidents of this project — read-only, no automatic refresh.
          </p>
        </div>

        <span className="flex items-center gap-2">
          <label htmlFor={rangeSelectId} className="sr-only">
            Dashboard time range
          </label>
          <select
            id={rangeSelectId}
            value={range}
            onChange={(event) => {
              if (isDashboardRange(event.target.value)) {
                setRange(event.target.value);
              }
            }}
            disabled={status === "loading"}
            className="rounded-md border border-(--border) bg-[#2D4143] px-3 py-3 min-h-[44px] text-xs text-[#F5F7F5] focus:outline-none focus-visible:ring-2 focus-visible:ring-[#B0F35C] focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
          >
            {METRICS_RANGE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            disabled={status === "loading"}
            className="rounded-md border border-(--border) px-3 py-2.5 min-h-[44px] text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-[#F5F7F5] disabled:opacity-50"
          >
            {status === "loading" ? "Loading…" : "Refresh"}
          </button>
        </span>
      </div>

      {status === "loading" || status === "idle" ? (
        <p className="mt-3 text-sm text-(--muted)" role="status">Loading dashboard…</p>
      ) : status === "error" ? (
        <div className="mt-3 rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-3 text-sm">
          <p className="text-(--destructive)">
            {error ?? "Could not load dashboard"}
          </p>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            className="mt-2 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-[#F5F7F5]"
          >
            Retry
          </button>
        </div>
      ) : (
        data && (
          <>
            {!hasDashboardData(data) ? (
              /* Zero data: 200 com zeros — estado vazio, nunca um erro */
              <p className="mt-3 text-sm text-(--muted)">
                No services, checks, alerts or incidents recorded for this
                project yet.
              </p>
            ) : (
              <>
                {/* KPIs da janela — taxas/null já formatadas pelos helpers */}
                <dl className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {dashboardKpis(data).map((kpi) => (
                    <div
                      key={kpi.label}
                      className="rounded-md border border-(--border) bg-[#2D4143] px-2 py-1.5"
                    >
                      <dt className="text-xs text-(--muted)">{kpi.label}</dt>
                      <dd className="text-sm font-semibold text-[#F5F7F5]">
                        {kpi.value}
                      </dd>
                    </div>
                  ))}
                </dl>

                {/* Saúde dos serviços — contagens inteiras, inclui inativos */}
                <h4 className={sectionTitle}>Services</h4>
                <dl className="mt-1 grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {DASHBOARD_HEALTH_TILES.map((tile) => (
                    <div
                      key={tile.key}
                      className="rounded-md border border-(--border) bg-[#2D4143] px-2 py-1.5"
                    >
                      <dt className="text-xs text-(--muted)">{tile.label}</dt>
                      <dd className="text-sm font-semibold text-[#F5F7F5]">
                        {data.health[tile.key]}
                      </dd>
                    </div>
                  ))}
                </dl>
                <p className="mt-1.5 text-xs text-(--muted)">
                  {pluralizeCount(data.health.totalServices, "service")} in
                  this project (active and inactive).
                </p>

                {/* Alertas em FIRING — total, severidades e top 10 */}
                <h4 className={sectionTitle}>Alerts</h4>
                <p className="mt-1 text-sm text-[#F5F7F5]">
                  {pluralizeCount(data.alerts.firingTotal, "firing alert")}
                </p>
                <p className="text-xs text-(--muted)">
                  SEV1 {data.alerts.sev1} · SEV2 {data.alerts.sev2} · SEV3{" "}
                  {data.alerts.sev3} · SEV4 {data.alerts.sev4}
                </p>
                {data.alerts.activeAlerts.length > 0 ? (
                  <div className="mt-1.5 overflow-x-auto">
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="border-b border-(--border) text-left text-(--muted)">
                          <th className="py-1 pr-2 font-medium">Severity</th>
                          <th className="py-1 pr-2 font-medium">Rule</th>
                          <th className="py-1 pr-2 font-medium">Service</th>
                          <th className="py-1 pr-2 font-medium">Fired</th>
                          <th className="py-1 font-medium">Incident</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.alerts.activeAlerts.map((alert) => (
                          <tr
                            key={alert.alertId}
                            className="border-b border-(--border)/30"
                          >
                            <td className="py-1 pr-2">
                              <span className={severityBadgeClasses(alert.severity)}>
                                {severityLabel(alert.severity)}
                              </span>
                            </td>
                            <td className="py-1 pr-2 font-medium text-[#F5F7F5]">
                              {alert.ruleName}
                            </td>
                            <td className="py-1 pr-2 text-(--muted)">
                              {alert.serviceName}
                            </td>
                            <td className="py-1 pr-2 text-(--muted)">
                              {formatActivityTime(alert.firedAt)}
                            </td>
                            <td className="py-1 text-(--muted)">
                              {alert.incidentId ? "Linked" : "—"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="mt-1 text-sm text-(--muted)">
                    No firing alerts in this project.
                  </p>
                )}

                {/* Incidentes ativos — nunca inclui RESOLVED */}
                <h4 className={sectionTitle}>Incidents</h4>
                <p className="mt-1 text-sm text-[#F5F7F5]">
                  {pluralizeCount(data.incidents.activeTotal, "active incident")}
                </p>
                <p className="text-xs text-(--muted)">
                  {DASHBOARD_INCIDENT_TILES.map((tile, index) => (
                    <span key={tile.key}>
                      {index > 0 ? " · " : ""}
                      {tile.label} {data.incidents[tile.key]}
                    </span>
                  ))}
                </p>
                {data.incidents.activeIncidents.length > 0 ? (
                  <div className="mt-1.5 overflow-x-auto">
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="border-b border-(--border) text-left text-(--muted)">
                          <th className="py-1 pr-2 font-medium">Severity</th>
                          <th className="py-1 pr-2 font-medium">Status</th>
                          <th className="py-1 pr-2 font-medium">Title</th>
                          <th className="py-1 pr-2 font-medium">Service</th>
                          <th className="py-1 pr-2 font-medium">Started</th>
                          <th className="py-1 text-right font-medium">Alerts</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.incidents.activeIncidents.map((incident) => (
                          <tr
                            key={incident.incidentId}
                            className="border-b border-(--border)/30"
                          >
                            <td className="py-1 pr-2">
                              <span
                                className={severityBadgeClasses(incident.severity)}
                              >
                                {severityLabel(incident.severity)}
                              </span>
                            </td>
                            <td className="py-1 pr-2 text-(--muted)">
                              {incidentStatusLabel(incident.status)}
                            </td>
                            <td className="py-1 pr-2 font-medium text-[#F5F7F5]">
                              {incident.title}
                            </td>
                            <td className="py-1 pr-2 text-(--muted)">
                              {incident.serviceName}
                            </td>
                            <td className="py-1 pr-2 text-(--muted)">
                              {formatActivityTime(incident.startedAt)}
                            </td>
                            <td className="py-1 text-right text-(--muted)">
                              {incident.associatedAlertsCount}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="mt-1 text-sm text-(--muted)">
                    No active incidents in this project.
                  </p>
                )}

                {/* Serviços que pedem atenção — critério do backend, top 20 */}
                <h4 className={sectionTitle}>Services needing attention</h4>
                {data.servicesNeedingAttention.length > 0 ? (
                  <div className="mt-1.5 overflow-x-auto">
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="border-b border-(--border) text-left text-(--muted)">
                          <th className="py-1 pr-2 font-medium">Service</th>
                          <th className="py-1 pr-2 font-medium">Status</th>
                          <th className="py-1 pr-2 font-medium">Severity</th>
                          <th className="py-1 font-medium">Incident</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.servicesNeedingAttention.map((service) => (
                          <tr
                            key={service.serviceId}
                            className="border-b border-(--border)/30"
                          >
                            <td className="py-1 pr-2 font-medium text-[#F5F7F5]">
                              {service.serviceName}
                            </td>
                            <td className="py-1 pr-2 text-(--muted)">
                              {DASHBOARD_SERVICE_STATUS_LABELS[service.status]}
                            </td>
                            <td className="py-1 pr-2">
                              {service.highestSeverity ? (
                                <span
                                  className={severityBadgeClasses(
                                    service.highestSeverity
                                  )}
                                >
                                  {severityLabel(service.highestSeverity)}
                                </span>
                              ) : (
                                <span className="text-(--muted)">—</span>
                              )}
                            </td>
                            <td className="py-1 text-(--muted)">
                              {service.hasActiveIncident ? "Active" : "—"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="mt-1 text-sm text-(--muted)">
                    No service needs attention right now.
                  </p>
                )}

                {/* Atividade recente — união de alertas e incidentes, DESC */}
                <h4 className={sectionTitle}>Recent activity</h4>
                {data.recentActivity.length > 0 ? (
                  <ul className="mt-1 space-y-1">
                    {data.recentActivity.map((item, index) => (
                      <li
                        key={`${item.resourceType ?? "row"}-${
                          item.resourceId ?? index
                        }-${item.timestamp}`}
                        className="flex flex-wrap items-baseline gap-2 text-xs"
                      >
                        <span className="text-(--muted)">
                          {formatActivityTime(item.timestamp)}
                        </span>
                        <span className="font-medium text-[#F5F7F5]">
                          {DASHBOARD_ACTIVITY_LABELS[item.type]}
                        </span>
                        {item.severity && (
                          <span className={severityBadgeClasses(item.severity)}>
                            {severityLabel(item.severity)}
                          </span>
                        )}
                        <span className="text-(--muted)">
                          {activityTitle(item)}
                          {item.serviceName ? ` · ${item.serviceName}` : ""}
                        </span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="mt-1 text-sm text-(--muted)">
                    No activity in this period.
                  </p>
                )}

                {/* Janela exata + limites das listas (contrato do backend) */}
                <p className="mt-3 text-xs text-(--muted)">
                  Window {formatWindow(data.windowFrom, data.windowTo)} ·{" "}
                  {rangeLabel(data.rangeKey)} · showing up to 10 alerts, 10
                  incidents, 20 services and 20 activity rows.
                </p>
              </>
            )}
          </>
        )
      )}
    </section>
  );
}
