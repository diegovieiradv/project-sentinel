/**
 * Testes do Prompt 16 — helpers do painel (`src/lib/dashboard.ts`) e do
 * contrato de URL/erros de `getDashboard` em `src/lib/api.ts`.
 *
 * Cobrem o que é puro/verificável sem DOM: rótulos completos por enum (a UI
 * nunca mostra o enum cru), `null` → `"—"` nas taxas (nunca `0`), janela
 * meia-aberta, plural de contagem, detecção de projeto sem dados, e o path
 * de TRÊS níveis + `?range=` do endpoint único, além de 400/404/401.
 *
 * Ambiente `node` (ver `vitest.config.ts`): sem jsdom. A RENDERIZAÇÃO da UI
 * (DashboardPanel) não é coberta aqui: exigiria jsdom/testing-library, que
 * não é dependência deste projeto (mesma lacuna documentada em
 * `monitors.test.ts`).
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  API_URL,
  ApiError,
  SESSION_EXPIRED_EVENT,
  clearOrganizationId,
  getAccessToken,
  getDashboard,
  setAccessToken,
  type DashboardResponse,
} from "./api";
import { SEVERITY_LABELS } from "./alert-rules";
import { INCIDENT_STATUS_LABELS } from "./incidents";
import {
  DASHBOARD_ACTIVITY_LABELS,
  DASHBOARD_HEALTH_TILES,
  DASHBOARD_INCIDENT_TILES,
  DASHBOARD_SERVICE_STATUS_LABELS,
  activityTitle,
  dashboardKpis,
  formatActivityTime,
  formatWindow,
  hasDashboardData,
  incidentStatusLabel,
  isDashboardRange,
  pluralizeCount,
  rangeLabel,
  severityLabel,
} from "./dashboard";

const ORG_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
const PROJECT_ID = "9b2c1f6e-4d5a-4e8f-8a1b-2c3d4e5f6a7b";

/** Painel ZERADO — o contrato de "zero data" (200, não 404/erro). */
const EMPTY_DASHBOARD: DashboardResponse = {
  projectId: PROJECT_ID,
  rangeKey: "24h",
  windowFrom: "2026-02-28T12:00:00Z",
  windowTo: "2026-03-01T12:00:00Z",
  health: {
    totalServices: 0,
    operational: 0,
    degraded: 0,
    unavailable: 0,
    unknown: 0,
  },
  availability: {
    availabilityPercent: null,
    totalChecks: 0,
    relevantChecks: 0,
    successRatePercent: null,
    avgLatencyMs: null,
    minLatencyMs: null,
    maxLatencyMs: null,
  },
  alerts: {
    firingTotal: 0,
    sev1: 0,
    sev2: 0,
    sev3: 0,
    sev4: 0,
    activeAlerts: [],
  },
  incidents: {
    activeTotal: 0,
    open: 0,
    investigating: 0,
    identified: 0,
    activeIncidents: [],
  },
  servicesNeedingAttention: [],
  recentActivity: [],
};

/** Clonador raso — cada teste acende UMA seção do zero data. */
function withData(patch: Partial<DashboardResponse>): DashboardResponse {
  return { ...EMPTY_DASHBOARD, ...patch };
}

/* -------------------------------------------------------------------------- */
/* Harness de fetch (mesmo padrão de services.test.ts / metrics.test.ts)     */
/* -------------------------------------------------------------------------- */

const globals = globalThis as unknown as Record<string, unknown>;
const originalFetch = globals.fetch;

const calls: { url: string; init: RequestInit }[] = [];
const dispatched: string[] = [];

/** Fetch mockado que registra a chamada e responde `status` + `body`. */
function respondWith(status: number, body: unknown): void {
  globals.fetch = (input: unknown, init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { "content-type": "application/json" },
      })
    );
  };
}

function headersOf(index = 0): Record<string, string> {
  return (calls[index].init.headers ?? {}) as Record<string, string>;
}

/** localStorage mínimo em memória (mesma semântica dos testes irmãos). */
function makeStorage(): void {
  const store = new Map<string, string>();
  globals.localStorage = {
    getItem: (key: string) => (store.get(key) ?? null),
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => store.clear(),
  };
}

/** `window` mínimo: registrador dos eventos disparados pelo `apiFetch`. */
function makeWindow(): void {
  globals.window = {
    localStorage: globals.localStorage,
    dispatchEvent: (event: Event) => {
      dispatched.push(event.type);
      return true;
    },
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  };
}

