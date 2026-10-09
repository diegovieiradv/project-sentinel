/* Sentinel Prompt 14 — Incident: detalhe inline (conteúdo, status, alertas, timeline) */
"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  ApiError,
  changeIncidentStatus,
  listAlerts,
  listIncidentAlerts,
  listIncidentEvents,
  linkIncidentAlert,
  unlinkIncidentAlert,
  updateIncident,
  type AlertResponse,
  type AlertSeverity,
  type IncidentEventResponse,
  type IncidentResponse,
  type IncidentStatus,
  type ServiceResponse,
} from "@/lib/api";
import {
  INCIDENT_SEVERITY_OPTIONS,
  INCIDENT_SOURCE_LABELS,
  INCIDENT_STATUS_LABELS,
  canTransition,
  formatIncidentInstant,
  hasIncidentContentChanges,
  incidentActorLabel,
  incidentAuthorLabel,
  incidentEventLine,
  incidentSeverityBadgeClasses,
  incidentSourceBadgeClasses,
  incidentStatusBadgeClasses,
  isResolved,
  nextStatusOptions,
  type IncidentsListStatus,
} from "@/lib/incidents";
import { SEVERITY_LABELS } from "@/lib/alert-rules";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";

/** Tamanho da página enviada ao server (contrato: default 20, máx 100). */
const DETAIL_PAGE_SIZE = 50;

type DetailNotice = { kind: "success" | "error"; text: string } | null;

/** Rascunho de edição dos três campos de conteúdo do incidente. */
type ContentDraft = {
  title: string;
  description: string;
  severity: AlertSeverity;
};

function draftFrom(incident: IncidentResponse): ContentDraft {
  return {
    title: incident.title,
    description: incident.description ?? "",
    severity: incident.severity,
  };
}

/**
 * Mensagem legível de uma falha de mutação de incidente.
 * - **403** → papel insuficiente (feedback; NUNCA logout — só 401 derruba a sessão).
 * - **409** `INCIDENT_STATUS_TRANSITION` → a máquina de estados recusou o destino.
 * - **409** `INCIDENT_RESOLVED` → o incidente já está resolvido (terminal).
 * - **409** `PROJECT_INACTIVE`/`SERVICE_INACTIVE` → pai inativo: leitura ok, mutação não.
 * - **409** `ALERT_ALREADY_LINKED` → o alerta pertence a outro incidente.
 * - **409** `OptimisticLockingFailure` (sem `code`) → outra pessoa salvou antes.
 * - Demais (**400** `VALIDATION_ERROR`, …) → a mensagem do servidor.
 */
function incidentActionError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You don't have permission to manage incidents in this organization.";
    }
    if (err.code === "INCIDENT_STATUS_TRANSITION") {
      return err.message;
    }
    if (err.code === "INCIDENT_RESOLVED") {
      return "This incident is resolved and can no longer be modified.";
    }
    if (err.code === "SERVICE_INACTIVE") {
      return "This service is inactive; incidents can be viewed but not modified.";
    }
    if (err.code === "PROJECT_INACTIVE") {
      return "This project is inactive; incidents can be viewed but not modified.";
    }
    if (err.code === "ALERT_ALREADY_LINKED") {
      return "That alert is already linked to another incident.";
    }
    return err.message;
  }
  return fallback;
}

/**
 * Detalhe inline de UM incidente (Prompt 14) — montado pela linha expandida do
 * `IncidentsPanel` com `key={incident.id}`.
 *
 * Quatro blocos, nesta ordem: **estado** (badges + fatos), **mutações**
 * (transição de status e conteúdo), **alertas vinculados** (link/unlink) e a
 * **timeline append-only** (leitura pura — a UI não escreve aqui).
 *
 * RBAC aqui é só UX: VIEWER não recebe controles, e um incidente `RESOLVED`
 * é terminal — a UI some com toda mutação, espelhando o `409` que o backend
 * devolveria. A autoridade continua no backend.
 */
