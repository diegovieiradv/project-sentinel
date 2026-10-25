/* Sentinel Prompt 13 — Alert Engine: alertas do serviço (leitura) */
"use client";

import { Fragment, useEffect, useMemo, useState } from "react";
import {
  listAlerts,
  listMonitors,
  type AlertListParams,
  type AlertResponse,
  type AlertSeverity,
  type AlertStatus,
  type MonitorResponse,
  type ServiceResponse,
} from "@/lib/api";
import {
  ALERT_SEVERITY_OPTIONS,
  ALERT_STATUS_LABELS,
  ALERT_STATUS_OPTIONS,
  alertsViewState,
  alertCondition,
  formatAlertInstant,
  isFiring,
  resolutionReasonLabel,
  severityBadgeClasses,
  triggerValueLabel,
  type AlertsListStatus,
} from "@/lib/alerts";
import {
  SEVERITY_LABELS,
  WINDOW_LABELS,
  ruleScopeLabel,
} from "@/lib/alert-rules";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";

type AlertsStatus = AlertsListStatus;

/** Tamanho da página enviada ao server (contrato: default 20, máx 100). */
const ALERTS_PAGE_SIZE = 10;

/** Filtro de status → query param opcional (sem filtro = histórico todo). */
type AlertStatusFilter = "all" | AlertStatus;

/** Badge de status do ciclo de vida (palavra + cor, nunca só cor). */
function StatusBadge({ alert }: { alert: AlertResponse }) {
  const firing = isFiring(alert);
  return (
    <span
      className={
        firing
          ? "rounded bg-(--destructive) px-1.5 py-0.5 text-xs font-semibold text-white"
          : "rounded border border-(--border) px-1.5 py-0.5 text-xs font-semibold text-(--muted)"
      }
    >
      {ALERT_STATUS_LABELS[alert.status]}
    </span>
  );
}

/**
 * Alert Engine do serviço selecionado (Prompt 13) — o RESULTADO avaliado.
 *
 * Hierarquia: organização ativa → `ProjectsPanel` → projeto expandido →
 * `ServicesPanel` → serviço expandido → este painel. O pai o monta com
 * `key={`alerts-${service.id}`}`, então o estado nasce limpo por serviço —
 * mesmo padrão do `AlertRulesPanel`.
 *
 * **Somente leitura, por contrato:** a API não tem POST/PATCH/DELETE de
 * alerta (ciclo de vida é do backend), então este painel não tem nenhum
 * controle mutável — nenhum papel vê botões que não existem.
 *
 * Sem dados fake: a lista vem SEMPRE de `listAlerts` (a API é a única fonte
 * de alertas deste componente).
 */