beforeEach(() => {
  calls.length = 0;
  dispatched.length = 0;
  clearOrganizationId();
  setAccessToken("access-token-em-memoria");
  makeStorage();
  makeWindow();
  globals.document = { cookie: "XSRF-TOKEN=csrf-test-token" };
});

afterEach(() => {
  clearOrganizationId();
  setAccessToken(null);
  delete globals.document;
  delete globals.window;
  delete globals.localStorage;
  globals.fetch = originalFetch;
  calls.length = 0;
  dispatched.length = 0;
});

/* -------------------------------------------------------------------------- */
/* Contrato da função (path, query, headers, erros)                          */
/* -------------------------------------------------------------------------- */

describe("getDashboard (contrato)", () => {
  it("path de TRÊS níveis com range explícito", async () => {
    respondWith(200, EMPTY_DASHBOARD);

    await getDashboard(ORG_ID, PROJECT_ID, "6h");

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/dashboard?range=6h`
    );
  });

  it("sem range → sem query string (o server aplica o default 24h)", async () => {
    respondWith(200, EMPTY_DASHBOARD);

    await getDashboard(ORG_ID, PROJECT_ID);

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/dashboard`
    );
  });

  it("é GET puro: nenhum method no init (sem escrita no painel)", async () => {
    respondWith(200, EMPTY_DASHBOARD);

    await getDashboard(ORG_ID, PROJECT_ID, "1h");

    expect(calls[0].init.method).toBeUndefined();
  });

  it("injeta Authorization e NÃO injeta X-Organization-Id (org no path)", async () => {
    respondWith(200, EMPTY_DASHBOARD);

    await getDashboard(ORG_ID, PROJECT_ID);

    const headers = headersOf();
    expect(getAccessToken()).not.toBeNull();
    expect(headers["Authorization"]).toBe("Bearer access-token-em-memoria");
    expect(headers["X-Organization-Id"]).toBeUndefined();
    expect(headers["Accept"]).toBe("application/json");
  });

  it("404 (projeto de outro tenant) → ApiError genérico, sem vazar domínio", async () => {
    respondWith(404, {
      timestamp: "2026-03-01T12:00:00Z",
      status: 404,
      code: "NOT_FOUND",
      message: "Resource not found",
      path: "/api/v1/organizations/x/projects/y/dashboard",
    });

    const error = await getDashboard(ORG_ID, PROJECT_ID, "24h").catch(
      (err: unknown) => err
    );

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    expect((error as ApiError).code).toBe("NOT_FOUND");
    expect((error as ApiError).message).toBe("Resource not found");
    // sessão NÃO é derrubada por 404 (só 401 limpa)
    expect(dispatched).not.toContain(SESSION_EXPIRED_EVENT);
  });

  it("400 de range inválido → ApiError VALIDATION_ERROR (mensagem canônica)", async () => {
    respondWith(400, {
      timestamp: "2026-03-01T12:00:00Z",
      status: 400,
      code: "VALIDATION_ERROR",
      message: "range: must be one of 1h, 6h, 24h, 7d, 30d",
      path: "/api/v1/organizations/x/projects/y/dashboard",
    });

    const error = await getDashboard(ORG_ID, PROJECT_ID, "12h" as never).catch(
      (err: unknown) => err
    );

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
    expect((error as ApiError).code).toBe("VALIDATION_ERROR");
    expect((error as ApiError).message).toContain("1h, 6h, 24h, 7d, 30d");
  });

  it("401 → dispara SESSION_EXPIRED_EVENT (mesma regra das demais rotas)", async () => {
    respondWith(401, {
      timestamp: "2026-03-01T12:00:00Z",
      status: 401,
      code: "UNAUTHORIZED",
      message: "Unauthorized",
      path: "/api/v1/organizations/x/projects/y/dashboard",
    });

    const error = await getDashboard(ORG_ID, PROJECT_ID).catch(
      (err: unknown) => err
    );

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
    expect(dispatched).toContain(SESSION_EXPIRED_EVENT);
  });
});

/* -------------------------------------------------------------------------- */
/* Rótulos — enum nunca cru na tela                                          */
/* -------------------------------------------------------------------------- */

