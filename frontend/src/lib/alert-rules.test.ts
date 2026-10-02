/**
 * Testes de Alert Rules (Prompt 12) — helpers puros de `src/lib/alert-rules.ts`
 * + cliente de API em `src/lib/api.ts` (mesmo stub de fetch/`document` de
 * `monitors.test.ts`).
 *
 * Cobre o que é puro/verificável sem DOM: matriz type × operator, campo de
 * threshold por tipo (percentual `%`, latência `ms`, contagem, status SEM
 * input numérico), severidade COM texto, gate de VIEWER, builders de body
 * (PATCH parcial, monitorId explícito), query string, paths de CINCO níveis e
 * o critério "sem dados fake" lendo o fonte do componente.
 *
 * Cobertura dos critérios 99–112 (um teste citando cada número):
 * `alertRulesViewState` cobre 99–102 (estado da view puro, consumido pelo
 * painel); builders/API cobrem 103–105; `thresholdFieldFor` 106–109;
 * `SEVERITY_LABELS` 110; `canManageAlertRules` 111; leitura do fonte 112.
 *
 * A RENDERIZAÇÃO DOM (mount do AlertRulesPanel com Testing Library) não é
 * coberta aqui: exigiria jsdom/@testing-library — dependências NOVAS, fora
 * do escopo do Prompt 12 (limitação registrada em docs/alert-rules.md §12).
 */
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  ALLOWED_OPERATORS,
  SEVERITY_LABELS,
  alertRulesViewState,
  buildCreateAlertRuleRequest,
  buildUpdateAlertRuleRequest,
  canManageAlertRules,
  emptyAlertRuleForm,
  formValuesFromRule,
  parseThresholdInput,
  ruleScopeLabel,
  thresholdFieldFor,
  thresholdSummary,
  usesNumericThreshold,
  validateAlertRuleForm,
} from "./alert-rules";
import {
  API_URL,
  ApiError,
  activateAlertRule,
  buildAlertRuleListParams,
  clearOrganizationId,
  createAlertRule,
  listAlertRules,
  setAccessToken,
  updateAlertRule,
  type AlertRuleResponse,
} from "./api";

const ORG_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
const PROJECT_ID = "9b2c1f6e-4d5a-4e8f-8a1b-2c3d4e5f6a7b";
const SERVICE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
const RULE_ID = "c0a80121-7d2e-4f6a-9b1c-3e5d7f9a2b4c";
const MONITOR_ID = "7d4e2b1a-9c3f-4d6e-8b2a-5f1e0c9d8a7b";