export default function AlertsPanel({ service }: { service: ServiceResponse }) {
  const orgId = service.organizationId;
  const projectId = service.projectId;
  const serviceId = service.id;

  const [alerts, setAlerts] = useState<AlertResponse[]>([]);
  const [status, setStatus] = useState<AlertsStatus>("idle");
  const [reload, setReload] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const [statusFilter, setStatusFilter] = useState<AlertStatusFilter>("all");
  const [severityFilter, setSeverityFilter] = useState<AlertSeverity | "all">(
    "all"
  );

  // Monitores do MESMO serviço — só para os nomes dos badges de escopo (o
  // contrato de alerta envia o id, não o nome).
  const [monitors, setMonitors] = useState<MonitorResponse[]>([]);

  // Decisão de renderização da listagem — função pura em lib/alerts.ts
  // (testável sem DOM).
  const view = alertsViewState(status, alerts.length);

  const monitorNameById = useMemo(() => {
    const map: Record<string, string> = {};
    for (const monitor of monitors) map[monitor.id] = monitor.name;
    return map;
  }, [monitors]);

  // Listagem — recarrega em mudança de página, filtro, serviço ou reload.
  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      setStatus("loading");
      setListError(null);
      try {
        const params: AlertListParams = { page, size: ALERTS_PAGE_SIZE };
        if (statusFilter !== "all") params.status = statusFilter;
        if (severityFilter !== "all") params.severity = severityFilter;

        const data = await listAlerts(orgId, projectId, serviceId, params);
        if (cancelled) return;
        setAlerts(data.content);
        setTotalPages(data.totalPages);
        setTotalElements(data.totalElements);
        setStatus("ready");
      } catch (err) {
        if (cancelled) return;
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        setStatus("error");
        setListError(messageOf(err, "Could not load alerts"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, serviceId, page, statusFilter, severityFilter, reload]);

  // Monitores do serviço (nomes de escopo) — melhor esforço: falha aqui não
  // derruba a lista de alertas (os badges caem no prefixo do id).
  useEffect(() => {
    let cancelled = false;

    async function loadMonitors(): Promise<void> {
      try {
        const data = await listMonitors(orgId, projectId, serviceId, {
          size: 100,
        });
        if (!cancelled) setMonitors(data.content);
      } catch {
        // Silencioso — nomes de monitor são enriquecimento, não dado crítico.
      }
    }

    void loadMonitors();
    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, serviceId, reload]);

  return (
    <section
      aria-label="Alerts"
      className="mt-4 rounded-lg border border-(--border) p-4"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h4 className="text-sm font-semibold text-white">Alerts</h4>
          <p className="text-xs text-(--muted)">
            Fired conditions evaluated by the alert engine — read-only history.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="alerts-status-filter" className="text-xs text-(--muted)">
            Status
          </label>
          <select
            id="alerts-status-filter"
            value={statusFilter}
            onChange={(event) => {
              setStatusFilter(event.target.value as AlertStatusFilter);
              setPage(0);
            }}
            className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background)"
          >
            <option value="all">All</option>
            {ALERT_STATUS_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {ALERT_STATUS_LABELS[option]}
              </option>
            ))}
          </select>

          <label
            htmlFor="alerts-severity-filter"
            className="text-xs text-(--muted)"
          >
            Severity
          </label>
          <select
            id="alerts-severity-filter"
            value={severityFilter}
            onChange={(event) => {
              setSeverityFilter(event.target.value as AlertSeverity | "all");
              setPage(0);
            }}
            className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background)"
          >
            <option value="all">All</option>
            {ALERT_SEVERITY_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {SEVERITY_LABELS[option]}
              </option>
            ))}
          </select>
        </div>
      </div>

      {view === "loading" && (
        <p className="mt-3 text-sm text-(--muted)" role="status">
          Loading alerts…
        </p>
      )}

      {view === "error" && (
        <div
          className="mt-3 rounded-md border border-(--destructive) p-3 text-sm"
          role="alert"
        >
          <p className="text-(--destructive)">{listError}</p>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            className="mt-2 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-xs font-semibold text-white transition hover:border-(--border-hover)"
          >
            Retry
          </button>
        </div>
      )}

      {view === "empty" && (
        <p className="mt-3 text-sm text-(--muted)">
          No alerts for this service — no rule condition has fired yet.
        </p>
      )}

      {view === "list" && (
        <>
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-(--border) text-xs text-(--muted)">
                  <th scope="col" className="py-2 pr-3 font-semibold">
                    Severity
                  </th>
                  <th scope="col" className="py-2 pr-3 font-semibold">
                    Rule
                  </th>
                  <th scope="col" className="py-2 pr-3 font-semibold">
                    Condition
                  </th>
                  <th scope="col" className="py-2 pr-3 font-semibold">
                    Trigger value
                  </th>
                  <th scope="col" className="py-2 pr-3 font-semibold">
                    Status
                  </th>
                  <th scope="col" className="py-2 pr-3 font-semibold">
                    Started
                  </th>
                  <th scope="col" className="py-2 pr-3 font-semibold">
                    Resolved
                  </th>
                </tr>
              </thead>
              <tbody>
                {alerts.map((alert) => (
                  <Fragment key={alert.id}>
                    <tr className="border-b border-(--border) align-top">
                      <td className="py-2 pr-3">
                        <span className={severityBadgeClasses(alert.severity)}>
                          {SEVERITY_LABELS[alert.severity]}
                        </span>
                      </td>
                      <td className="py-2 pr-3 text-white">{alert.ruleName}</td>
                      <td className="py-2 pr-3 text-(--muted)">
                        {alertCondition(alert)}
                      </td>
                      <td className="py-2 pr-3 text-white">
                        {triggerValueLabel(alert)}
                      </td>
                      <td className="py-2 pr-3">
                        <StatusBadge alert={alert} />
                        {!isFiring(alert) && (
                          <span className="mt-1 block text-xs text-(--muted)">
                            {resolutionReasonLabel(alert.resolutionReason)}
                          </span>
                        )}
                      </td>
                      <td className="py-2 pr-3 text-(--muted)">
                        {formatAlertInstant(alert.startedAt)}
                      </td>
                      <td className="py-2 pr-3 text-(--muted)">
                        {formatAlertInstant(alert.resolvedAt)}
                      </td>
                    </tr>
                    <tr className="border-b border-(--border)">
                      <td colSpan={7} className="pb-2 text-xs text-(--muted)">
                        {alert.message} · Scope:{" "}
                        {ruleScopeLabel(alert, monitorNameById)} · Window:{" "}
                        {WINDOW_LABELS[alert.ruleWindow]} · Last evaluated:{" "}
                        {formatAlertInstant(alert.lastEvaluatedAt)}
                      </td>
                    </tr>
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>

          {/* Paginação simples: anterior/próxima + total */}
          <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-sm">
            <span className="text-(--muted)">
              {totalElements}{" "}
              {totalElements === 1 ? "alert" : "alerts"} · Page{" "}
              {Math.min(page + 1, Math.max(totalPages, 1))} of{" "}
              {Math.max(totalPages, 1)}
            </span>
            <span className="flex gap-2">
              <button
                type="button"
                onClick={() => setPage((current) => Math.max(current - 1, 0))}
                disabled={page === 0}
                className="rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-xs font-semibold text-white transition hover:border-(--border-hover) disabled:opacity-50"
              >
                Previous
              </button>
              <button
                type="button"
                onClick={() =>
                  setPage((current) =>
                    current + 1 < totalPages ? current + 1 : current
                  )
                }
                disabled={page + 1 >= totalPages}
                className="rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-xs font-semibold text-white transition hover:border-(--border-hover) disabled:opacity-50"
              >
                Next
              </button>
            </span>
          </div>
        </>
      )}
    </section>
  );
}