describe("rótulos do painel", () => {
  it("atividade cobre os 6 tipos do enum, com palavra legível", () => {
    const types = [
      "ALERT_FIRED",
      "ALERT_RESOLVED",
      "INCIDENT_CREATED",
      "INCIDENT_STATUS_CHANGED",
      "INCIDENT_LINKED",
      "INCIDENT_RESOLVED",
    ] as const;

    for (const type of types) {
      expect(DASHBOARD_ACTIVITY_LABELS[type]).toBeTruthy();
      expect(DASHBOARD_ACTIVITY_LABELS[type]).not.toBe(type);
      expect(DASHBOARD_ACTIVITY_LABELS[type]).not.toContain("_");
    }
    expect(Object.keys(DASHBOARD_ACTIVITY_LABELS)).toHaveLength(types.length);
  });

  it("saúde são 4 tiles numéricos, sem contar o total como quinto estado", () => {
    expect(DASHBOARD_HEALTH_TILES.map((tile) => tile.key)).toEqual([
      "operational",
      "degraded",
      "unavailable",
      "unknown",
    ]);
    expect(DASHBOARD_HEALTH_TILES.map((tile) => tile.key)).not.toContain(
      "totalServices" as never
    );
    expect(DASHBOARD_HEALTH_TILES.map((tile) => tile.label)).toEqual([
      "Operational",
      "Degraded",
      "Unavailable",
      "Unknown",
    ]);
  });

  it("incidentes ativos são os 3 status que nunca foram resolvidos", () => {
    expect(DASHBOARD_INCIDENT_TILES.map((tile) => tile.key)).toEqual([
      "open",
      "investigating",
      "identified",
    ]);
    expect(DASHBOARD_INCIDENT_TILES.map((tile) => tile.key)).not.toContain(
      "resolved" as never
    );
    for (const tile of DASHBOARD_INCIDENT_TILES) {
      expect(tile.label).toBe(
        INCIDENT_STATUS_LABELS[
          tile.key.toUpperCase() as keyof typeof INCIDENT_STATUS_LABELS
        ]
      );
    }
  });

  it("status de serviço espelha os 4 valores do enum", () => {
    expect(Object.keys(DASHBOARD_SERVICE_STATUS_LABELS).sort()).toEqual([
      "DEGRADED",
      "OPERATIONAL",
      "UNAVAILABLE",
      "UNKNOWN",
    ]);
    expect(DASHBOARD_SERVICE_STATUS_LABELS.UNAVAILABLE).toBe("Unavailable");
  });

  it("severidade reusa a fonte única (alert-rules) e null vira '—'", () => {
    expect(severityLabel("SEV1")).toBe(SEVERITY_LABELS.SEV1);
    expect(severityLabel("SEV4")).toBe(SEVERITY_LABELS.SEV4);
    expect(severityLabel(null)).toBe("—");
  });

  it("status de incidente reusa a fonte única (incidents)", () => {
    expect(incidentStatusLabel("OPEN")).toBe(INCIDENT_STATUS_LABELS.OPEN);
    expect(incidentStatusLabel("RESOLVED")).toBe(
      INCIDENT_STATUS_LABELS.RESOLVED
    );
  });
});

/* -------------------------------------------------------------------------- */
/* Faixa, janela e formatação                                                 */
/* -------------------------------------------------------------------------- */

describe("faixa e janela", () => {
  it("rangeLabel traduz as 5 faixas do contrato", () => {
    expect(rangeLabel("1h")).toBe("Last hour");
    expect(rangeLabel("6h")).toBe("Last 6 hours");
    expect(rangeLabel("24h")).toBe("Last 24 hours");
    expect(rangeLabel("7d")).toBe("Last 7 days");
    expect(rangeLabel("30d")).toBe("Last 30 days");
  });

  it("rangeLabel devolve o valor cru para entrada fora do contrato", () => {
    expect(rangeLabel("48h" as never)).toBe("48h");
  });

  it("isDashboardRange aceita só o vocabulário do backend", () => {
    expect(isDashboardRange("1h")).toBe(true);
    expect(isDashboardRange("30d")).toBe(true);
    expect(isDashboardRange("")).toBe(false);
    expect(isDashboardRange("90d")).toBe(false);
    expect(isDashboardRange("24H")).toBe(false);
  });

  it("formatWindow mostra a janela meia-aberta EXATA do server", () => {
    expect(
      formatWindow("2026-02-28T12:00:00Z", "2026-03-01T12:00:00Z")
    ).toBe("[2026-02-28T12:00:00Z, 2026-03-01T12:00:00Z)");
  });

  it("formatActivityTime é UTC determinístico (sem timezone da máquina)", () => {
    expect(formatActivityTime("2026-03-01T11:05:00Z")).toBe("Mar 1 11:05");
    expect(formatActivityTime("2026-01-09T00:00:00Z")).toBe("Jan 9 00:00");
    expect(formatActivityTime("não-é-data")).toBe("não-é-data");
  });
});

