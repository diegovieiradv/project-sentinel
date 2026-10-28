/* Sentinel — Gráfico de área SVG de disponibilidade (sem libs externas) */
"use client";

import {
  useId,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
} from "react";
import type { MetricsBucket } from "@/lib/api";
import { bucketHasData, formatBucketLabel, formatPercent } from "@/lib/metrics";

/** Dimensões do viewBox (SVG responsivo — escala junto com o contêiner). */
const VIEW_WIDTH = 640;
const VIEW_HEIGHT = 190;
const PADDING = { top: 14, right: 12, bottom: 28, left: 42 };

type Point = { x: number; y: number };

/**
 * Interpolação cúbica MONOTÔNICA (Fritsch–Carlson, mesma família da
 * `curveMonotoneX` do d3): a curva é suave, mas dentro de cada intervalo
 * nunca ultrapassa os valores vizinhos — impossível criar pico acima de
 * 100% ou vale abaixo de 0% que não exista nos dados.
 *
 * Retorna o path `d` (M + segmentos C) para uma série de pontos já em
 * coordenadas de viewBox.
 */
function monotonePath(points: Point[]): string {
  const n = points.length;
  if (n === 0) return "";
  if (n === 1) return `M ${points[0].x} ${points[0].y}`;

  const h: number[] = [];
  const s: number[] = []; // secantes por intervalo
  for (let i = 0; i < n - 1; i++) {
    h.push(points[i + 1].x - points[i].x);
    s.push((points[i + 1].y - points[i].y) / h[i]);
  }

  // Tangentes iniciais
  const m: number[] = new Array(n);
  m[0] = s[0];
  m[n - 1] = s[n - 2];
  for (let i = 1; i < n - 1; i++) {
    // Mudança de sinal → mínimo/local máximo real: tangente zero (sem overshoot)
    m[i] = s[i - 1] * s[i] <= 0 ? 0 : (s[i - 1] + s[i]) / 2;
  }

  // Limite de Fritsch–Carlson: garante monotonicidade visual do trecho
  for (let i = 0; i < n - 1; i++) {
    if (s[i] === 0) {
      m[i] = 0;
      m[i + 1] = 0;
      continue;
    }
    const a = m[i] / s[i];
    const b = m[i + 1] / s[i];
    const norm = a * a + b * b;
    if (norm > 9) {
      const tau = 3 / Math.sqrt(norm);
      m[i] *= tau;
      m[i + 1] *= tau;
    }
  }

  let d = `M ${points[0].x} ${points[0].y}`;
  for (let i = 0; i < n - 1; i++) {
    const c1x = points[i].x + h[i] / 3;
    const c1y = points[i].y + (m[i] * h[i]) / 3;
    const c2x = points[i + 1].x - h[i] / 3;
    const c2y = points[i + 1].y - (m[i + 1] * h[i]) / 3;
    d += ` C ${c1x} ${c1y}, ${c2x} ${c2y}, ${points[i + 1].x} ${points[i + 1].y}`;
  }
  return d;
}

/**
 * Série temporal de disponibilidade por bucket — gráfico de ÁREA SVG inline
 * puro (o projeto não tem biblioteca de gráficos e o escopo proíbe
 * dependência nova).
 *
 * Contrato visual:
 * - eixo Y fixo 0–100% (grade em 0/50/100), linha = `availability` do bucket;
 * - curva suave MONOTÔNICA (nunca cria pico fora dos dados nem sai de 0–100);
 * - preenchimento em degradê: intenso junto à linha, transparente na base;
 * - bucket SEM check → lacuna real: a linha é interrompida (buraco é buraco);
 * - bucket com check mas taxa `null` (só falhas internas) → traço cinza na
 *   base: houve execução, não há disponibilidade a reportar;
 * - disponibilidade constante em 100% → linha constante em 100%;
 * - tooltip em mouse/toque/teclado, usando SOMENTE os dados recebidos;
 * - `role="img"` + `aria-label` resume a série para leitores de tela;
 * - animação de desenho inicial respeita `prefers-reduced-motion`.
 */
