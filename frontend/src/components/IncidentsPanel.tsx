/* Sentinel Prompt 14 — Incidents aninhados no serviço */
"use client";

import { FormEvent, Fragment, useEffect, useState } from "react";
import {
  ApiError,
  createIncident,
  listIncidents,
  type AlertSeverity,
  type IncidentListParams,
  type IncidentResponse,
  type IncidentStatus,
  type ServiceResponse,
} from "@/lib/api";
import {
  INCIDENT_SEVERITY_OPTIONS,
  INCIDENT_SOURCE_LABELS,
  INCIDENT_STATUS_LABELS,
  INCIDENT_STATUS_OPTIONS,
  canManageIncidents,
  formatIncidentInstant,
  incidentsViewState,
  incidentSeverityBadgeClasses,
  incidentSourceBadgeClasses,
  incidentStatusBadgeClasses,
  openIncidentCount,
  type IncidentsListStatus,
} from "@/lib/incidents";
import { SEVERITY_LABELS } from "@/lib/alert-rules";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";
import IncidentDetail from "@/components/IncidentDetail";

type IncidentsStatus = IncidentsListStatus;

/** Aviso sob a lista — sucesso (accent) ou falha de ação (destructive). */
type IncidentNotice = { kind: "success" | "error"; text: string } | null;

/** Filtro do ciclo de vida → query param opcional (sem filtro = todos). */
type IncidentStatusFilter = "all" | IncidentStatus;

/** Tamanho da página enviada ao server (contrato: default 20, máx 100). */
const INCIDENTS_PAGE_SIZE = 10;

/** Rascunho de criação — mesmos campos de `IncidentCreateRequest`. */
type IncidentCreateForm = {
  title: string;
  description: string;
  severity: AlertSeverity;
  /** `""` = o server usa o relógio do domínio ("agora"). */
  startedAt: string;
};

function emptyIncidentForm(): IncidentCreateForm {
  return { title: "", description: "", severity: "SEV2", startedAt: "" };
}

/**
 * Mensagem legível de uma falha ao criar um incidente.
 * - **403** → papel insuficiente (feedback; NUNCA logout — só 401 derruba a sessão).
 * - **409** `PROJECT_INACTIVE`/`SERVICE_INACTIVE` → pai inativo: leitura ok,
 *   criação não (a mensagem diz exatamente isso).
 * - **400** `VALIDATION_ERROR` → a validação de campo do server (`title: must
 *   not be blank`, `startedAt: must not be more than 5 minutes in the future`, …).
 */
function incidentCreateError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You don't have permission to create incidents in this organization.";
    }
    if (err.code === "SERVICE_INACTIVE") {
      return "This service is inactive; incidents can be viewed but not created.";
    }
    if (err.code === "PROJECT_INACTIVE") {
      return "This project is inactive; incidents can be viewed but not created.";
    }
    return err.message;
  }
  return fallback;
}

/**
 * Incidents do serviço selecionado (Prompt 14) — o ciclo de vida MANUAL.
 *
 * Hierarquia: organização ativa → `ProjectsPanel` → projeto expandido →
 * `ServicesPanel` → serviço expandido → este painel. O pai o monta com
 * `key={`incidents-${service.id}`}`, então o estado nasce limpo por serviço —
 * mesmo padrão do `AlertRulesPanel`.
 *
 * RBAC aqui é só UX: VIEWER não recebe controles mutáveis, mas a autoridade
 * continua no backend — um **403** vira mensagem na UI, jamais logout.
 * Serviço pai inativo mantém a leitura; o backend responde **409** em
 * criar/editar/transitar/vincular (exibido aqui).
 *
 * A transição de status NUNCA acontece por PATCH do conteúdo: ela usa a rota
 * `/status` dedicada (dentro do `IncidentDetail`), espelhando o contrato.
 *
 * Sem dados fake: a lista vem SEMPRE de `listIncidents` (a API é a única
 * fonte de incidentes deste componente).
 */