describe("contagens e textos", () => {
  it("pluralizeCount concorda 1 vs N e aceita plural irregular", () => {
    expect(pluralizeCount(1, "check")).toBe("1 check");
    expect(pluralizeCount(0, "check")).toBe("0 checks");
    expect(pluralizeCount(12, "check")).toBe("12 checks");
    expect(pluralizeCount(1, "service needing attention", "services needing attention")).toBe(
      "1 service needing attention"
    );
  });

  it("activityTitle nunca devolve string vazia (null/branco → '—')", () => {
    expect(activityTitle({ title: "Latency above 500ms" })).toBe(
      "Latency above 500ms"
    );
    expect(activityTitle({ title: null })).toBe("—");
    expect(activityTitle({ title: "   " })).toBe("—");
  });
});

/* -------------------------------------------------------------------------- */
/* KPIs e estado vazio                                                        */
/* -------------------------------------------------------------------------- */

describe("dashboardKpis", () => {
  it("taxas/latência null → '—', nunca 0", () => {
    const kpis = dashboardKpis(EMPTY_DASHBOARD);

    expect(kpis.map((kpi) => kpi.label)).toEqual([
      "Availability",
      "Success rate",
      "Avg latency",
      "Checks",
    ]);
    expect(kpis[0].value).toBe("—");
    expect(kpis[1].value).toBe("—");
    expect(kpis[2].value).toBe("—");
    expect(kpis[3].value).toBe("0");
  });

  it("formata as taxas prontas do backend com 2 casas", () => {
    const kpis = dashboardKpis(
      withData({
        availability: {
          availabilityPercent: 80,
          totalChecks: 25,
          relevantChecks: 20,
          successRatePercent: 95.5,
          avgLatencyMs: 124.6,
          minLatencyMs: 10,
          maxLatencyMs: 900,
        },
      })
    );

    expect(kpis[0].value).toBe("80.00%");
    expect(kpis[1].value).toBe("95.50%");
    expect(kpis[2].value).toBe("125ms");
    expect(kpis[3].value).toBe("25");
  });
});

describe("hasDashboardData (estado vazio)", () => {
  it("zero data → false (UI mostra o estado vazio, não um erro)", () => {
    expect(hasDashboardData(EMPTY_DASHBOARD)).toBe(false);
  });

  it("cada seção sozinha já liga o painel", () => {
    expect(hasDashboardData(withData({ health: { ...EMPTY_DASHBOARD.health, totalServices: 3 } }))).toBe(true);
    expect(
      hasDashboardData(
        withData({ availability: { ...EMPTY_DASHBOARD.availability, totalChecks: 1 } })
      )
    ).toBe(true);
    expect(hasDashboardData(withData({ alerts: { ...EMPTY_DASHBOARD.alerts, firingTotal: 1 } }))).toBe(true);
    expect(
      hasDashboardData(
        withData({ incidents: { ...EMPTY_DASHBOARD.incidents, activeTotal: 1 } })
      )
    ).toBe(true);
    expect(
      hasDashboardData(
        withData({
          servicesNeedingAttention: [
            {
              serviceId: "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
              serviceName: "Checkout API",
              status: "UNAVAILABLE",
              highestSeverity: null,
              hasActiveIncident: false,
              statusChangedAt: null,
            },
          ],
        })
      )
    ).toBe(true);
    expect(
      hasDashboardData(
        withData({
          recentActivity: [
            {
              type: "ALERT_FIRED",
              timestamp: "2026-03-01T11:00:00Z",
              serviceId: "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
              serviceName: "Checkout API",
              severity: "SEV2",
              title: "Latency above 500ms",
              resourceId: "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              resourceType: "ALERT",
            },
          ],
        })
      )
    ).toBe(true);
  });
});
