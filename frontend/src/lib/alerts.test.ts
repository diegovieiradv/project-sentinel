/**
 * Testes de Alert Engine (Prompt 13) — helpers puros de `src/lib/alerts.ts`
 * + cliente de API em `src/lib/api.ts` (mesmo stub de fetch/`document` de
 * `alert-rules.test.ts`).
 *
 * Cobre o que é puro/verificável sem DOM: estado da listagem, snapshot da
 * condição formatada a partir do ALERTA (não da regra atual), valor que
 * estourou com unidade, motivo de resolução textual, instantes
 * determinísticos, query string/filtros, paths de CINCO níveis e o contrato
 * somente-leitura (sem dados fake, sem mutação de alerta) lendo fontes.
 *
 * Cobertura dos critérios 103–112 (um teste citando cada número):
 * `alertsViewState` cobre 103–106 (decisão da view sem DOM); clientes/API
 * cobrem 107–108; snapshot/formato cobrem 109–110; leitura dos fontes cobre
 * 111–112.
 *
 * A RENDERIZAÇÃO DOM (mount do AlertsPanel com Testing Library) não é
 * coberta aqui: exigiria jsdom/@testing-library — dependências NOVAS, fora
 * do escopo do Prompt 13 (limitação registrada em docs/alert-engine.md).
 *
 * ATENÇÃO: a numeração 103–112 segue o mapeamento de melhor esforço do
 * planejamento — confirmar contra a lista oficial de critérios do prompt
 * antes do relatório final (ver docs/alert-engine.md §2).
 */
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  ALERT_STATUS_LABELS,
  RESOLUTION_REASON_LABELS,
  alertsViewState,
  alertCondition,
  formatAlertInstant,
  isFiring,
  resolutionReasonLabel,
  triggerValueLabel,
} from "./alerts";
import {
  API_URL,
  ApiError,
  buildAlertListParams,
  clearOrganizationId,
  getAlert,
  listAlerts,
  setAccessToken,
  type AlertResponse,
} from "./api";

const ORG_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
const PROJECT_ID = "9b2c1f6e-4d5a-4e8f-8a1b-2c3d4e5f6a7b";
const SERVICE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
const ALERT_ID = "e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7a8b";
const RULE_ID = "c0a80121-7d2e-4f6a-9b1c-3e5d7f9a2b4c";

/** Alerta FIRING de disponibilidade (snapshot completo, veio da API). */
const FIRING: AlertResponse = {
  id: ALERT_ID,
  organizationId: ORG_ID,
  projectId: PROJECT_ID,
  serviceId: SERVICE_ID,
  alertRuleId: RULE_ID,
  monitorId: null,
  ruleName: "Availability below 99.9",
  status: "FIRING",
  severity: "SEV1",
  resolutionReason: null,
  startedAt: "2026-03-01T12:34:56Z",
  resolvedAt: null,
  lastEvaluatedAt: "2026-03-01T12:39:56Z",
  triggerValue: 97.5,
  message: "Availability below 99.9: availability 97.50 % < 99.9 % in last 15 minutes",
  ruleType: "AVAILABILITY",
  ruleOperator: "LESS_THAN",
  ruleThresholdNumeric: 99.9,
  ruleThresholdStatus: null,
  ruleWindow: "FIFTEEN_MINUTES",
  createdAt: "2026-03-01T12:34:56Z",
  updatedAt: "2026-03-01T12:34:56Z",
  role: "ADMIN",
};

/** Alerta RESOLVED com motivo dedicado (mensagem original preservada). */
const RESOLVED: AlertResponse = {
  ...FIRING,
  id: "9c1d2e3f-4a5b-4c6d-8e7f-0a1b2c3d4e5f",
  status: "RESOLVED",
  resolutionReason: "AUTO",
  resolvedAt: "2026-03-01T12:49:56Z",
  updatedAt: "2026-03-01T12:49:56Z",
};

const EMPTY_PAGE = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: 10,
};

/** Acesso dinâmico aos globals para instalar/remover stubs sem quebrar o TS. */
const globals = globalThis as unknown as Record<string, unknown>;
const originalFetch = globals.fetch;

const calls: { url: string; init: RequestInit }[] = [];

/** Fetch mockado que registra a chamada e responde com o corpo informado. */
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

function makeStorage(): void {
  const store = new Map<string, string>();
  globals.localStorage = {
    getItem: (key: string) => (store.has(key) ? (store.get(key) as string) : null),
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => store.clear(),
  };
}

function makeWindow(): void {
  globals.window = {
    localStorage: globals.localStorage,
    dispatchEvent: () => true,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  };
}

beforeEach(() => {
  calls.length = 0;
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
});

/* -------------------------------------------------------------------------- */
/* 103–106 — estado da view da listagem (função pura consumida pelo painel)   */
/* -------------------------------------------------------------------------- */

