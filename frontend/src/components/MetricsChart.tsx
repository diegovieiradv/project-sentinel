/* Sentinel Prompt 11 — Gráfico SVG de disponibilidade (sem libs externas) */
"use client";

import type { MetricsBucket } from "@/lib/api";
import { bucketHasData, formatBucketLabel } from "@/lib/metrics";

/** Dimensões do viewBox (SVG responsivo — escala junto com o contêiner). */
const VIEW_WIDTH = 640;
const VIEW_HEIGHT = 170;
const PADDING = { top: 10, right: 10, bottom: 26, left: 40 };

/** Menor altura visual de uma barra — um 0% precisa aparecer como traço. */
const MIN_BAR_HEIGHT = 2;

/**
 * Série temporal de disponibilidade por bucket — SVG inline puro (o projeto
 * não tem biblioteca de gráficos e o escopo proíbe dependência nova).
 *
 * Contrato visual:
 * - eixo Y fixo 0–100% (grade em 0/50/100), barras = `availability` do bucket;
 * - bucket SEM check → espaço vazio (grade completa, buraco é buraco);
 * - bucket com check mas taxa `null` (só falhas internas) → traço cinza na
 *   base: houve execução, não há disponibilidade a reportar;
 * - `role="img"` + `aria-label` resume a série para leitores de tela.
 */
export default function MetricsChart({
  buckets,
  rangeLabel,
}: {
  buckets: MetricsBucket[];
  /** Rótulo legível da faixa (ex.: "Last 24 hours") para o aria-label. */
  rangeLabel: string;
}) {
  if (buckets.length === 0) return null;

  const plotWidth = VIEW_WIDTH - PADDING.left - PADDING.right;
  const plotHeight = VIEW_HEIGHT - PADDING.top - PADDING.bottom;
  const slot = plotWidth / buckets.length;
  const barWidth = Math.max(2, slot * 0.7);

  /** Y do topo de uma barra para uma percentagem (0–100). */
  function yOf(percent: number): number {
    return PADDING.top + plotHeight * (1 - percent / 100);
  }

  const withData = buckets.filter(bucketHasData).length;
  const first = formatBucketLabel(buckets[0].bucketStart);
  const last = formatBucketLabel(buckets[buckets.length - 1].bucketStart);
  const ariaLabel =
    `Availability over ${buckets.length} buckets (${rangeLabel}), ` +
    `${first} to ${last} UTC; ${withData} of ${buckets.length} buckets have checks.`;

  /** Índices com rótulo no eixo X: primeiro, meio e último (não se cruzam). */
  const labelIndexes = new Set([
    0,
    Math.floor((buckets.length - 1) / 2),
    buckets.length - 1,
  ]);

  return (
    <figure className="mt-2">
      <svg
        role="img"
        aria-label={ariaLabel}
        viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
        className="h-auto w-full"
      >
        {/* Grade + rótulos do eixo Y (0%, 50%, 100%) */}
        {[100, 50, 0].map((tick) => (
          <g key={tick}>
            <line
              x1={PADDING.left}
              x2={VIEW_WIDTH - PADDING.right}
              y1={yOf(tick)}
              y2={yOf(tick)}
              style={{ stroke: "var(--border)" }}
              strokeWidth={1}
            />
            <text
              x={PADDING.left - 6}
              y={yOf(tick) + 3}
              textAnchor="end"
              fontSize={9}
              style={{ fill: "var(--muted)" }}
            >
              {tick}%
            </text>
          </g>
        ))}

        {/* Barras */}
        {buckets.map((bucket, index) => {
          const x = PADDING.left + index * slot + (slot - barWidth) / 2;
          if (!bucketHasData(bucket)) return null;
          if (bucket.availability === null) {
            // Checagem sem taxa (ex.: só falhas internas) — traço cinza.
            return (
              <rect
                key={bucket.bucketStart}
                x={x}
                y={PADDING.top + plotHeight - MIN_BAR_HEIGHT}
                width={barWidth}
                height={MIN_BAR_HEIGHT}
                style={{ fill: "var(--muted)" }}
              />
            );
          }
          const height = Math.max(
            MIN_BAR_HEIGHT,
            plotHeight * (bucket.availability / 100)
          );
          return (
            <rect
              key={bucket.bucketStart}
              x={x}
              y={PADDING.top + plotHeight - height}
              width={barWidth}
              height={height}
              style={{ fill: "var(--accent)" }}
            />
          );
        })}

        {/* Rótulos do eixo X: primeiro / meio / último bucket (UTC) */}
        {buckets.map((bucket, index) =>
          labelIndexes.has(index) ? (
            <text
              key={`label-${bucket.bucketStart}`}
              x={PADDING.left + index * slot + slot / 2}
              y={VIEW_HEIGHT - PADDING.bottom + 14}
              textAnchor={
                index === 0
                  ? "start"
                  : index === buckets.length - 1
                    ? "end"
                    : "middle"
              }
              fontSize={9}
              style={{ fill: "var(--muted)" }}
            >
              {formatBucketLabel(bucket.bucketStart)}
            </text>
          ) : null
        )}
      </svg>
      <figcaption className="mt-1 text-xs text-(--muted)">
        Availability by bucket · axis in UTC
      </figcaption>
    </figure>
  );
}