const RULE: AlertRuleResponse = {
  id: RULE_ID,
  organizationId: ORG_ID,
  projectId: PROJECT_ID,
  serviceId: SERVICE_ID,
  monitorId: null,
  name: "Availability below 99.9",
  type: "AVAILABILITY",
  operator: "LESS_THAN",
  thresholdNumeric: 99.9,
  thresholdStatus: null,
  window: "FIFTEEN_MINUTES",
  severity: "SEV1",
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-02T00:00:00Z",
  role: "ADMIN",
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

function bodyOf(index = 0): Record<string, unknown> {
  return JSON.parse(String(calls[index].init.body)) as Record<string, unknown>;
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
/* 99–102 — estado da view da listagem (função pura consumida pelo painel)     */
/* -------------------------------------------------------------------------- */

describe("estado da view da listagem (alertRulesViewState)", () => {
  it("#99 lista real: dados prontos + regras da API → estado \"list\" com a condição formatada", () => {
    expect(alertRulesViewState("ready", 1)).toBe("list");
    expect(alertRulesViewState("ready", 3)).toBe("list");
    // conteúdo exatamente como veio da API (sem dados fake): a linha da
    // tabela deriva nome + condição de cada regra recebida
    const rows = [RULE].map((rule) => ({
      name: rule.name,
      condition: thresholdSummary(rule),
    }));
    expect(rows).toEqual([
      { name: "Availability below 99.9", condition: "Availability < 99.9 %" },
    ]);
  });

  it("#100 empty: pronto e zero regras → estado \"empty\" (mensagem própria)", () => {
    expect(alertRulesViewState("ready", 0)).toBe("empty");
  });

  it("#101 loading: idle (antes do 1º fetch) e loading → estado \"loading\"", () => {
    expect(alertRulesViewState("idle", 0)).toBe("loading");
    // durante um refetch a lista antiga fica escondida atrás do loading
    expect(alertRulesViewState("loading", 5)).toBe("loading");
  });

  it("#102 error: falha de listagem → estado \"error\" com Retry, nunca confundido com vazio", () => {
    // erro tem precedência sobre empty: 0 regras + erro ≠ "sem regras"
    expect(alertRulesViewState("error", 0)).toBe("error");
    expect(alertRulesViewState("error", 3)).toBe("error");

    // o componente consome o estado: o botão Retry fica DENTRO do branch
    // de erro e ANTES do branch vazio (caixa própria, sem DOM aqui)
    const source = readFileSync(
      new URL("../components/AlertRulesPanel.tsx", import.meta.url),
      "utf8"
    );
    const errorBranch = source.indexOf('view === "error"');
    // setReload também aparece nos refresh pós-create/toggle — a ocorrência
    // do Retry é a primeira DEPOIS do branch de erro
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
/* 106–109 — campo de threshold por tipo                                      */
/* -------------------------------------------------------------------------- */

describe("campo de threshold por tipo (formShowsCorrectFieldsPerType)", () => {
  it("#106 cada tipo mapeia para o campo e os operadores da matriz do backend", () => {
    expect(thresholdFieldFor("AVAILABILITY").kind).toBe("percent");
    expect(thresholdFieldFor("SUCCESS_RATE").kind).toBe("percent");
    expect(thresholdFieldFor("AVERAGE_LATENCY").kind).toBe("milliseconds");
    expect(thresholdFieldFor("TARGET_FAILURE_COUNT").kind).toBe("count");
    expect(thresholdFieldFor("SERVICE_STATUS").kind).toBe("status");

    // espelho exato de AlertRuleValidationMatrix.ALLOWED_OPERATORS
    expect(ALLOWED_OPERATORS).toEqual({
      AVAILABILITY: ["LESS_THAN", "LESS_THAN_OR_EQUAL"],
      SUCCESS_RATE: ["LESS_THAN", "LESS_THAN_OR_EQUAL"],
      AVERAGE_LATENCY: ["GREATER_THAN", "GREATER_THAN_OR_EQUAL"],
      TARGET_FAILURE_COUNT: ["GREATER_THAN", "GREATER_THAN_OR_EQUAL", "EQUAL"],
      SERVICE_STATUS: ["EQUAL"],
    });
  });

  it("#107 SERVICE_STATUS esconde o input numérico e oferece select de status", () => {
    const config = thresholdFieldFor("SERVICE_STATUS");
    expect(usesNumericThreshold("SERVICE_STATUS")).toBe(false);
    expect(config.options).toEqual(["DEGRADED", "UNAVAILABLE"]);
    expect(config.placeholder).toBeUndefined();
    expect(parseThresholdInput("", "SERVICE_STATUS").ok).toBe(false);
  });

  it("#108 AVAILABILITY mostra input de percentual com unidade %", () => {
    const config = thresholdFieldFor("AVAILABILITY");
    expect(config.kind).toBe("percent");
    expect(config.unit).toBe("%");
    expect(usesNumericThreshold("AVAILABILITY")).toBe(true);
  });

  it("#109 AVERAGE_LATENCY mostra input em ms", () => {
    const config = thresholdFieldFor("AVERAGE_LATENCY");
    expect(config.kind).toBe("milliseconds");
    expect(config.unit).toBe("ms");
  });

  it("contagem é inteira ≥ 0 e latência > 0 (faixas do backend)", () => {
    expect(parseThresholdInput("3", "TARGET_FAILURE_COUNT")).toEqual({ ok: true, value: 3 });
    expect(parseThresholdInput("3.5", "TARGET_FAILURE_COUNT").ok).toBe(false);
    expect(parseThresholdInput("-1", "TARGET_FAILURE_COUNT").ok).toBe(false);
    expect(parseThresholdInput("800", "AVERAGE_LATENCY")).toEqual({ ok: true, value: 800 });
    expect(parseThresholdInput("0", "AVERAGE_LATENCY").ok).toBe(false);
    expect(parseThresholdInput("100", "AVAILABILITY")).toEqual({ ok: true, value: 100 });
    expect(parseThresholdInput("100.1", "AVAILABILITY").ok).toBe(false);
    expect(parseThresholdInput("99.999", "AVAILABILITY").ok).toBe(false);
    expect(parseThresholdInput("800.12345", "AVERAGE_LATENCY").ok).toBe(false);
  });
});

/* -------------------------------------------------------------------------- */
/* 110 — severidade textual                                                   */
/* -------------------------------------------------------------------------- */

describe("severidade com texto (severityShowsTextNotJustColor)", () => {
  it("#110 cada SEV traz a palavra humana junto do código (texto, não só cor)", () => {
    expect(SEVERITY_LABELS.SEV1).toContain("Critical");
    expect(SEVERITY_LABELS.SEV2).toContain("High");
    expect(SEVERITY_LABELS.SEV3).toContain("Moderate");
    expect(SEVERITY_LABELS.SEV4).toContain("Low");
    // o código continua visível (nunca só cor)
    (Object.keys(SEVERITY_LABELS) as (keyof typeof SEVERITY_LABELS)[]).forEach((sev) => {
      expect(SEVERITY_LABELS[sev]).toContain(sev);
    });
  });
});

/* -------------------------------------------------------------------------- */
/* 111 — VIEWER é somente-leitura                                             */
/* -------------------------------------------------------------------------- */

describe("gate de mutação (viewerSeesNoMutableControls)", () => {
  it("#111 VIEWER não pode gerenciar (sem controles mutáveis); ADMIN e OPERATOR podem", () => {
    expect(canManageAlertRules("VIEWER")).toBe(false);
    expect(canManageAlertRules("ADMIN")).toBe(true);
    expect(canManageAlertRules("OPERATOR")).toBe(true);
  });
});

/* -------------------------------------------------------------------------- */
/* 112 — sem dados fake no componente                                         */
/* -------------------------------------------------------------------------- */

describe("componente sem dados fake (noMockDataInComponent)", () => {
  it("#112 AlertRulesPanel só consome a API real (sem arrays/mock de regras)", () => {
    const source = readFileSync(
      new URL("../components/AlertRulesPanel.tsx", import.meta.url),
      "utf8"
    );
    expect(source).toContain("listAlertRules(");
    expect(source).not.toMatch(/\bMOCK\b|\bmockRules\b|\bfakeRules\b|\bsampleRules\b|\bfakeData\b|\bdummyData\b/);
    // nenhuma regra "de exemplo" hardcoded (objeto com threshold de mentira)
    expect(source).not.toMatch(/availability:\s*99|sample\s*rule/i);
  });
});

/* -------------------------------------------------------------------------- */
/* Validação/ builders do form                                                */
/* -------------------------------------------------------------------------- */

describe("validateAlertRuleForm (espelho da matriz)", () => {
  it("form vazio de defaults é válido quando nome e threshold são preenchidos", () => {
    const form = {
      ...emptyAlertRuleForm(),
      name: "Latency p95",
      thresholdNumeric: "99.9",
    };
    expect(validateAlertRuleForm(form)).toBeNull();
  });

  it("nome obrigatório e operador fora da matriz é recusado", () => {
    const noName = emptyAlertRuleForm();
    expect(validateAlertRuleForm(noName)).toBe("Name is required.");

    const badOperator = { ...emptyAlertRuleForm(), name: "R", operator: "EQUAL" as const };
    expect(validateAlertRuleForm(badOperator)).toBe(
      "Operator EQUAL is not allowed for AVAILABILITY."
    );

    const statusForm = {
      ...emptyAlertRuleForm(),
      name: "R",
      type: "SERVICE_STATUS" as const,
      operator: "EQUAL" as const,
      thresholdStatus: "" as const,
    };
    expect(validateAlertRuleForm(statusForm)).toBe("Select a status threshold.");
  });
});

describe("builders de body", () => {
  it("create omite campos ausentes (monitorId/threshold nunca viram null)", () => {
    const form = {
      ...emptyAlertRuleForm(),
      name: "Availability below 99.9",
      thresholdNumeric: "99.9",
    };
    const body = buildCreateAlertRuleRequest(form);
    expect(body).toEqual({
      name: "Availability below 99.9",
      type: "AVAILABILITY",
      operator: "LESS_THAN",
      window: "FIFTEEN_MINUTES",
      severity: "SEV2",
      thresholdNumeric: 99.9,
    });
    expect(body).not.toHaveProperty("monitorId");
    expect(body).not.toHaveProperty("thresholdStatus");
  });

  it("create de SERVICE_STATUS manda status e NUNCA threshold numérico", () => {
    const form = {
      ...emptyAlertRuleForm(),
      name: "Service degraded",
      type: "SERVICE_STATUS" as const,
      operator: "EQUAL" as const,
      thresholdStatus: "DEGRADED" as const,
    };
    const body = buildCreateAlertRuleRequest(form);
    expect(body?.thresholdStatus).toBe("DEGRADED");
    expect(body).not.toHaveProperty("thresholdNumeric");
  });

  it("create inválido devolve null (não monta payload que o backend recusa)", () => {
    expect(buildCreateAlertRuleRequest(emptyAlertRuleForm())).toBeNull();
    const outOfRange = {
      ...emptyAlertRuleForm(),
      name: "R",
      thresholdNumeric: "120",
    };
    expect(buildCreateAlertRuleRequest(outOfRange)).toBeNull();
  });

  it("update envia a condição completa e monitorId explícito (null limpa o escopo)", () => {
    const form = {
      ...emptyAlertRuleForm(),
      name: "Now monitor-scoped",
      type: "AVERAGE_LATENCY" as const,
      operator: "GREATER_THAN" as const,
      thresholdNumeric: "800",
      monitorId: MONITOR_ID,
    };
    const body = buildUpdateAlertRuleRequest(form);
    expect(body).toEqual({
      name: "Now monitor-scoped",
      type: "AVERAGE_LATENCY",
      operator: "GREATER_THAN",
      window: "FIFTEEN_MINUTES",
      severity: "SEV2",
      monitorId: MONITOR_ID,
      thresholdNumeric: 800,
    });
    expect(body).not.toHaveProperty("thresholdStatus");
  });

  it("update voltando para Service-level manda monitorId: null (limpa de verdade)", () => {
    const form = { ...emptyAlertRuleForm(), name: "R", thresholdNumeric: "99.9" };
    expect(buildUpdateAlertRuleRequest(form)?.monitorId).toBeNull();
  });

  it("formValuesFromRule semeia o form a partir da regra", () => {
    const form = formValuesFromRule(RULE);
    expect(form.name).toBe("Availability below 99.9");
    expect(form.thresholdNumeric).toBe("99.9");
    expect(form.thresholdStatus).toBe("");
    expect(form.monitorId).toBe("");
    expect(form.type).toBe("AVAILABILITY");
  });
});

/* -------------------------------------------------------------------------- */
/* Rótulos de apresentação                                                    */
/* -------------------------------------------------------------------------- */

describe("rótulos de escopo e condição", () => {
  it("escopo: Service-level ou Monitor: {nome}", () => {
    expect(ruleScopeLabel({ monitorId: null }, {})).toBe("Service-level");
    expect(ruleScopeLabel({ monitorId: MONITOR_ID }, { [MONITOR_ID]: "Checkout" })).toBe(
      "Monitor: Checkout"
    );
    // sem nome resolvido → prefixo do id, nunca UUID inteiro
    const fallback = ruleScopeLabel({ monitorId: MONITOR_ID }, {});
    expect(fallback.startsWith("Monitor: ")).toBe(true);
    expect(fallback).not.toContain(MONITOR_ID);
  });

  it("condition summary traz tipo, operador, valor e unidade", () => {
    expect(thresholdSummary(RULE)).toBe("Availability < 99.9 %");
    expect(
      thresholdSummary({ ...RULE, type: "SERVICE_STATUS", operator: "EQUAL", thresholdNumeric: null, thresholdStatus: "UNAVAILABLE" })
    ).toBe("Service status = Unavailable");
  });
});

/* -------------------------------------------------------------------------- */
/* Cliente de API — paths, query e bodies                                     */
/* -------------------------------------------------------------------------- */

describe("buildAlertRuleListParams (query string)", () => {
  it("sem params (ou objeto vazio) → string vazia", () => {
    expect(buildAlertRuleListParams()).toBe("");
    expect(buildAlertRuleListParams({})).toBe("");
  });

  it("omite undefined/string vazia e serializa boolean/enum corretamente", () => {
    expect(
      buildAlertRuleListParams({ page: undefined, name: "", monitorId: "" })
    ).toBe("");
    expect(buildAlertRuleListParams({ active: false })).toBe("?active=false");
    expect(buildAlertRuleListParams({ type: "AVAILABILITY" })).toBe("?type=AVAILABILITY");
    expect(buildAlertRuleListParams({ severity: "SEV1" })).toBe("?severity=SEV1");
  });

  it("ordem fixa: page, size, active, type, severity, monitorId, name, sort", () => {
    expect(
      buildAlertRuleListParams({
        sort: "name,asc",
        name: "critical",
        monitorId: MONITOR_ID,
        severity: "SEV2",
        type: "SUCCESS_RATE",
        active: true,
        size: 10,
        page: 1,
      })
    ).toBe(
      `?page=1&size=10&active=true&type=SUCCESS_RATE&severity=SEV2&monitorId=${MONITOR_ID}&name=critical&sort=name%2Casc`
    );
  });
});

describe("listAlertRules", () => {
  it("GET path de CINCO níveis com a query da listagem", async () => {
    respondWith(200, EMPTY_PAGE);
    const page = await listAlertRules(ORG_ID, PROJECT_ID, SERVICE_ID, {
      page: 0,
      size: 10,
      active: true,
    });
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/alert-rules?page=0&size=10&active=true`
    );
    expect(calls[0].init.method ?? "GET").toBe("GET");
    expect(page.content).toEqual([]);
  });

  it("404 vira ApiError com o code do backend", async () => {
    respondWith(404, {
      code: "NOT_FOUND",
      message: "Resource not found",
      timestamp: "2026-01-01T00:00:00Z",
      path: "/api/v1/x",
    });
    await expect(listAlertRules(ORG_ID, PROJECT_ID, SERVICE_ID)).rejects.toMatchObject({
      name: "ApiError",
      status: 404,
      code: "NOT_FOUND",
    });
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/alert-rules`
    );
  });
});

describe("createAlertRule", () => {
  it("#103 POST com body APENAS de configuração (nunca ids/active/timestamps)", async () => {
    respondWith(201, RULE);
    const created = await createAlertRule(ORG_ID, PROJECT_ID, SERVICE_ID, {
      name: "Availability below 99.9",
      type: "AVAILABILITY",
      operator: "LESS_THAN",
      thresholdNumeric: 99.9,
      window: "FIFTEEN_MINUTES",
      severity: "SEV1",
    });

    expect(calls[0].init.method).toBe("POST");
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/alert-rules`
    );
    const body = bodyOf();
    expect(body).toEqual({
      name: "Availability below 99.9",
      type: "AVAILABILITY",
      operator: "LESS_THAN",
      thresholdNumeric: 99.9,
      window: "FIFTEEN_MINUTES",
      severity: "SEV1",
    });
    for (const forbidden of ["id", "organizationId", "projectId", "serviceId", "active", "createdAt", "updatedAt"]) {
      expect(body).not.toHaveProperty(forbidden);
    }
    expect(created.id).toBe(RULE_ID);
  });

  it("409 ALERT_RULE_NAME_DUPLICATE expõe code e mensagem", async () => {
    respondWith(409, {
      code: "ALERT_RULE_NAME_DUPLICATE",
      message: "Alert rule with this name already exists in this service",
      timestamp: "2026-01-01T00:00:00Z",
      path: "/api/v1/x",
    });
    const failure = await createAlertRule(ORG_ID, PROJECT_ID, SERVICE_ID, {
      name: "dup",
      type: "AVAILABILITY",
      operator: "LESS_THAN",
      thresholdNumeric: 99.9,
      window: "FIVE_MINUTES",
      severity: "SEV2",
    }).catch((err: unknown) => err);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe("ALERT_RULE_NAME_DUPLICATE");
  });
});

describe("updateAlertRule", () => {
  it("#104 PATCH manda só o que foi informado (parcial de verdade)", async () => {
    respondWith(200, RULE);
    await updateAlertRule(ORG_ID, PROJECT_ID, SERVICE_ID, RULE_ID, {
      name: "Renamed rule",
    });
    expect(calls[0].init.method).toBe("PATCH");
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/alert-rules/${RULE_ID}`
    );
    expect(bodyOf()).toEqual({ name: "Renamed rule" });
  });
});

describe("activateAlertRule", () => {
  it("#105 PATCH .../activation com {active} idempotente", async () => {
    respondWith(200, { ...RULE, active: false });
    const updated = await activateAlertRule(ORG_ID, PROJECT_ID, SERVICE_ID, RULE_ID, false);
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/alert-rules/${RULE_ID}/activation`
    );
    expect(calls[0].init.method).toBe("PATCH");
    expect(bodyOf()).toEqual({ active: false });
    expect(updated.active).toBe(false);
  });
});