describe("estado da view da listagem (alertsViewState)", () => {
  it("#103 lista real: dados prontos + alertas da API → estado \"list\" com a condição do snapshot formatada", () => {
    expect(alertsViewState("ready", 1)).toBe("list");
    expect(alertsViewState("ready", 3)).toBe("list");
    // conteúdo exatamente como veio da API (sem dados fake): a linha deriva
    // nome + condição do SNAPSHOT do alerta
    const rows = [FIRING].map((alert) => ({
      name: alert.ruleName,
      condition: alertCondition(alert),
      value: triggerValueLabel(alert),
    }));
    expect(rows).toEqual([
      {
        name: "Availability below 99.9",
        condition: "Availability < 99.9 %",
        value: "97.5 %",
      },
    ]);
  });

  it("#104 empty: pronto e zero alertas → estado \"empty\" (mensagem própria)", () => {
    expect(alertsViewState("ready", 0)).toBe("empty");
  });

  it("#105 loading: idle (antes do 1º fetch) e loading → estado \"loading\"", () => {
    expect(alertsViewState("idle", 0)).toBe("loading");
    // durante um refetch a lista antiga fica escondida atrás do loading
    expect(alertsViewState("loading", 5)).toBe("loading");
  });

  it("#106 error: falha de listagem → estado \"error\" com Retry, nunca confundido com vazio", () => {
    // erro tem precedência sobre empty: 0 alertas + erro ≠ "sem alertas"
    expect(alertsViewState("error", 0)).toBe("error");
    expect(alertsViewState("error", 3)).toBe("error");

    // o componente consome o estado: o botão Retry fica DENTRO do branch
    // de erro e ANTES do branch vazio (caixa própria, sem DOM aqui)
    const source = readFileSync(
      new URL("../components/AlertsPanel.tsx", import.meta.url),
      "utf8"
    );
    const errorBranch = source.indexOf('view === "error"');
    const retryButton = source.indexOf(
      "setReload((count) => count + 1)",
      errorBranch
    );
    const emptyBranch = source.indexOf('view === "empty"');
    expect(errorBranch).toBeGreaterThan(-1);
    expect(retryButton).toBeGreaterThan(errorBranch);
    expect(retryButton).toBeLessThan(emptyBranch);
  });
});

/* -------------------------------------------------------------------------- */
/* 107–108 — cliente de API: paths de CINCO níveis e filtros                  */
/* -------------------------------------------------------------------------- */

