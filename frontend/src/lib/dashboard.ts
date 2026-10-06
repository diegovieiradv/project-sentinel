/**
 * Helpers puros do painel operacional (Prompt 16) — rótulos, formato e as
 * decisões de renderização do read model composto.
 *
 * Espelha o contrato de `DashboardResponse` de `src/lib/api.ts` (que por sua
 * vez espelha os records de `com.sentinel.project.dashboard.dto`). Nada aqui
 * fala rede nem DOM: ambiente `node` do vitest cobre tudo (ver
 * `dashboard.test.ts`).
 *
 * Regras herdadas dos Prompts 11 e 16 que ESTE módulo garante na UI:
 *
 * - percentual/latência `null` (denominador zero — sem evidência) vira
 *   `"—"`, NUNCA `0`: zero aqui afirmaria indisponibilidade onde não há
 *   checagem;
 * - as taxas chegam prontas do backend (agregação global, HALF_UP, 2
 *   casas) — nada aqui recalcula disponibilidade;
 * - severidade, status de incidente e status de serviço sempre como
 *   PALAVRA legível: a UI nunca mostra o enum cru.
 *
 * Fontes já canônicas são REUTILIZADAS em vez de duplicadas:
 * `SEVERITY_LABELS` (`alert-rules.ts`), `INCIDENT_STATUS_LABELS`
 * (`incidents.ts`) e a formatação UTC/`null` de `metrics.ts`.
 */
import { SEVERITY_LABELS } from "./alert-rules";
import { INCIDENT_STATUS_LABELS } from "./incidents";
import type {
  AlertSeverity,
  DashboardActivityType,
  DashboardRange,
  DashboardResponse,
  IncidentStatus,
  ServiceStatus,
} from "./api";
import {
  METRICS_RANGE_OPTIONS,
  formatAvgMillis,
  formatBucketLabel,
  formatPercent,
} from "./metrics";

/* -------------------------------------------------------------------------- */
/* Rótulos — sempre TEXTO legível (enum nunca aparece cru na tela)            */
/* -------------------------------------------------------------------------- */

/** Linhas da atividade recente (espelha o enum `ActivityType` do backend). */
export const DASHBOARD_ACTIVITY_LABELS: Record<DashboardActivityType, string> =
  {
    ALERT_FIRED: "Alert fired",
    ALERT_RESOLVED: "Alert resolved",
    INCIDENT_CREATED: "Incident created",
    INCIDENT_STATUS_CHANGED: "Incident status changed",
    INCIDENT_LINKED: "Alert linked to incident",
    INCIDENT_RESOLVED: "Incident resolved",
  };

/** Chaves numéricas da saúde — `totalServices` é o total, não um tile. */
export type DashboardHealthKey =
  | "operational"
  | "degraded"
  | "unavailable"
  | "unknown";

/**
 * Saúde por status, na ordem em que a UI renderiza (o `totalServices` fica
 * fora: é a SOMA dos quatro, não um quinto estado).
 */
export const DASHBOARD_HEALTH_TILES: ReadonlyArray<{
  key: DashboardHealthKey;
  label: string;
}> = [
  { key: "operational", label: "Operational" },
  { key: "degraded", label: "Degraded" },
  { key: "unavailable", label: "Unavailable" },
  { key: "unknown", label: "Unknown" },
];

/** Chaves numéricas dos incidentes ativos (RESOLVED nunca é ativo). */
export type DashboardIncidentStatusKey = "open" | "investigating" | "identified";

/** Incidentes ativos por status, na ordem da UI — espelha o enum do server. */
export const DASHBOARD_INCIDENT_TILES: ReadonlyArray<{
  key: DashboardIncidentStatusKey;
  label: string;
}> = [
  { key: "open", label: "Open" },
  { key: "investigating", label: "Investigating" },
  { key: "identified", label: "Identified" },
];

/**
 * Status operacional como palavra. Mesmos quatro rótulos do `ServicesPanel`
 * (Prompt 07) — ali o mapa é local ao componente/pill e não é exportado;
 * aqui ele pertence ao contrato de leitura do painel.
 */
export const DASHBOARD_SERVICE_STATUS_LABELS: Record<ServiceStatus, string> = {
  OPERATIONAL: "Operational",
  DEGRADED: "Degraded",
  UNAVAILABLE: "Unavailable",
  UNKNOWN: "Unknown",
};

