/**
 * Helpers puros de métricas (Prompt 11) — formatação e seleção de faixa,
 * sem rede, sem DOM, sem estado.
 *
 * Regra central: `null` (sem dados na janela) vira em dash "—", NUNCA 0.
 * As taxas vêm prontas do backend (HALF_UP, 2 casas) — aqui só se formata
 * para exibição; nada aqui recalcula disponibilidade.
 *
 * Os rótulos de bucket usam UTC determinístico ( getters do `Date` em UTC,
 * sem `toLocaleString`) para o teste não depender do timezone da máquina.
 */
import type { MetricsBucket, MetricsRange } from "./api";

/** Opções do seletor de faixa — espelha o enum `MetricsRange` do backend. */
export const METRICS_RANGE_OPTIONS: ReadonlyArray<{
  value: MetricsRange;
  label: string;
}> = [
  { value: "1h", label: "Last hour" },
  { value: "6h", label: "Last 6 hours" },
  { value: "24h", label: "Last 24 hours" },
  { value: "7d", label: "Last 7 days" },
  { value: "30d", label: "Last 30 days" },
];

/** Meses abreviados em UTC (rótulo curto do eixo). */
const UTC_MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
] as const;

/**
 * Percentual com 2 casas (`75` → `"75.00%"`); `null` → `"—"` (sem dados —
 * nunca `0.00%`, que afirmaria disponibilidade zero onde não há evidência).
 */
export function formatPercent(value: number | null): string {
  if (value === null) return "—";
  return `${value.toFixed(2)}%`;
}

/**
 * Latência média em milissegundos arredondada (`124.5` → `"125ms"`);
 * `null` → `"—"` (sem SUCCESS/DEGRADED na janela — nunca `0ms`).
 */
export function formatAvgMillis(value: number | null): string {
  if (value === null) return "—";
  return `${Math.round(value)}ms`;
}

/**
 * Rótulo UTC curto de um bucket (`2026-03-01T12:00:00Z` → `"Mar 1 12:00"`).
 * Entrada inválida é devolvida como está (defensivo — o contrato garante ISO).
 * O leitor do eixo sabe que é UTC pela legenda do gráfico.
 */
export function formatBucketLabel(bucketStart: string): string {
  const date = new Date(bucketStart);
  if (Number.isNaN(date.getTime())) return bucketStart;
  const month = UTC_MONTHS[date.getUTCMonth()];
  const day = date.getUTCDate();
  const hours = String(date.getUTCHours()).padStart(2, "0");
  const minutes = String(date.getUTCMinutes()).padStart(2, "0");
  return `${month} ${day} ${hours}:${minutes}`;
}

/**
 * O bucket tem ao menos um check? (`totalChecks > 0`.) Distingue "houve
 * execução mas nenhuma relevante para a taxa" de "janela vazia".
 */
export function bucketHasData(bucket: MetricsBucket): boolean {
  return bucket.totalChecks > 0;
}

/**
 * Algum bucket da série tem dados? A grade é sempre completa (mesmo vazia),
 * então é isso que separa "gráfico com barras" de "sem checagens no período".
 */
export function hasAnyMetricsData(buckets: MetricsBucket[]): boolean {
  return buckets.some(bucketHasData);
}