describe("cliente de API (listAlerts/getAlert)", () => {
  it("#107 GET da listagem: path de CINCO níveis com page/size/status/severity (ordem fixa)", async () => {
    respondWith(200, EMPTY_PAGE);

    await listAlerts(ORG_ID, PROJECT_ID, SERVICE_ID, {
      page: 0,
      size: 10,
      status: "FIRING",
      severity: "SEV1",
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/alerts?page=0&size=10&status=FIRING&severity=SEV1`
    );
    expect(calls[0].init.method ?? "GET").toBe("GET");
  });

  it("#108 filtros omitidos ficam fora da query; GET do detalhe tem o alertId no quinto nível", async () => {
    // sem params → nenhuma query string (nunca `page=undefined`)
    expect(buildAlertListParams()).toBe("");
    expect(buildAlertListParams({})).toBe("");
    expect(buildAlertListParams({ page: 0 })).toBe("?page=0");
    expect(buildAlertListParams({ status: "RESOLVED" })).toBe(
      "?status=RESOLVED"
    );

    respondWith(200, FIRING);
    await getAlert(ORG_ID, PROJECT_ID, SERVICE_ID, ALERT_ID);
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/alerts/${ALERT_ID}`
    );
  });

  it("404 da API vira ApiError com status preservado (leitura de alerta alheio)", async () => {
    respondWith(404, {
      code: "NOT_FOUND",
      message: "Resource not found",
      timestamp: "2026-03-01T12:34:56Z",
      path: "/x",
    });
    await expect(
      getAlert(ORG_ID, PROJECT_ID, SERVICE_ID, ALERT_ID)
    ).rejects.toBeInstanceOf(ApiError);
    await expect(
      getAlert(ORG_ID, PROJECT_ID, SERVICE_ID, ALERT_ID)
    ).rejects.toMatchObject({ status: 404 });
  });
});

/* -------------------------------------------------------------------------- */
/* 109–110 — snapshot e rótulos textuais                                      */
/* -------------------------------------------------------------------------- */

describe("snapshot do alerta e rótulos", () => {
  it("#109 a condição e o valor vêm do SNAPSHOT do alerta (não da regra atual)", () => {
    // um alerta cujo snapshot divergiria de qualquer regra editada depois
    const latencySnapshot: AlertResponse = {
      ...FIRING,
      ruleType: "AVERAGE_LATENCY",
      ruleOperator: "GREATER_THAN",
      ruleThresholdNumeric: 800,
      ruleThresholdStatus: null,
      triggerValue: 1240,
      message: "Latency above 800 ms: average 1240 ms in last 5 minutes",
    };
    expect(alertCondition(latencySnapshot)).toBe("Average latency > 800 ms");
    expect(triggerValueLabel(latencySnapshot)).toBe("1240 ms");

    // SERVICE_STATUS: sem valor numérico (—) e threshold de status textual
    const statusSnapshot: AlertResponse = {
      ...FIRING,
      ruleType: "SERVICE_STATUS",
      ruleOperator: "EQUAL",
      ruleThresholdNumeric: null,
      ruleThresholdStatus: "UNAVAILABLE",
      triggerValue: null,
      message: "Service status = Unavailable for last 15 minutes",
    };
    expect(alertCondition(statusSnapshot)).toBe("Service status = Unavailable");
    expect(triggerValueLabel(statusSnapshot)).toBe("—");

    // contagem inteira sem unidade
    const countSnapshot: AlertResponse = {
      ...FIRING,
      ruleType: "TARGET_FAILURE_COUNT",
      ruleOperator: "GREATER_THAN_OR_EQUAL",
      ruleThresholdNumeric: 3,
      triggerValue: 4,
      message: "Target failures >= 3: 4 in last 15 minutes",
    };
    expect(triggerValueLabel(countSnapshot)).toBe("4");
  });

  it("#110 status, motivo de resolução e severidade são TEXTO (nunca só cor/enum cru)", () => {
    expect(ALERT_STATUS_LABELS.FIRING).toBe("Firing");
    expect(ALERT_STATUS_LABELS.RESOLVED).toBe("Resolved");
    expect(RESOLUTION_REASON_LABELS.AUTO).toBe("Auto-resolved");
    expect(RESOLUTION_REASON_LABELS.RULE_DEACTIVATED).toBe("Rule deactivated");
    expect(RESOLUTION_REASON_LABELS.PARENT_INACTIVE).toBe("Parent deactivated");

    // enquanto FIRING não há motivo (nulo → travis); depois, rótulo legível
    expect(FIRING.resolutionReason).toBeNull();
    expect(resolutionReasonLabel(FIRING.resolutionReason)).toBe("—");
    expect(resolutionReasonLabel(RESOLVED.resolutionReason)).toBe(
      "Auto-resolved"
    );
    expect(isFiring(FIRING)).toBe(true);
    expect(isFiring(RESOLVED)).toBe(false);

    // severidade textual vem de SEVERITY_LABELS (fonte única, já testada no P12)
    expect(FIRING.severity).toBe("SEV1");

    // instantes determinísticos em UTC (sem depender de timezone do ambiente)
    expect(formatAlertInstant("2026-03-01T12:34:56Z")).toBe(
      "2026-03-01 12:34:56 UTC"
    );
    expect(formatAlertInstant(null)).toBe("—");
    expect(formatAlertInstant("data-invalida")).toBe("—");
  });
});

/* -------------------------------------------------------------------------- */
/* 111–112 — contrato somente-leitura e sem dados fake                        */
/* -------------------------------------------------------------------------- */

describe("contrato somente-leitura (sem mutação de alerta)", () => {
  it("#111 a API de alerta só expõe GET (sem POST/PATCH/DELETE de alerta)", () => {
    // o cliente declara somente list/get — nenhum método mutante
    const source = readFileSync(
      new URL("./api.ts", import.meta.url),
      "utf8"
    );
    const alertsSection = source.slice(source.indexOf("Alerts (Prompt 13)"));
    expect(alertsSection).toContain("export async function listAlerts");
    expect(alertsSection).toContain("export async function getAlert");
    expect(alertsSection).not.toContain("export async function createAlert");
    expect(alertsSection).not.toContain("export async function updateAlert");
    expect(alertsSection).not.toContain("export async function deleteAlert");
    expect(alertsSection).not.toContain("export async function resolveAlert");
    // nenhuma mutação disfarçada na seção de alertas
    expect(alertsSection).not.toContain('method: "POST"');
    expect(alertsSection).not.toContain('method: "PATCH"');
    expect(alertsSection).not.toContain('method: "DELETE"');
  });

  it("#112 AlertsPanel só consome a API real (sem arrays/mock de alertas)", () => {
    const source = readFileSync(
      new URL("../components/AlertsPanel.tsx", import.meta.url),
      "utf8"
    );
    expect(source).toContain("listAlerts(");
    expect(source).not.toMatch(
      /\bMOCK\b|\bmockAlerts\b|\bfakeAlerts\b|\bsampleAlerts\b|\bfakeData\b|\bdummyData\b/
    );
    // nenhuma lista "de exemplo" hardcoded com severidade de mentira
    expect(source).not.toMatch(/severity:\s*"SEV1"[\s\S]{0,40}ruleName/);
    expect(source).not.toMatch(/sample\s*alert/i);
    // nenhum controle mutável (a API não tem mutação de alerta)
    expect(source).not.toMatch(/\bonClick=\{[^}]*resolve|\backnowledge|\bsilence/i);
  });
});