export default function IncidentDetail({
  service,
  incident,
  canManage,
  onIncidentUpdated,
  onListChanged,
}: {
  service: ServiceResponse;
  incident: IncidentResponse;
  /** `canManageIncidents(service.role)` — VIEWER nunca vê controles. */
  canManage: boolean;
  /** Substitui a linha da lista após um PATCH bem-sucedido. */
  onIncidentUpdated: (next: IncidentResponse) => void;
  /** Recarrega a listagem (ex.: mudou filtro de status da lista). */
  onListChanged: () => void;
}) {
  const orgId = service.organizationId;
  const projectId = service.projectId;
  const serviceId = service.id;
  const incidentId = incident.id;

  /** Mutação bloqueada por contrato: resolvido é terminal. */
  const resolved = isResolved(incident);

  /* ---------------------------------------------------------------------- */
  /* Timeline — sempre carregada (leitura livre, mesmo resolvido)            */
  /* ---------------------------------------------------------------------- */

  const [events, setEvents] = useState<IncidentEventResponse[]>([]);
  const [eventsStatus, setEventsStatus] = useState<IncidentsListStatus>("idle");
  const [eventsError, setEventsError] = useState<string | null>(null);
  /** Incrementado a cada mutação que gera evento — recarrega a timeline. */
  const [eventsReload, setEventsReload] = useState(0);

  useEffect(() => {
    let cancelled = false;

    async function loadEvents(): Promise<void> {
      setEventsStatus("loading");
      setEventsError(null);
      try {
        const data = await listIncidentEvents(
          orgId,
          projectId,
          serviceId,
          incidentId
        );
        if (cancelled) return;
        setEvents(data);
        setEventsStatus("ready");
      } catch (err) {
        if (cancelled) return;
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        setEventsStatus("error");
        setEventsError(messageOf(err, "Could not load the incident timeline"));
      }
    }

    void loadEvents();
    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, serviceId, incidentId, eventsReload]);

  /* ---------------------------------------------------------------------- */
  /* Alertas: os vinculados a ESTE incidente + os do serviço (para vincular) */
  /* ---------------------------------------------------------------------- */

  const [linkedAlerts, setLinkedAlerts] = useState<AlertResponse[]>([]);
  const [serviceAlerts, setServiceAlerts] = useState<AlertResponse[]>([]);
  const [alertsStatus, setAlertsStatus] = useState<IncidentsListStatus>("idle");
  const [alertsError, setAlertsError] = useState<string | null>(null);
  /** Incrementado após link/unlink — recarrega os dois lados do vínculo. */
  const [alertsReload, setAlertsReload] = useState(0);

  useEffect(() => {
    let cancelled = false;

    async function loadAlerts(): Promise<void> {
      setAlertsStatus("loading");
      setAlertsError(null);
      try {
        const [linked, all] = await Promise.all([
          listIncidentAlerts(orgId, projectId, serviceId, incidentId, {
            page: 0,
            size: DETAIL_PAGE_SIZE,
          }),
          listAlerts(orgId, projectId, serviceId, {
            page: 0,
            size: DETAIL_PAGE_SIZE,
          }),
        ]);
        if (cancelled) return;
        setLinkedAlerts(linked.content);
        setServiceAlerts(all.content);
        setAlertsStatus("ready");
      } catch (err) {
        if (cancelled) return;
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        setAlertsStatus("error");
        setAlertsError(messageOf(err, "Could not load the incident alerts"));
      }
    }

    void loadAlerts();
    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, serviceId, incidentId, alertsReload]);

  /** Alertas do serviço ainda NÃO vinculados a este incidente. */
  const linkableAlerts = useMemo(() => {
    const linkedIds = new Set(linkedAlerts.map((alert) => alert.id));
    return serviceAlerts.filter((alert) => !linkedIds.has(alert.id));
  }, [linkedAlerts, serviceAlerts]);

  /* ---------------------------------------------------------------------- */
  /* Mutação: conteúdo                                                      */
  /* ---------------------------------------------------------------------- */

  // O painel monta `<IncidentDetail key={incident.id}>`, então trocar de linha
  // REMONTA o detalhe e o rascunho nasce semeado com o incidente certo — sem
  // efeito de re-semeia (evita render em cascata).
  const [draft, setDraft] = useState<ContentDraft>(() => draftFrom(incident));
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  async function onSaveContent(
    event: FormEvent<HTMLFormElement>
  ): Promise<void> {
    event.preventDefault();
    if (!canManage || resolved || saving) return;
    if (!hasIncidentContentChanges(incident, draft)) return;

    setSaving(true);
    setSaveError(null);
    try {
      const next = await updateIncident(orgId, projectId, serviceId, incidentId, {
        title: draft.title,
        description: draft.description,
        severity: draft.severity,
      });
      onIncidentUpdated(next);
      // Eventos novos (title/description/severity changed) → recarrega a timeline.
      setEventsReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setSaveError(incidentActionError(err, "Could not save the incident."));
    } finally {
      setSaving(false);
    }
  }

  /* ---------------------------------------------------------------------- */
  /* Mutação: status (rota /status — o único caminho legal)                  */
  /* ---------------------------------------------------------------------- */

  const [statusTarget, setStatusTarget] = useState<IncidentStatus | "">("");
  const [changing, setChanging] = useState(false);
  const [statusError, setStatusError] = useState<string | null>(null);

  const statusOptions = nextStatusOptions(incident.status);
  /** Guarda extra: a UI só envia um destino que a máquina de estados aceita. */
  const statusValid =
    statusTarget !== "" && canTransition(incident.status, statusTarget);

  async function onChangeStatus(
    event: FormEvent<HTMLFormElement>
  ): Promise<void> {
    event.preventDefault();
    if (!canManage || resolved || changing || !statusValid) return;

    setChanging(true);
    setStatusError(null);
    try {
      const next = await changeIncidentStatus(
        orgId,
        projectId,
        serviceId,
        incidentId,
        statusTarget
      );
      // Limpa o seletor depois do sucesso (o destino já foi consumido).
      setStatusTarget("");
      onIncidentUpdated(next);
      onListChanged();
      setEventsReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setStatusError(
        incidentActionError(err, "Could not change the incident status.")
      );
    } finally {
      setChanging(false);
    }
  }

  /* ---------------------------------------------------------------------- */
  /* Mutação: vincular / desvincular alerta                                 */
  /* ---------------------------------------------------------------------- */

  const [linkTarget, setLinkTarget] = useState<string>("");
  const [linking, setLinking] = useState(false);
  const [linkError, setLinkError] = useState<string | null>(null);
  const [unlinkingId, setUnlinkingId] = useState<string | null>(null);

  const [notice, setNotice] = useState<DetailNotice>(null);
  const busy = saving || changing || linking || unlinkingId !== null;

  async function onLinkAlert(): Promise<void> {
    if (!canManage || resolved || busy || linkTarget === "") return;

    setLinking(true);
    setLinkError(null);
    setNotice(null);
    try {
      await linkIncidentAlert(
        orgId,
        projectId,
        serviceId,
        incidentId,
        linkTarget
      );
      setLinkTarget("");
      setAlertsReload((count) => count + 1);
      setEventsReload((count) => count + 1);
      setNotice({ kind: "success", text: "Alert linked." });
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setLinkError(
        incidentActionError(err, "Could not link the alert to this incident.")
      );
    } finally {
      setLinking(false);
    }
  }

  async function onUnlinkAlert(alertId: string): Promise<void> {
    if (!canManage || resolved || busy) return;

    setUnlinkingId(alertId);
    setLinkError(null);
    setNotice(null);
    try {
      await unlinkIncidentAlert(
        orgId,
        projectId,
        serviceId,
        incidentId,
        alertId
      );
      // 204 idempotente: um segundo DELETE não altera nada e NÃO gera evento.
      setAlertsReload((count) => count + 1);
      setEventsReload((count) => count + 1);
      setNotice({ kind: "success", text: "Alert unlinked." });
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setNotice({
        kind: "error",
        text: incidentActionError(err, "Could not unlink the alert."),
      });
    } finally {
      setUnlinkingId(null);
    }
  }

  const canEditContent = canManage && !resolved;
  const dirty = hasIncidentContentChanges(incident, draft);

  return (
    <div className="flex flex-col gap-4 rounded-md border border-(--border) bg-(--background) p-3">
      {/* ---------------------------------------------------------------- */}
      {/* Estado — badges + fatos imutáveis                                 */}
      {/* ---------------------------------------------------------------- */}
      <div className="flex flex-col gap-2">
        <p className="flex flex-wrap items-center gap-2 text-xs text-(--muted)">
          <span className={incidentStatusBadgeClasses(incident.status)}>
            {INCIDENT_STATUS_LABELS[incident.status]}
          </span>
          <span className={incidentSeverityBadgeClasses(incident.severity)}>
            {SEVERITY_LABELS[incident.severity]}
          </span>
          {/* Origem (Prompt 15) — "humano ou flag da regra" */}
          <span className={incidentSourceBadgeClasses(incident.source)}>
            {INCIDENT_SOURCE_LABELS[incident.source]}
          </span>
          <span>· Your role {incident.role}</span>
          <span>· v{incident.version}</span>
        </p>
        <p className="text-sm text-white">
          {incident.title}
          <span className="ml-2 text-xs text-(--muted)">
            Started {formatIncidentInstant(incident.startedAt)} · Resolved{" "}
            {formatIncidentInstant(incident.resolvedAt)}
          </span>
        </p>
        <p className="text-sm text-(--muted)">
          {incident.description ?? "No description."}
        </p>
        <p className="text-xs text-(--muted)">
          Created {formatIncidentInstant(incident.createdAt)} · Updated{" "}
          {formatIncidentInstant(incident.updatedAt)} · Reported by{" "}
          {incidentAuthorLabel(incident)}
        </p>
      </div>

      {/* ---------------------------------------------------------------- */}
      {/* Mutações — omitidas para VIEWER e para RESOLVED (terminal)        */}
      {/* ---------------------------------------------------------------- */}
      {canManage && !service.active && (
        <p className="rounded-md border border-(--border) px-3 py-2 text-sm text-(--muted)">
          <span className="font-medium text-white">This service is inactive;</span>{" "}
          incidents can be viewed but not modified.
        </p>
      )}

      {canManage && !resolved && statusOptions.length > 0 && (
        <form
          onSubmit={(event) => void onChangeStatus(event)}
          className="flex flex-wrap items-end gap-2 border-t border-(--border) pt-3"
        >
          <div className="flex flex-col gap-1">
            <label
              htmlFor={`incident-status-${incident.id}`}
              className="text-xs text-(--muted)"
            >
              Move to
            </label>
            <select
              id={`incident-status-${incident.id}`}
              value={statusTarget}
              onChange={(event) =>
                setStatusTarget(event.target.value as IncidentStatus | "")
              }
              disabled={busy}
              className="rounded-md border border-(--border) bg-(--card) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
            >
              <option value="">Select status…</option>
              {statusOptions.map((option) => (
                <option key={option} value={option}>
                  {INCIDENT_STATUS_LABELS[option]}
                </option>
              ))}
            </select>
          </div>
          <button
            type="submit"
            disabled={busy || !statusValid}
            className="rounded-md bg-(--accent) px-4 py-3 min-h-[44px] text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
          >
            {changing ? "Saving…" : "Change status"}
          </button>
          {statusError && (
            <p className="text-sm text-(--destructive)">{statusError}</p>
          )}
        </form>
      )}

      {canManage && resolved && (
        <p className="rounded-md border border-(--border) px-3 py-2 text-xs text-(--muted)">
          This incident is resolved — status and content are frozen. The
          timeline below stays readable.
        </p>
      )}

      {canEditContent && (
        <form
          onSubmit={(event) => void onSaveContent(event)}
          className="flex flex-col gap-3 border-t border-(--border) pt-3"
        >
          <p className="text-xs font-medium text-white">Edit content</p>
          <div className="flex flex-col gap-1">
            <label
              htmlFor={`incident-title-${incident.id}`}
              className="text-xs text-(--muted)"
            >
              Title
            </label>
            <input
              id={`incident-title-${incident.id}`}
              value={draft.title}
              onChange={(event) =>
                setDraft((form) => ({ ...form, title: event.target.value }))
              }
              disabled={busy}
              className="rounded-md border border-(--border) bg-(--card) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
            />
          </div>
          <div className="flex flex-col gap-1">
            <label
              htmlFor={`incident-description-${incident.id}`}
              className="text-xs text-(--muted)"
            >
              Description
            </label>
            <textarea
              id={`incident-description-${incident.id}`}
              value={draft.description}
              onChange={(event) =>
                setDraft((form) => ({
                  ...form,
                  description: event.target.value,
                }))
              }
              rows={3}
              disabled={busy}
              className="rounded-md border border-(--border) bg-(--card) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
            />
            <p className="text-xs text-(--muted)">
              Clearing the field removes the description.
            </p>
          </div>
          <div className="flex flex-col gap-1">
            <label
              htmlFor={`incident-severity-${incident.id}`}
              className="text-xs text-(--muted)"
            >
              Severity
            </label>
            <select
              id={`incident-severity-${incident.id}`}
              value={draft.severity}
              onChange={(event) =>
                setDraft((form) => ({
                  ...form,
                  severity: event.target.value as AlertSeverity,
                }))
              }
              disabled={busy}
              className="rounded-md border border-(--border) bg-(--card) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
            >
              {INCIDENT_SEVERITY_OPTIONS.map((severity) => (
                <option key={severity} value={severity}>
                  {SEVERITY_LABELS[severity]}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="submit"
              disabled={busy || !dirty || draft.title.trim() === ""}
              className="rounded-md bg-(--accent) px-4 py-3 min-h-[44px] text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
            >
              {saving ? "Saving…" : "Save changes"}
            </button>
            {saveError && (
              <p className="text-sm text-(--destructive)">{saveError}</p>
            )}
          </div>
        </form>
      )}

      {/* ---------------------------------------------------------------- */}
      {/* Alertas vinculados + vínculo novo                                 */}
      {/* ---------------------------------------------------------------- */}
      <div className="flex flex-col gap-2 border-t border-(--border) pt-3">
        <p className="text-xs font-medium text-white">Linked alerts</p>

        {alertsStatus === "error" && (
          <p className="text-sm text-(--destructive)" role="alert">
            {alertsError ?? "Could not load the incident alerts"}
          </p>
        )}

        {alertsStatus !== "error" && linkedAlerts.length === 0 && (
          <p className="text-xs text-(--muted)">
            No alert is linked to this incident yet.
          </p>
        )}

        {linkedAlerts.length > 0 && (
          <ul className="flex flex-col gap-1">
            {linkedAlerts.map((alert) => (
              <li
                key={alert.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-(--border) px-2 py-1.5 text-xs"
              >
                <span className="min-w-0 text-white">
                  <span className={incidentSeverityBadgeClasses(alert.severity)}>
                    {SEVERITY_LABELS[alert.severity]}
                  </span>{" "}
                  <span className="ml-1 text-(--muted)">
                    {formatIncidentInstant(alert.startedAt)}
                  </span>{" "}
                  {alert.message}
                </span>
                {canEditContent && (
                  <button
                    type="button"
                    onClick={() => void onUnlinkAlert(alert.id)}
                    disabled={busy}
                    className="rounded-md border border-(--border) px-3 py-2.5 min-h-[44px] text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                  >
                    {unlinkingId === alert.id ? "Removing…" : "Unlink"}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}

        {canEditContent && (
          <div className="flex flex-wrap items-end gap-2">
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <label
                htmlFor={`incident-link-${incident.id}`}
                className="text-xs text-(--muted)"
              >
                Link an alert of this service
              </label>
              <select
                id={`incident-link-${incident.id}`}
                value={linkTarget}
                onChange={(event) => setLinkTarget(event.target.value)}
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--card) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
              >
                <option value="">
                  {linkableAlerts.length === 0
                    ? "No alert available to link"
                    : "Select an alert…"}
                </option>
                {linkableAlerts.map((alert) => (
                  <option key={alert.id} value={alert.id}>
                    {SEVERITY_LABELS[alert.severity]} ·{" "}
                    {formatIncidentInstant(alert.startedAt)} · {alert.message}
                  </option>
                ))}
              </select>
            </div>
            <button
              type="button"
              onClick={() => void onLinkAlert()}
              disabled={busy || linkTarget === ""}
              className="rounded-md bg-(--accent) px-4 py-3 min-h-[44px] text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
            >
              {linking ? "Linking…" : "Link alert"}
            </button>
            {linkError && (
              <p className="text-sm text-(--destructive)">{linkError}</p>
            )}
          </div>
        )}
      </div>

      {/* ---------------------------------------------------------------- */}
      {/* Timeline — append-only, leitura pura (a UI nunca escreve)          */}
      {/* ---------------------------------------------------------------- */}
      <div className="flex flex-col gap-2 border-t border-(--border) pt-3">
        <p className="text-xs font-medium text-white">Timeline</p>

        {eventsStatus === "loading" && (
          <p className="text-xs text-(--muted)" role="status">
            Loading timeline…
          </p>
        )}

        {eventsStatus === "error" && (
          <p className="text-sm text-(--destructive)" role="alert">
            {eventsError ?? "Could not load the incident timeline"}
          </p>
        )}

        {eventsStatus === "ready" && events.length === 0 && (
          <p className="text-xs text-(--muted)">
            No events recorded for this incident.
          </p>
        )}

        {events.length > 0 && (
          <ol className="flex flex-col gap-1">
            {events.map((item) => (
              <li
                key={item.id}
                className="rounded-md border border-(--border) px-2 py-1.5 text-xs text-(--muted)"
              >
                <span className="text-white">{incidentEventLine(item)}</span>
                <span className="ml-1">· by {incidentActorLabel(item)}</span>
              </li>
            ))}
          </ol>
        )}
      </div>

      {notice && (
        <p
          className={`text-sm ${
            notice.kind === "error"
              ? "text-(--destructive)"
              : "text-(--accent)"
          }`}
        >
          {notice.text}
        </p>
      )}
    </div>
  );
}