export default function MetricsChart({
  buckets,
  rangeLabel,
}: {
  buckets: MetricsBucket[];
  /** Rótulo legível da faixa (ex.: "Last 24 hours") para o aria-label. */
  rangeLabel: string;
}) {
  const gradientId = useId();
  const [hover, setHover] = useState<{ index: number } | null>(null);

  if (buckets.length === 0) return null;

  const plotWidth = VIEW_WIDTH - PADDING.left - PADDING.right;
  const plotHeight = VIEW_HEIGHT - PADDING.top - PADDING.bottom;
  const slot = plotWidth / buckets.length;
  const baseY = PADDING.top + plotHeight;

  /** Y do gráfico para uma percentagem (0–100). */
  function yOf(percent: number): number {
    return PADDING.top + plotHeight * (1 - Math.min(100, Math.max(0, percent)) / 100);
  }

  /** X do centro do bucket i. */
  function xOf(index: number): number {
    return PADDING.left + index * slot + slot / 2;
  }

  // Segmentos contíguos de buckets com taxa válida — lacunas separam paths.
  const segments: Point[][] = [];
  let current: Point[] = [];
  buckets.forEach((bucket, index) => {
    if (bucketHasData(bucket) && bucket.availability !== null) {
      current.push({ x: xOf(index), y: yOf(bucket.availability) });
    } else if (current.length > 0) {
      segments.push(current);
      current = [];
    }
  });
  if (current.length > 0) segments.push(current);

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

  /** Bucket sob o cursor/toque a partir do X relativo ao overlay. */
  function indexOfClientX(overlay: SVGRectElement, clientX: number): number {
    const rect = overlay.getBoundingClientRect();
    const relX = ((clientX - rect.left) / rect.width) * VIEW_WIDTH;
    const index = Math.floor((relX - PADDING.left) / slot);
    return Math.min(buckets.length - 1, Math.max(0, index));
  }

  function handlePointer(event: ReactPointerEvent<SVGRectElement>): void {
    const index = indexOfClientX(event.currentTarget, event.clientX);
    setHover((previous) =>
      previous?.index === index ? previous : { index }
    );
  }

  function handleKey(event: ReactKeyboardEvent<SVGRectElement>): void {
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      const delta = event.key === "ArrowLeft" ? -1 : 1;
      setHover((previous) => {
        const base = previous?.index ?? 0;
        return {
          index: Math.min(buckets.length - 1, Math.max(0, base + delta)),
        };
      });
      return;
    }
    if (event.key === "Escape") setHover(null);
  }

  const hovered = hover ? buckets[hover.index] : null;
  const hoverPct =
    hover && buckets.length > 0
      ? Math.min(
          92,
          Math.max(8, (xOf(hover.index) / VIEW_WIDTH) * 100)
        )
      : 0;

  return (
    <figure className="relative mt-2">
      <svg
        role="img"
        aria-label={ariaLabel}
        viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
        className="h-auto w-full"
      >
        <defs>
          {/* Degradê do preenchimento: intenso junto à linha, transparente na base */}
          <linearGradient
            id={gradientId}
            x1="0"
            y1="0"
            x2="0"
            y2="1"
          >
            <stop offset="0%" stopColor="var(--accent)" stopOpacity={0.45} />
            <stop offset="55%" stopColor="var(--accent)" stopOpacity={0.16} />
            <stop offset="100%" stopColor="var(--accent)" stopOpacity={0.02} />
          </linearGradient>
        </defs>

        {/* Grade discreta + rótulos do eixo Y (0%, 50%, 100%) */}
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

        {/* Áreas (preenchimento) — um path por trecho contínuo */}
        {segments.map((points, segmentIndex) => {
          const lineD = monotonePath(points);
          const areaD =
            `${lineD} L ${points[points.length - 1].x} ${baseY}` +
            ` L ${points[0].x} ${baseY} Z`;
          return (
            <g key={`segment-${segmentIndex}`}>
              <path
                d={areaD}
                fill={`url(#${gradientId})`}
                pathLength={1}
                className="sentinel-chart-area"
              />
              <path
                d={lineD}
                fill="none"
                style={{
                  stroke: "var(--accent)",
                  strokeWidth: 2,
                  strokeLinecap: "round",
                  strokeLinejoin: "round",
                }}
                pathLength={1}
                className="sentinel-chart-line"
              />
            </g>
          );
        })}

        {/* Segmento de ponto único → marcador visível (área de largura zero é invisível) */}
        {segments.map((points, segmentIndex) =>
          points.length === 1 ? (
            <circle
              key={`dot-${segmentIndex}`}
              cx={points[0].x}
              cy={points[0].y}
              r={3.5}
              style={{ fill: "var(--accent)", stroke: "var(--card)" }}
              strokeWidth={2}
            />
          ) : null
        )}

        {/* Buckets com check mas sem taxa (só falhas internas) — traço cinza */}
        {buckets.map((bucket, index) =>
          bucketHasData(bucket) && bucket.availability === null ? (
            <rect
              key={`nodata-${bucket.bucketStart}`}
              x={xOf(index) - 3}
              y={baseY - 2}
              width={6}
              height={2}
              rx={1}
              style={{ fill: "var(--muted)" }}
            />
          ) : null
        )}

        {/* Marcador + linha-guia do bucket em hover */}
        {hovered && hover && bucketHasData(hovered) ? (
          <g aria-hidden="true">
            <line
              x1={xOf(hover.index)}
              x2={xOf(hover.index)}
              y1={PADDING.top}
              y2={baseY}
              style={{ stroke: "var(--accent)", opacity: 0.25 }}
              strokeWidth={1}
            />
            {hovered.availability !== null && (
              <circle
                cx={xOf(hover.index)}
                cy={yOf(hovered.availability)}
                r={4}
                style={{ fill: "var(--accent)", stroke: "var(--card)" }}
                strokeWidth={2}
              />
            )}
          </g>
        ) : null}

        {/* Rótulos do eixo X: primeiro / meio / último bucket (UTC) */}
        {buckets.map((bucket, index) =>
          labelIndexes.has(index) ? (
            <text
              key={`label-${bucket.bucketStart}`}
              x={xOf(index)}
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

        {/* Overlay de interação: mouse, toque e teclado (setas) */}
        <rect
          x={PADDING.left}
          y={PADDING.top}
          width={plotWidth}
          height={plotHeight}
          fill="transparent"
          tabIndex={0}
          aria-label="Chart values. Use Left and Right arrow keys to inspect buckets; Escape to dismiss."
          onPointerMove={handlePointer}
          onPointerDown={handlePointer}
          onPointerLeave={() => setHover(null)}
          onBlur={() => setHover(null)}
          onKeyDown={handleKey}
          style={{ touchAction: "pan-y", cursor: "crosshair", outline: "none" }}
        />
      </svg>

      {/* Tooltip — posicionado por percentual do viewBox, sem JS de layout */}
      {hovered && hover ? (
        <div
          role="status"
          className="pointer-events-none absolute z-10 -translate-x-1/2 rounded-lg border border-(--border-hover) bg-[#202D2E]/95 px-3 py-2 text-xs shadow-[0_8px_24px_rgba(0,0,0,0.45)] backdrop-blur-sm"
          style={{ left: `${hoverPct}%`, top: 0 }}
        >
          <p className="font-medium text-[#F5F7F5]">
            {formatBucketLabel(hovered.bucketStart)} UTC
          </p>
          {bucketHasData(hovered) ? (
            <p className="mt-0.5 text-(--muted)">
              {hovered.availability !== null ? (
                <>
                  Availability{" "}
                  <span className="font-semibold text-(--accent)">
                    {formatPercent(hovered.availability)}
                  </span>{" "}
                  · {hovered.totalChecks} check
                  {hovered.totalChecks === 1 ? "" : "s"}
                </>
              ) : (
                "Executed, no availability to report"
              )}
            </p>
          ) : (
            <p className="mt-0.5 text-(--muted)">No checks in this bucket</p>
          )}
        </div>
      ) : null}

      <figcaption className="mt-1 text-xs text-(--muted)">
        Availability by bucket · axis in UTC
      </figcaption>
    </figure>
  );
}