export default function IncidentsPanel({
  service,
}: {
  service: ServiceResponse;
}) {
  const orgId = service.organizationId;
  const projectId = service.projectId;
  const serviceId = service.id;
  /** VIEWER é somente-leitura no cliente (o backend continua sendo a autoridade). */
  const canManage = canManageIncidents(service.role);

  const [incidents, setIncidents] = useState<IncidentResponse[]>([]);
  const [status, setStatus] = useState<IncidentsStatus>("idle");
  const [reload, setReload] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const [statusFilter, setStatusFilter] = useState<IncidentStatusFilter>("all");
  const [severityFilter, setSeverityFilter] = useState<AlertSeverity | "all">(
    "all"
  );

  const [createForm, setCreateForm] = useState<IncidentCreateForm>(
    emptyIncidentForm()
  );
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [notice, setNotice] = useState<IncidentNotice>(null);

  const busy = creating;
  const hasFilter = statusFilter !== "all" || severityFilter !== "all";

  // Decisão de renderização da listagem — função pura em lib/incidents.ts
  // (testável sem DOM).
  const view = incidentsViewState(status, incidents.length);
  const openHere = openIncidentCount(incidents);

  // Listagem — recarrega em mudança de página, filtro, serviço ou reload.
  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      setStatus("loading");
      setListError(null);
      try {
        const params: IncidentListParams = { page, size: INCIDENTS_PAGE_SIZE };
        if (statusFilter !== "all") params.status = statusFilter;
        if (severityFilter !== "all") params.severity = severityFilter;

        const data = await listIncidents(orgId, projectId, serviceId, params);
        if (cancelled) return;
        setIncidents(data.content);
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
        setListError(messageOf(err, "Could not load incidents"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, serviceId, page, statusFilter, severityFilter, reload]);

  /** POST .../incidents — criação (validada no cliente e no server). */
  async function onCreateIncident(
    event: FormEvent<HTMLFormElement>
  ): Promise<void> {
    event.preventDefault();
    if (!canManage || busy) return;

    if (createForm.title.trim() === "") {
      setCreateError("title: must not be blank");
      return;
    }

    setCreating(true);
    setCreateError(null);
    setNotice(null);
    try {
      const created = await createIncident(orgId, projectId, serviceId, {
        title: createForm.title.trim(),
        description:
          createForm.description.trim() === ""
            ? undefined
            : createForm.description.trim(),
        severity: createForm.severity,
        ...(createForm.startedAt === ""
          ? {}
          : { startedAt: new Date(createForm.startedAt).toISOString() }),
      });
      setCreateForm(emptyIncidentForm());
      setNotice({
        kind: "success",
        text: `Incident "${created.title}" created.`,
      });
      setReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setCreateError(
        incidentCreateError(
          err,
          "Could not create the incident. Is the backend running?"
        )
      );
    } finally {
      setCreating(false);
    }
  }

  /** PATCH bem-sucedido → atualiza a linha SEM refazer o fetch. */
  function onIncidentUpdated(next: IncidentResponse): void {
    setIncidents((prev) =>
      prev.map((item) => (item.id === next.id ? next : item))
    );
    setNotice({
      kind: "success",
      text: `Incident "${next.title}" updated.`,
    });
  }

  return (
    <section className="mt-3 rounded-md border border-(--border) bg-(--card) p-4">
      <h3 className="font-semibold text-white">Incidents — {service.name}</h3>

      <p className="mt-1 text-xs text-(--muted)">
        Manual incident lifecycle with an append-only timeline. Status moves
        only through the dedicated transition route — resolved incidents are
        terminal.
      </p>

      {/* Serviço inativo → leitura ok, mutação devolve 409 SERVICE_INACTIVE */}
      {!service.active && (
        <p className="mt-2 rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-(--muted)">
          <span className="font-medium text-white">This service is inactive;</span>{" "}
          incidents can be viewed but not modified.
        </p>
      )}

      {service.role === "VIEWER" && (
        <p className="mt-2 text-sm text-(--muted)">
          Your role in this organization is{" "}
          <span className="font-medium text-white">VIEWER</span>. You can view
          incidents; creating, editing, status changes and alert linking
          require the <span className="font-medium text-white">ADMIN</span> or{" "}
          <span className="font-medium text-white">OPERATOR</span> role.
        </p>
      )}

      {/* Filtros + contagem de abertos da fatia atual */}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <label
          htmlFor={`incident-status-filter-${service.id}`}
          className="text-xs text-(--muted)"
        >
          Status
        </label>
        <select
          id={`incident-status-filter-${service.id}`}
          value={statusFilter}
          onChange={(event) => {
            setStatusFilter(event.target.value as IncidentStatusFilter);
            setPage(0);
            setExpandedId(null);
          }}
          disabled={busy}
          className="rounded-md border border-(--border) bg-(--background) px-2 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
        >
          <option value="all">All</option>
          {INCIDENT_STATUS_OPTIONS.map((option) => (
            <option key={option} value={option}>
              {INCIDENT_STATUS_LABELS[option]}
            </option>
          ))}
        </select>

        <label
          htmlFor={`incident-severity-filter-${service.id}`}
          className="text-xs text-(--muted)"
        >
          Severity
        </label>
        <select
          id={`incident-severity-filter-${service.id}`}
          value={severityFilter}
          onChange={(event) => {
            setSeverityFilter(event.target.value as AlertSeverity | "all");
            setPage(0);
            setExpandedId(null);
          }}
          disabled={busy}
          className="rounded-md border border-(--border) bg-(--background) px-2 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
        >
          <option value="all">All</option>
          {INCIDENT_SEVERITY_OPTIONS.map((severity) => (
            <option key={severity} value={severity}>
              {SEVERITY_LABELS[severity]}
            </option>
          ))}
        </select>

        {view === "list" && (
          <span className="text-xs text-(--muted)">
            {totalElements} {totalElements === 1 ? "incident" : "incidents"} ·{" "}
            {openHere} open on this page
          </span>
        )}
      </div>

      {/* Erro da listagem ≠ lista vazia: caixa própria com Retry */}
      {view === "error" ? (
        <div className="mt-3 rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-3 text-sm">
          <p className="text-(--destructive)">
            {listError ?? "Could not load incidents"}
          </p>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            className="mt-2 rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white"
          >
            Retry
          </button>
        </div>
      ) : view === "empty" ? (
        <p className="mt-3 text-sm text-(--muted)">
          {hasFilter
            ? "No incidents match your filters."
            : canManage
              ? "No incidents yet for this service — create the first one below."
              : "No incidents yet for this service."}
        </p>
      ) : view === "list" ? (
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-(--border) text-left text-(--muted)">
                <th className="py-2 pr-3 font-medium">Severity</th>
                <th className="py-2 pr-3 font-medium">Title</th>
                <th className="py-2 pr-3 font-medium">Status</th>
                <th className="py-2 pr-3 font-medium">Started</th>
                <th className="py-2 pr-3 font-medium">Resolved</th>
                <th className="py-2 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {incidents.map((incident) => {
                const isExpanded = expandedId === incident.id;
                return (
                  <Fragment key={incident.id}>
                    <tr className="border-b border-(--border)/30">
                      <td className="py-2 pr-3">
                        <span
                          className={incidentSeverityBadgeClasses(
                            incident.severity
                          )}
                        >
                          {SEVERITY_LABELS[incident.severity]}
                        </span>
                      </td>
                      <td className="py-2 pr-3 font-medium text-white">
                        {incident.title}
                      </td>
                      <td className="py-2 pr-3">
                        <span className="flex flex-wrap items-center gap-1.5">
                          <span
                            className={incidentStatusBadgeClasses(
                              incident.status
                            )}
                          >
                            {INCIDENT_STATUS_LABELS[incident.status]}
                          </span>
                          {/* Origem (Prompt 15) — palavra + cor, nunca só cor */}
                          <span
                            className={incidentSourceBadgeClasses(
                              incident.source
                            )}
                          >
                            {INCIDENT_SOURCE_LABELS[incident.source]}
                          </span>
                        </span>
                      </td>
                      <td className="py-2 pr-3 text-(--muted)">
                        {formatIncidentInstant(incident.startedAt)}
                      </td>
                      <td className="py-2 pr-3 text-(--muted)">
                        {formatIncidentInstant(incident.resolvedAt)}
                      </td>
                      <td className="py-2 text-right">
                        <button
                          type="button"
                          onClick={() => {
                            setExpandedId((current) =>
                              current === incident.id ? null : incident.id
                            );
                            setNotice(null);
                          }}
                          aria-expanded={isExpanded}
                          disabled={busy}
                          className="rounded-md border border-(--border) px-2 py-1 text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                        >
                          {isExpanded ? "Close" : "Details"}
                        </button>
                      </td>
                    </tr>

                    {/* Detalhe inline = estado + mutações + alertas + timeline */}
                    {isExpanded && (
                      <tr className="border-b border-(--border)/30">
                        <td colSpan={6} className="py-3 pr-3">
                          <IncidentDetail
                            key={incident.id}
                            service={service}
                            incident={incident}
                            canManage={canManage}
                            onIncidentUpdated={onIncidentUpdated}
                            onListChanged={() =>
                              setReload((count) => count + 1)
                            }
                          />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>

          {/* Paginação simples: anterior/próxima + total */}
          <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-sm">
            <span className="text-(--muted)">
              Page {Math.min(page + 1, Math.max(totalPages, 1))} of{" "}
              {Math.max(totalPages, 1)}
            </span>
            <span className="flex gap-2">
              <button
                type="button"
                onClick={() => setPage((current) => Math.max(current - 1, 0))}
                disabled={page === 0 || busy}
                className="rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
              >
                Previous
              </button>
              <button
                type="button"
                onClick={() => setPage((current) => current + 1)}
                disabled={page + 1 >= totalPages || busy}
                className="rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
              >
                Next
              </button>
            </span>
          </div>
        </div>
      ) : (
        <p className="mt-3 text-sm text-(--muted)" role="status">
          Loading incidents…
        </p>
      )}

      {/* Feedback de sucesso/falha de ações sobre a lista */}
      {view !== "error" && notice && (
        <p
          className={`mt-3 text-sm ${
            notice.kind === "error"
              ? "text-(--destructive)"
              : "text-(--accent)"
          }`}
        >
          {notice.text}
        </p>
      )}

      {/* Criar incidente — oculto para VIEWER (controle mutável sem permissão) */}
      {canManage && (
        <form
          onSubmit={(event) => void onCreateIncident(event)}
          className="mt-4 flex flex-col gap-3 border-t border-(--border) pt-4"
        >
          <p className="text-sm font-medium text-white">New incident</p>

          <div className="flex flex-col gap-1">
            <label
              htmlFor={`incident-create-title-${service.id}`}
              className="text-xs text-(--muted)"
            >
              Title
            </label>
            <input
              id={`incident-create-title-${service.id}`}
              value={createForm.title}
              onChange={(event) =>
                setCreateForm((form) => ({
                  ...form,
                  title: event.target.value,
                }))
              }
              placeholder="Checkout returning HTTP 500"
              maxLength={200}
              disabled={busy}
              className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white placeholder:text-(--muted) focus:border-(--accent) focus:outline-none disabled:opacity-60"
            />
          </div>

          <div className="flex flex-wrap gap-3">
            <div className="flex flex-col gap-1">
              <label
                htmlFor={`incident-create-severity-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Severity
              </label>
              <select
                id={`incident-create-severity-${service.id}`}
                value={createForm.severity}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    severity: event.target.value as AlertSeverity,
                  }))
                }
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--background) px-2 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
              >
                {INCIDENT_SEVERITY_OPTIONS.map((severity) => (
                  <option key={severity} value={severity}>
                    {SEVERITY_LABELS[severity]}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label
                htmlFor={`incident-create-started-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Started at
              </label>
              <input
                id={`incident-create-started-${service.id}`}
                type="datetime-local"
                value={createForm.startedAt}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    startedAt: event.target.value,
                  }))
                }
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
              />
              <p className="text-xs text-(--muted)">
                Leave empty to use the current time.
              </p>
            </div>
          </div>

          <div className="flex flex-col gap-1">
            <label
              htmlFor={`incident-create-description-${service.id}`}
              className="text-xs text-(--muted)"
            >
              Description
            </label>
            <textarea
              id={`incident-create-description-${service.id}`}
              value={createForm.description}
              onChange={(event) =>
                setCreateForm((form) => ({
                  ...form,
                  description: event.target.value,
                }))
              }
              rows={3}
              maxLength={4000}
              placeholder="Optional — impact, scope, first signals…"
              disabled={busy}
              className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white placeholder:text-(--muted) focus:border-(--accent) focus:outline-none disabled:opacity-60"
            />
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="submit"
              disabled={busy || createForm.title.trim() === ""}
              className="rounded-md bg-(--accent) px-3 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
            >
              {creating ? "Creating…" : "Create incident"}
            </button>
            {createError && (
              <p className="text-sm text-(--destructive)">{createError}</p>
            )}
          </div>
        </form>
      )}
    </section>
  );
}
