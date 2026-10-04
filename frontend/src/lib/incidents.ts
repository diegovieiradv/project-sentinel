/**
 * Helpers puros de Incidentes (Prompt 14) — o ciclo de vida manual, o
 * vocabulário da timeline append-only e a decisão ÚNICA do que a UI renderiza.
 *
 * Espelha o contrato de `IncidentResponse`/`IncidentEventResponse` do backend.
 * Nada aqui fala rede nem DOM: ambiente `node` do vitest cobre tudo (ver
 * `incidents.test.ts`). A formatação de severidade é REUTILIZADA de
 * `alert-rules.ts` (`SEVERITY_LABELS`) — uma fonte só para severidade, seja
 * ela de alerta ou de incidente.
 *
 * Fronteira Prompt 14: o STATUS só muda pela rota `/status` (aqui o módulo
 * apenas ESPELHA a máquina de estados do server para habilitar/desabilitar
 * botões — a autoridade continua no backend, que responde
 * `409 INCIDENT_STATUS_TRANSITION`).
 */
import { formatAlertInstant } from "./alerts";
import { SEVERITY_LABELS } from "./alert-rules";
import type {
  AlertSeverity,
  IncidentEventResponse,
  IncidentEventType,
  IncidentResponse,
  IncidentSource,
  IncidentStatus,
  OrganizationRole,
} from "./api";

/* -------------------------------------------------------------------------- */
/* Rótulos — sempre TEXTO legível (severidade/status nunca são só cor)        */
/* -------------------------------------------------------------------------- */

/** Rótulo do ciclo de vida (sempre com palavra). */
export const INCIDENT_STATUS_LABELS: Record<IncidentStatus, string> = {
  OPEN: "Open",
  INVESTIGATING: "Investigating",
  IDENTIFIED: "Identified",
  RESOLVED: "Resolved",
};

/** Opções dos filtros e do seletor de transição — espelham o enum do server. */
export const INCIDENT_STATUS_OPTIONS: readonly IncidentStatus[] = [
  "OPEN",
  "INVESTIGATING",
  "IDENTIFIED",
  "RESOLVED",
];

/** Mesmas severidades do alerta — a UI nunca mostra o enum cru. */
export const INCIDENT_SEVERITY_OPTIONS: readonly AlertSeverity[] = [
  "SEV1",
  "SEV2",
  "SEV3",
  "SEV4",
];

/** Reexporta os rótulos de severidade (fonte única em `alert-rules.ts`). */
export { SEVERITY_LABELS as INCIDENT_SEVERITY_LABELS };

/** Rótulo do tipo de evento da timeline (o `message` é a frase do server). */
export const INCIDENT_EVENT_TYPE_LABELS: Record<IncidentEventType, string> = {
  CREATED: "Incident created",
  STATUS_CHANGED: "Status changed",
  SEVERITY_CHANGED: "Severity changed",
  TITLE_CHANGED: "Title changed",
  DESCRIPTION_CHANGED: "Description changed",
  ALERT_LINKED: "Alert linked",
  ALERT_UNLINKED: "Alert unlinked",
};

/**
 * Rótulo da origem (Prompt 15) — sempre com palavra: `MANUAL` nasceu por
 * humano, `ALERT_AUTOMATION` nasceu sozinho quando a regra disparou. A UI
 * nunca mostra o enum cru nem diferencia origem só por cor.
 */
export const INCIDENT_SOURCE_LABELS: Record<IncidentSource, string> = {
  MANUAL: "Manual",
  ALERT_AUTOMATION: "Auto-created",
};

/* -------------------------------------------------------------------------- */
/* Máquina de estados — espelho fiel do `IncidentStatus.canTransitionTo`       */
/* -------------------------------------------------------------------------- */

/**
 * Transições admitidas por estado. `RESOLVED` é terminal: nada sai dele (a UI
 * não oferece nenhuma opção e o server responderia 409).
 */
export const INCIDENT_TRANSITIONS: Record<
  IncidentStatus,
  readonly IncidentStatus[]
> = {
  OPEN: ["INVESTIGATING", "IDENTIFIED", "RESOLVED"],
  INVESTIGATING: ["IDENTIFIED", "RESOLVED"],
  IDENTIFIED: ["RESOLVED"],
  RESOLVED: [],
};

/**
 * A transição seria aceita pelo server? Mesma autoridade do domínio — serve
 * para desabilitar a opção na UI, nunca para substituir a checagem (um 409
 * vira mensagem na UI, igual ao resto do projeto).
 */
export function canTransition(
  from: IncidentStatus,
  to: IncidentStatus
): boolean {
  return INCIDENT_TRANSITIONS[from].includes(to);
}

/**
 * Destinos que o SELETOR de status deve oferecer para um estado — a lista
 * inteira na ordem canônica, menos o destino inválido. Um incidente
 * `RESOLVED` devolve a lista vazia (o seletor some).
 */
export function nextStatusOptions(status: IncidentStatus): IncidentStatus[] {
  return INCIDENT_STATUS_OPTIONS.filter((option) => canTransition(status, option));
}

/** Incidente já resolvido? (conteúdo e timeline continuam legíveis). */
export function isResolved(incident: Pick<IncidentResponse, "status">): boolean {
  return incident.status === "RESOLVED";
}

/**
 * RBAC de UX — VIEWER não recebe controles mutáveis (criar/editar/status/
 * vincular), do mesmo jeito que `canManageAlertRules`. O backend continua
 * sendo a autoridade: um 403 vira mensagem na UI, jamais logout.
 */
export function canManageIncidents(role: OrganizationRole): boolean {
  return role !== "VIEWER";
}