/** Severidade como palavra (fonte única: `alert-rules.ts`); `null` → `"—"`. */
export function severityLabel(severity: AlertSeverity | null): string {
  return severity ? SEVERITY_LABELS[severity] : "—";
}

/** Status de incidente como palavra (fonte única: `incidents.ts`). */
export function incidentStatusLabel(status: IncidentStatus): string {
  return INCIDENT_STATUS_LABELS[status];
}

/* -------------------------------------------------------------------------- */
/* Faixa temporal                                                             */
/* -------------------------------------------------------------------------- */

/**
 * Rótulo legível da faixa (`24h` → `"Last 24 hours"`). Valor fora do
 * vocabulário é devolvido como está (defensivo — o contrato garante os 5).
 */
export function rangeLabel(range: DashboardRange): string {
  const option = METRICS_RANGE_OPTIONS.find((item) => item.value === range);
  return option?.label ?? range;
}

/**
 * Type guard da faixa — o `<select>` entrega string; só o vocabulário
 * do contrato passa adiante (o backend responderia 400 para o resto).
 */
export function isDashboardRange(value: string): value is DashboardRange {
  return METRICS_RANGE_OPTIONS.some((option) => option.value === value);
}

/* -------------------------------------------------------------------------- */
/* Formatação — janela, tempo e contagens                                     */
/* -------------------------------------------------------------------------- */

/**
 * Janela meia-aberta `[from, to)` como o backend a calculou
 * (`"[2026-02-28T12:00:00Z, 2026-03-01T12:00:00Z)"`): o leitor vê EXATAMENTE
 * o recorte, sem arredondar em "últimas 24h".
 */
export function formatWindow(from: string, to: string): string {
  return `[${from}, ${to})`;
}

/**
 * Instante UTC curto da atividade (`2026-03-01T11:00:00Z` → `"Mar 1 11:00"`).
 * Mesma formatação UTC determinística dos buckets (sem `toLocaleString`, que
 * amarraria o teste ao timezone da máquina).
 */
export function formatActivityTime(timestamp: string): string {
  return formatBucketLabel(timestamp);
}

/**
 * Contagem com plural (`1` → `"1 check"`, `5` → `"5 checks"`). Fica fora do
 * JSX para a regra ser testável e para nenhum texto de contagem nascer
 * errado por engano de concordância.
 */
export function pluralizeCount(
  count: number,
  singular: string,
  plural: string = `${singular}s`
): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

/** Texto da atividade com fallback `"—"` para título nulo (nunca vazio). */
export function activityTitle(item: { title: string | null }): string {
  return item.title && item.title.trim() !== "" ? item.title : "—";
}

/* -------------------------------------------------------------------------- */
/* Decisões de renderização                                                   */
/* -------------------------------------------------------------------------- */

/**
 * KPIs da linha de resumo, já formatados (rótulo + valor legível). As taxas
 * e a latência média vêm em `"—"` quando o backend devolveu `null`.
 */
export function dashboardKpis(
  dashboard: DashboardResponse
): ReadonlyArray<{ label: string; value: string }> {
  const availability = dashboard.availability;
  return [
    { label: "Availability", value: formatPercent(availability.availabilityPercent) },
    { label: "Success rate", value: formatPercent(availability.successRatePercent) },
    { label: "Avg latency", value: formatAvgMillis(availability.avgLatencyMs) },
    { label: "Checks", value: String(availability.totalChecks) },
  ];
}

/**
 * O projeto tem ALGO a mostrar? Saúde com serviço, checagem na janela,
 * alerta disparado, incidente aberto, serviço pedindo atenção ou linha de
 * atividade — basta UM para o painel renderizar as seções.
 *
 * `false` (zero data) NÃO é erro: o backend responde 200 com contagens em
 * zero, taxas `null` e listas vazias, e a UI mostra o estado vazio.
 */
export function hasDashboardData(dashboard: DashboardResponse): boolean {
  const { health, availability, alerts, incidents } = dashboard;
  return (
    health.totalServices > 0 ||
    availability.totalChecks > 0 ||
    alerts.firingTotal > 0 ||
    incidents.activeTotal > 0 ||
    dashboard.servicesNeedingAttention.length > 0 ||
    dashboard.recentActivity.length > 0
  );
}
