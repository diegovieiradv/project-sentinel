/**
 * Helpers puros de Alert Engine (Prompt 13) — o RESULTADO avaliado: status
 * do alerta, motivo de resolução, snapshot da condição, valor que estourou e
 * a decisão ÚNICA de o que a listagem renderiza.
 *
 * Espelha o contrato de `AlertResponse` do backend. Nada aqui fala rede nem
 * DOM: ambiente `node` do vitest cobre tudo (ver `alerts.test.ts`). A
 * formatação da condição é REUTILIZADA de `alert-rules.ts`
 * (`thresholdSummary` com o shape do snapshot) — uma fonte só para
 * regra/alerta.
 *
 * Fronteira Prompt 13: este módulo só LÊ o resultado — não existe mutação de
 * alerta (sem ack/silence/escalation), nem Incident/notificação aqui.
 */
import { thresholdSummary } from "./alert-rules";
import type {
  AlertResponse,
  AlertResolutionReason,
  AlertSeverity,
  AlertStatus,
} from "./api";

/* -------------------------------------------------------------------------- */
/* Rótulos — sempre TEXTO legível (severidade/status nunca são só cor)        */
/* -------------------------------------------------------------------------- */

/** Rótulo do status do ciclo de vida (sempre com palavra). */
export const ALERT_STATUS_LABELS: Record<AlertStatus, string> = {
  FIRING: "Firing",
  RESOLVED: "Resolved",
};

/**
 * Rótulo do motivo de resolução — a UI nunca mostra o enum cru: `AUTO` é a
 * métrica voltando ao normal, os outros dois são desligamentos de contexto
 * (regra/pai) com o alerta ainda aberto.
 */
export const RESOLUTION_REASON_LABELS: Record<AlertResolutionReason, string> = {
  AUTO: "Auto-resolved",
  RULE_DEACTIVATED: "Rule deactivated",
  PARENT_INACTIVE: "Parent deactivated",
};

/** Motivo legível (`—` enquanto o alerta ainda está FIRING). */
export function resolutionReasonLabel(
  reason: AlertResolutionReason | null
): string {
  if (reason === null) return "—";
  return RESOLUTION_REASON_LABELS[reason] ?? reason;
}

/** Opções dos filtros — espelham os enums do backend. */
export const ALERT_STATUS_OPTIONS: readonly AlertStatus[] = [
  "FIRING",
  "RESOLVED",
];

export const ALERT_SEVERITY_OPTIONS: readonly AlertSeverity[] = [
  "SEV1",
  "SEV2",
  "SEV3",
  "SEV4",
];

/* -------------------------------------------------------------------------- */
/* Snapshot da condição (o que valia NA ÉPOCA do disparo)                     */
/* -------------------------------------------------------------------------- */

/**
 * Condição em uma linha a partir do SNAPSHOT do alerta, ex.:
 * `Availability < 99.9 %` ou `Service status = Degraded` — delega para
 * `thresholdSummary` (fonte única de formatação).
 */
export function alertCondition(
  alert: Pick<
    AlertResponse,
    "ruleType" | "ruleOperator" | "ruleThresholdNumeric" | "ruleThresholdStatus"
  >
): string {
  return thresholdSummary({
    type: alert.ruleType,
    operator: alert.ruleOperator,
    thresholdNumeric: alert.ruleThresholdNumeric,
    thresholdStatus: alert.ruleThresholdStatus,
  });
}

/**
 * Valor que estourou (evidência numérica) com a unidade do tipo, ex.:
 * `97.5 %`, `1240 ms`, `3`. `null` (ex.: `SERVICE_STATUS`) vira `—` — o
 * detalhe fica na `message`.
 */
export function triggerValueLabel(
  alert: Pick<AlertResponse, "ruleType" | "triggerValue">
): string {
  if (alert.triggerValue === null || alert.triggerValue === undefined) {
    return "—";
  }
  switch (alert.ruleType) {
    case "AVAILABILITY":
    case "SUCCESS_RATE":
      return `${alert.triggerValue} %`;
    case "AVERAGE_LATENCY":
      return `${alert.triggerValue} ms`;
    case "TARGET_FAILURE_COUNT":
      return String(alert.triggerValue);
    case "SERVICE_STATUS":
      return String(alert.triggerValue);
  }
}

/**
 * Instante legível em UTC determinístico (sem depender de timezone/locale do
 * ambiente — o teste node e o browser veem o mesmo texto), ex.:
 * `2026-03-01 12:34:56 UTC`. Valor ilegível → `—`.
 */
export function formatAlertInstant(iso: string | null): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return `${date.toISOString().replace("T", " ").slice(0, 19)} UTC`;
}

/** O alerta ainda está aberto? (mensagem original nunca muda por causa disso). */
export function isFiring(alert: Pick<AlertResponse, "status">): boolean {
  return alert.status === "FIRING";
}

/* -------------------------------------------------------------------------- */
/* Estado da view da listagem (decisão ÚNICA, sem DOM)                        */
/* -------------------------------------------------------------------------- */

/** Status de carregamento da lista no componente (`idle` antes do 1º fetch). */
export type AlertsListStatus = "idle" | "loading" | "ready" | "error";

/** O que a listagem renderiza — decisão ÚNICA derivada de status + dados. */
export type AlertsViewState = "loading" | "error" | "empty" | "list";

/**
 * Estado da view da listagem — função pura consumida pelo `AlertsPanel`
 * (mesmo arranjo de `alertRulesViewState`): `error` tem precedência sobre
 * `empty` — falha de carregar nunca se disfarça de "sem alertas" (a lista
 * vazia é uma informação operacional: nada disparou).
 */
export function alertsViewState(
  status: AlertsListStatus,
  alertCount: number
): AlertsViewState {
  if (status === "error") return "error";
  if (status !== "ready") return "loading";
  return alertCount === 0 ? "empty" : "list";
}

/* -------------------------------------------------------------------------- */
/* Badge de severidade (texto + cor, nunca só cor)                            */
/* -------------------------------------------------------------------------- */

/** Classes do badge por severidade — a PALAVRA vem junto (via `SEVERITY_LABELS`). */
export function severityBadgeClasses(severity: AlertSeverity): string {
  switch (severity) {
    case "SEV1":
      return "rounded bg-(--destructive) px-1.5 py-0.5 text-xs font-semibold text-white";
    case "SEV2":
      return "rounded bg-(--accent) px-1.5 py-0.5 text-xs font-semibold text-white";
    case "SEV3":
      return "rounded border border-(--border) bg-(--background) px-1.5 py-0.5 text-xs font-semibold text-(--muted)";
    case "SEV4":
      return "rounded border border-(--border) px-1.5 py-0.5 text-xs font-semibold text-(--muted)";
  }
}