/* -------------------------------------------------------------------------- */
/* Timeline — log append-only (sem escrita na UI)                              */
/* -------------------------------------------------------------------------- */

/**
 * Instante legível em UTC determinístico — delega para
 * `formatAlertInstant` (mesma fonte de formatação de instante do projeto,
 * ex.: `2026-03-01 12:34:56 UTC`). Valor ilegível → `—`.
 */
export function formatIncidentInstant(iso: string | null): string {
  return formatAlertInstant(iso);
}

/**
 * Uma linha da timeline no formato fixo da UI:
 * `12:34:56 UTC · Status changed · Status changed from OPEN to INVESTIGATING`.
 * O `message` do server é sempre a parte informativa; o tipo entra como
 * rótulo para o leitor saber a natureza do fato sem decifrar a frase.
 */
export function incidentEventLine(
  event: Pick<IncidentEventResponse, "type" | "message" | "createdAt">
): string {
  const at = formatIncidentInstant(event.createdAt);
  const kind = INCIDENT_EVENT_TYPE_LABELS[event.type] ?? event.type;
  return `${at} · ${kind} · ${event.message}`;
}

/** Quem fez a ação, em texto curto — `null` (sem autor) vira `—`. */
export function incidentActorLabel(
  event: Pick<IncidentEventResponse, "actorUserId">
): string {
  return event.actorUserId ?? "—";
}

/**
 * Autor do INCIDENTE, em texto curto — `null` (automático: não existe humano
 * por trás) vira `—`, mesmo critério de `incidentActorLabel`.
 */
export function incidentAuthorLabel(
  incident: Pick<IncidentResponse, "createdByUserId">
): string {
  return incident.createdByUserId ?? "—";
}

/* -------------------------------------------------------------------------- */
/* Estado da view da listagem (decisão ÚNICA, sem DOM)                         */
/* -------------------------------------------------------------------------- */

/** Status de carregamento da lista no componente (`idle` antes do 1º fetch). */
export type IncidentsListStatus = "idle" | "loading" | "ready" | "error";

/** O que a listagem renderiza — decisão ÚNICA derivada de status + dados. */
export type IncidentsViewState = "loading" | "error" | "empty" | "list";

/**
 * Estado da view da listagem — mesmo arranjo de `alertsViewState`: `error`
 * tem precedência sobre `empty` (falha de carregar nunca se disfarça de
 * "sem incidentes"; a lista vazia é informação operacional).
 */
export function incidentsViewState(
  status: IncidentsListStatus,
  incidentCount: number
): IncidentsViewState {
  if (status === "error") return "error";
  if (status !== "ready") return "loading";
  return incidentCount === 0 ? "empty" : "list";
}

/* -------------------------------------------------------------------------- */
/* Derivados da lista (cabeçalho, filtro "só abertos", edição)                */
/* -------------------------------------------------------------------------- */

/** Quantos incidentes da fatia atual ainda NÃO estão resolvidos. */
export function openIncidentCount(
  incidents: readonly Pick<IncidentResponse, "status">[]
): number {
  return incidents.filter((incident) => !isResolved(incident)).length;
}

/**
 * O rascunho de edição difere do incidente? Decide se o botão Save fica
 * habilitado — `description` nulo e `""` são tratados como "sem descrição"
 * porque é assim que o server normaliza (`""` limpa, vira `null`).
 */
export function hasIncidentContentChanges(
  incident: Pick<IncidentResponse, "title" | "description" | "severity">,
  draft: { title: string; description: string; severity: AlertSeverity }
): boolean {
  const draftDescription = draft.description === "" ? null : draft.description;
  return (
    draft.title !== incident.title ||
    draftDescription !== incident.description ||
    draft.severity !== incident.severity
  );
}

/* -------------------------------------------------------------------------- */
/* Badges (texto + cor, nunca só cor)                                          */
/* -------------------------------------------------------------------------- */

/** Classes do badge por status — a PALAVRA vem junto (via `INCIDENT_STATUS_LABELS`). */
export function incidentStatusBadgeClasses(status: IncidentStatus): string {
  switch (status) {
    case "OPEN":
      return "rounded bg-(--destructive) px-1.5 py-0.5 text-xs font-semibold text-white";
    case "INVESTIGATING":
      return "rounded bg-(--accent) px-1.5 py-0.5 text-xs font-semibold text-white";
    case "IDENTIFIED":
      return "rounded border border-(--border) bg-(--background) px-1.5 py-0.5 text-xs font-semibold text-(--muted)";
    case "RESOLVED":
      return "rounded border border-(--border) px-1.5 py-0.5 text-xs font-semibold text-(--muted)";
  }
}

/** Classes do badge por severidade — reusa a mesma decisão do alerta. */
export { severityBadgeClasses as incidentSeverityBadgeClasses } from "./alerts";

/**
 * Classes do badge por ORIGEM (Prompt 15) — a palavra vem junto via
 * `INCIDENT_SOURCE_LABELS` (a cor nunca é o único sinal). `MANUAL` fica
 * discreto (borda, como um estado encerrado) e `ALERT_AUTOMATION` ganha o
 * destaque do accent, para o leitor notar de imediato que ninguém criou
 * aquele incidente.
 */
export function incidentSourceBadgeClasses(source: IncidentSource): string {
  switch (source) {
    case "MANUAL":
      return "rounded border border-(--border) bg-(--background) px-1.5 py-0.5 text-xs font-semibold text-(--muted)";
    case "ALERT_AUTOMATION":
      return "rounded bg-(--accent) px-1.5 py-0.5 text-xs font-semibold text-white";
  }
}
