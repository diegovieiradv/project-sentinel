/**
 * Testes do Prompt 11 — helpers de métricas (`src/lib/metrics.ts`) e do
 * contrato de URL das funções novas de `src/lib/api.ts`.
 *
 * Cobrem o que é puro/verificável sem DOM: formatação com "—" para null
 * (nunca 0), rótulo UTC determinístico, opções de faixa espelhando o
 * contrato, detecção de série vazia, query `?range=` e o path de CINCO
 * níveis dos dois endpoints de métricas.
 *
 * Ambiente `node` (ver `vitest.config.ts`): sem jsdom. A RENDERIZAÇÃO da UI
 * (MetricsSection/MetricsChart) não é coberta aqui: exigiria
 * jsdom/testing-library, que não é dependência deste projeto (mesma lacuna
 * documentada em `monitors.test.ts`).
 */
import { afterEach, describe, expect, it } from "vitest";
import {
  API_URL,
  buildMetricsRangeParams,
  getMonitorMetrics,
  getServiceMetrics,
} from "./api";
import {
  METRICS_RANGE_OPTIONS,
  bucketHasData,
  formatAvgMillis,
  formatBucketLabel,
  formatPercent,
  hasAnyMetricsData,
} from "./metrics";

const ORG_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
const PROJECT_ID = "9b2c1f6e-4d5a-4e8f-8a1b-2c3d4e5f6a7b";
const SERVICE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
const MONITOR_ID = "7d4e2b1a-9c3f-4d6e-8b2a-5f1e0c9d8a7b";

/** Acesso dinâmico aos globals para instalar/remover stubs sem quebrar o TS. */
const globals = globalThis as unknown as Record<string, unknown>;
const originalFetch = globals.fetch;

/** Stub de fetch que captura a URL chamada e devolve o body informado. */
function stubFetchCapture(captured: { url?: string }, body: unknown): void {
  globals.fetch = (input: unknown) => {
    captured.url = String(input);
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { "content-type": "application/json" },
      })
    );
  };
}

/** Bucket mínimo com contagens coerentes (soma = totalChecks). */
function bucket(totalChecks: number, availability: number | null = null) {
  return {
    bucketStart: "2026-03-01T12:00:00Z",
    totalChecks,
    success: 0,
    degraded: 0,
    targetFailure: 0,
    sentinelInternalFailure: 0,
    availability,
    successRate: null,
    avgLatencyMs: null,
    minLatencyMs: null,
    maxLatencyMs: null,
  };
}

afterEach(() => {
  globals.fetch = originalFetch;
});

describe("formatPercent", () => {
  it("null (sem dados) vira em dash — nunca 0.00%", () => {
    expect(formatPercent(null)).toBe("—");
  });

  it("formata com 2 casas, inclusive zero absoluto (taxa calculada)", () => {
    expect(formatPercent(0)).toBe("0.00%");
    expect(formatPercent(75)).toBe("75.00%");
    expect(formatPercent(90)).toBe("90.00%");
    expect(formatPercent(100)).toBe("100.00%");
    expect(formatPercent(66.67)).toBe("66.67%");
  });
});

describe("formatAvgMillis", () => {
  it("null (sem SUCCESS/DEGRADED na janela) vira em dash", () => {
    expect(formatAvgMillis(null)).toBe("—");
  });

  it("arredonda para inteiro em ms", () => {
    expect(formatAvgMillis(0)).toBe("0ms");
    expect(formatAvgMillis(60)).toBe("60ms");
    expect(formatAvgMillis(124.5)).toBe("125ms");
  });
});

describe("formatBucketLabel", () => {
  it("formata em UTC determinístico (não depende do timezone local)", () => {
    expect(formatBucketLabel("2026-03-01T12:00:00Z")).toBe("Mar 1 12:00");
    expect(formatBucketLabel("2026-02-28T00:00:00Z")).toBe("Feb 28 00:00");
    expect(formatBucketLabel("2026-12-31T23:59:59Z")).toBe("Dec 31 23:59");
  });

  it("entrada inválida é devolvida como está (defensivo)", () => {
    expect(formatBucketLabel("not-a-date")).toBe("not-a-date");
  });
});

describe("METRICS_RANGE_OPTIONS", () => {
  it("espelha exatamente o contrato 1h|6h|24h|7d|30d, nessa ordem", () => {
    expect(METRICS_RANGE_OPTIONS.map((option) => option.value)).toEqual([
      "1h",
      "6h",
      "24h",
      "7d",
      "30d",
    ]);
  });

  it("rótulos são únicos e não vazios", () => {
    const labels = METRICS_RANGE_OPTIONS.map((option) => option.label);
    expect(new Set(labels).size).toBe(labels.length);
    expect(labels.every((label) => label.length > 0)).toBe(true);
  });
});

describe("bucketHasData / hasAnyMetricsData", () => {
  it("bucket sem check não tem dados, mesmo com campos presentes", () => {
    expect(bucketHasData(bucket(0))).toBe(false);
    expect(bucketHasData(bucket(3))).toBe(true);
  });

  it("série toda vazia → sem dados; uma linha com check já conta", () => {
    expect(hasAnyMetricsData([bucket(0), bucket(0)])).toBe(false);
    expect(hasAnyMetricsData([bucket(0), bucket(1)])).toBe(true);
    expect(hasAnyMetricsData([])).toBe(false);
  });
});

describe("buildMetricsRangeParams", () => {
  it("faixa ausente → query vazia (default do servidor)", () => {
    expect(buildMetricsRangeParams()).toBe("");
    expect(buildMetricsRangeParams(undefined)).toBe("");
  });

  it("faixa presente → ?range=<literal do contrato>", () => {
    expect(buildMetricsRangeParams("1h")).toBe("?range=1h");
    expect(buildMetricsRangeParams("7d")).toBe("?range=7d");
    expect(buildMetricsRangeParams("30d")).toBe("?range=30d");
  });
});

describe("getMonitorMetrics", () => {
  it("path de CINCO níveis com /metrics e ?range= quando informado", async () => {
    const captured: { url?: string } = {};
    stubFetchCapture(captured, { buckets: [] });

    await getMonitorMetrics(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      MONITOR_ID,
      "7d"
    );

    expect(captured.url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors/${MONITOR_ID}/metrics?range=7d`
    );
  });

  it("sem range → endpoint sem query (servidor aplica o default)", async () => {
    const captured: { url?: string } = {};
    stubFetchCapture(captured, { buckets: [] });

    await getMonitorMetrics(ORG_ID, PROJECT_ID, SERVICE_ID, MONITOR_ID);

    expect(captured.url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors/${MONITOR_ID}/metrics`
    );
  });
});

describe("getServiceMetrics", () => {
  it("path de QUATRO níveis com /metrics e ?range= quando informado", async () => {
    const captured: { url?: string } = {};
    stubFetchCapture(captured, { buckets: [], monitors: [] });

    await getServiceMetrics(ORG_ID, PROJECT_ID, SERVICE_ID, "1h");

    expect(captured.url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/metrics?range=1h`
    );
  });

  it("sem range → endpoint sem query", async () => {
    const captured: { url?: string } = {};
    stubFetchCapture(captured, { buckets: [], monitors: [] });

    await getServiceMetrics(ORG_ID, PROJECT_ID, SERVICE_ID);

    expect(captured.url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/metrics`
    );
  });
});
