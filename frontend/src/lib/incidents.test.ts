/**
 * Testes de Incidentes (Prompt 14) — helpers puros de `src/lib/incidents.ts`
 * + cliente de API em `src/lib/api.ts` (mesmo stub de fetch/`document` de
 * `alert-rules.test.ts`).
 *
 * Cobre o que é puro/verificável sem DOM: a máquina de estados espelhada do
 * server (4×4), os rótulos (status/evento/severidade), a decisão da view,
 * a linha da timeline, os derivados da lista, a query string e os paths dos
 * NOVE endpoints do sub-recurso — incluindo os dois verbos de associação
 * (`PUT` 200 / `DELETE` 204) e a ausência propositada de DELETE de incidente.
 *
 * A RENDERIZAÇÃO DOM (mount do IncidentsPanel com Testing Library) não é
 * coberta aqui: exigiria jsdom/@testing-library — dependências NOVAS, fora
 * do escopo do Prompt 14 (mesma limitação registrada em docs/alert-engine.md).
 */
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  INCIDENT_EVENT_TYPE_LABELS,
  INCIDENT_SEVERITY_LABELS,
  INCIDENT_SOURCE_LABELS,
  INCIDENT_STATUS_LABELS,
  INCIDENT_STATUS_OPTIONS,
  INCIDENT_TRANSITIONS,
  canManageIncidents,
  canTransition,
  formatIncidentInstant,
  hasIncidentContentChanges,
  incidentActorLabel,
  incidentAuthorLabel,
  incidentEventLine,
  incidentSeverityBadgeClasses,
  incidentSourceBadgeClasses,
  incidentStatusBadgeClasses,
  incidentsViewState,
  isResolved,
  nextStatusOptions,
  openIncidentCount,
} from "./incidents";
import {
  API_URL,
  ApiError,
  buildIncidentListParams,
  changeIncidentStatus,
  clearOrganizationId,
  createIncident,
  getIncident,
  linkIncidentAlert,
  listIncidentAlerts,
  listIncidentEvents,
  listIncidents,
  setAccessToken,
  unlinkIncidentAlert,
  updateIncident,
  type AlertResponse,
  type IncidentEventResponse,
  type IncidentResponse,
} from "./api";

const ORG_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
const PROJECT_ID = "9b2c1f6e-4d5a-4e8f-8a1b-2c3d4e5f6a7b";
const SERVICE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
const INCIDENT_ID = "b7c8d9e0-1f2a-4b3c-8d4e-5f6a7b8c9d0e";
const ALERT_ID = "e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7a8b";
const USER_ID = "c0a80121-7d2e-4f6a-9b1c-3e5d7f9a2b4c";

/** Incidente OPEN completo (shape exato do `IncidentResponse`). */
const OPEN_INCIDENT: IncidentResponse = {
  id: INCIDENT_ID,
  organizationId: ORG_ID,
  projectId: PROJECT_ID,
  serviceId: SERVICE_ID,
  title: "Checkout fora do ar",
  description: "Paginação de 500 no checkout desde as 09:12",
  severity: "SEV1",
  status: "OPEN",
  source: "MANUAL",
  startedAt: "2026-03-01T09:12:00Z",
  resolvedAt: null,
  createdByUserId: USER_ID,
  createdAt: "2026-03-01T09:12:00Z",
  updatedAt: "2026-03-01T09:12:00Z",
  version: 0,
  role: "ADMIN",
};

const RESOLVED_INCIDENT: IncidentResponse = {
  ...OPEN_INCIDENT,
  status: "RESOLVED",
  resolvedAt: "2026-03-01T11:00:00Z",
  version: 4,
  role: "VIEWER",
};

const CREATED_EVENT: IncidentEventResponse = {
  id: "0d1e2f3a-4b5c-4d6e-8f70-8192a3b4c5d6",
  incidentId: INCIDENT_ID,
  type: "CREATED",
  message: "Incident created",
  actorUserId: USER_ID,
  createdAt: "2026-03-01T09:12:00Z",
};

/* -------------------------------------------------------------------------- */
/* Stub de fetch / document (mesmo arranjo dos demais testes de lib)           */
/* -------------------------------------------------------------------------- */

const globals = globalThis as unknown as Record<string, unknown>;
const originalFetch = globals.fetch;

const calls: { url: string; init: RequestInit }[] = [];

/** Fetch mockado que registra a chamada e responde com o corpo informado. */
function respondWith(status: number, body: unknown): void {
  globals.fetch = (input: unknown, init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    // 204/205/304 proíbem corpo no Response constructor (especificação Fetch)
    const hasBody = status !== 204 && status !== 205 && status !== 304;
    return Promise.resolve(
      new Response(hasBody ? JSON.stringify(body) : null, {
        status,
        headers: hasBody ? { "content-type": "application/json" } : {},
      })
    );
  };
}

function bodyOf(): Record<string, unknown> {
  const raw = calls[0].init.body;
  return JSON.parse(String(raw)) as Record<string, unknown>;
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

const BASE = `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/incidents`;

/* -------------------------------------------------------------------------- */
/* Rótulos                                                                     */
/* -------------------------------------------------------------------------- */

describe("rótulos", () => {
  it("status e evento têm rótulo legível para TODOS os membros do enum", () => {
    expect(Object.keys(INCIDENT_STATUS_LABELS)).toEqual([
      "OPEN",
      "INVESTIGATING",
      "IDENTIFIED",
      "RESOLVED",
    ]);
    for (const label of Object.values(INCIDENT_STATUS_LABELS)) {
      expect(label).not.toBe("");
      expect(label).toBe(label.trim());
    }

    expect(Object.keys(INCIDENT_EVENT_TYPE_LABELS)).toEqual([
      "CREATED",
      "STATUS_CHANGED",
      "SEVERITY_CHANGED",
      "TITLE_CHANGED",
      "DESCRIPTION_CHANGED",
      "ALERT_LINKED",
      "ALERT_UNLINKED",
    ]);
    for (const label of Object.values(INCIDENT_EVENT_TYPE_LABELS)) {
      expect(label).not.toBe("");
      expect(label).toBe(label.trim());
    }

    // origem (Prompt 15) — as DUAS entradas do enum, sempre com palavra
    expect(Object.keys(INCIDENT_SOURCE_LABELS)).toEqual([
      "MANUAL",
      "ALERT_AUTOMATION",
    ]);
    for (const label of Object.values(INCIDENT_SOURCE_LABELS)) {
      expect(label).not.toBe("");
      expect(label).toBe(label.trim());
    }
  });

  it("a severidade do incidente reusa os MESMOS rótulos do alerta", () => {
    expect(INCIDENT_SEVERITY_LABELS).toEqual({
      SEV1: "SEV1 — Critical",
      SEV2: "SEV2 — High",
      SEV3: "SEV3 — Moderate",
      SEV4: "SEV4 — Low",
    });
    expect(Object.keys(INCIDENT_SEVERITY_LABELS)).toEqual([
      "SEV1",
      "SEV2",
      "SEV3",
      "SEV4",
    ]);
  });
});

/* -------------------------------------------------------------------------- */
/* Máquina de estados                                                          */
/* -------------------------------------------------------------------------- */

describe("máquina de estados (espelho do server)", () => {
  it("as transições são EXATAMENTE as quatro cadeias do prompt", () => {
    expect(INCIDENT_TRANSITIONS).toEqual({
      OPEN: ["INVESTIGATING", "IDENTIFIED", "RESOLVED"],
      INVESTIGATING: ["IDENTIFIED", "RESOLVED"],
      IDENTIFIED: ["RESOLVED"],
      RESOLVED: [],
    });
  });

  it("canTransition aceita a cadeia e recusa autotransição/retorno", () => {
    // cadeias válidas
    expect(canTransition("OPEN", "INVESTIGATING")).toBe(true);
    expect(canTransition("OPEN", "IDENTIFIED")).toBe(true);
    expect(canTransition("OPEN", "RESOLVED")).toBe(true);
    expect(canTransition("INVESTIGATING", "IDENTIFIED")).toBe(true);
    expect(canTransition("INVESTIGATING", "RESOLVED")).toBe(true);
    expect(canTransition("IDENTIFIED", "RESOLVED")).toBe(true);

    // autotransição → 409 INCIDENT_STATUS_TRANSITION (não é idempotente)
    expect(canTransition("OPEN", "OPEN")).toBe(false);
    expect(canTransition("INVESTIGATING", "INVESTIGATING")).toBe(false);
    expect(canTransition("RESOLVED", "RESOLVED")).toBe(false);

    // pular etapa no meio da cadeia
    expect(canTransition("INVESTIGATING", "OPEN")).toBe(false);
    expect(canTransition("IDENTIFIED", "INVESTIGATING")).toBe(false);
    expect(canTransition("IDENTIFIED", "OPEN")).toBe(false);
  });

  it("RESOLVED é terminal: nenhuma saída, nem para si mesmo", () => {
    for (const target of INCIDENT_STATUS_OPTIONS) {
      expect(canTransition("RESOLVED", target)).toBe(false);
    }
    expect(INCIDENT_TRANSITIONS.RESOLVED).toHaveLength(0);
    expect(isResolved(RESOLVED_INCIDENT)).toBe(true);
    expect(isResolved(OPEN_INCIDENT)).toBe(false);
  });

  it("nextStatusOptions filtra o seletor pela cadeia do estado atual", () => {
    expect(nextStatusOptions("OPEN")).toEqual([
      "INVESTIGATING",
      "IDENTIFIED",
      "RESOLVED",
    ]);
    expect(nextStatusOptions("INVESTIGATING")).toEqual([
      "IDENTIFIED",
      "RESOLVED",
    ]);
    expect(nextStatusOptions("IDENTIFIED")).toEqual(["RESOLVED"]);
    expect(nextStatusOptions("RESOLVED")).toEqual([]);
  });
});

/* -------------------------------------------------------------------------- */
/* RBAC de UX                                                                  */
/* -------------------------------------------------------------------------- */

describe("canManageIncidents", () => {
  it("ADMIN e OPERATOR gerenciam; VIEWER só lê", () => {
    expect(canManageIncidents("ADMIN")).toBe(true);
    expect(canManageIncidents("OPERATOR")).toBe(true);
    expect(canManageIncidents("VIEWER")).toBe(false);
  });
});

/* -------------------------------------------------------------------------- */
/* Timeline                                                                    */
/* -------------------------------------------------------------------------- */

describe("timeline", () => {
  it("formatIncidentInstant devolve UTC determinístico ou —", () => {
    expect(formatIncidentInstant("2026-03-01T09:12:00Z")).toBe(
      "2026-03-01 09:12:00 UTC"
    );
    expect(formatIncidentInstant(null)).toBe("—");
    expect(formatIncidentInstant("não é data")).toBe("—");
  });

  it("incidentEventLine monta data · tipo · frase do server", () => {
    expect(incidentEventLine(CREATED_EVENT)).toBe(
      "2026-03-01 09:12:00 UTC · Incident created · Incident created"
    );
    expect(
      incidentEventLine({
        ...CREATED_EVENT,
        type: "STATUS_CHANGED",
        message: "Status changed from OPEN to INVESTIGATING",
      })
    ).toBe(
      "2026-03-01 09:12:00 UTC · Status changed · Status changed from OPEN to INVESTIGATING"
    );
  });

  it("incidentActorLabel nunca expõe e-mail — só UUID ou —", () => {
    expect(incidentActorLabel(CREATED_EVENT)).toBe(USER_ID);
    expect(incidentActorLabel({ ...CREATED_EVENT, actorUserId: null })).toBe(
      "—"
    );
    expect(incidentActorLabel(CREATED_EVENT)).not.toContain("@");
  });

  it("incidentAuthorLabel: UUID do autor ou — quando automático (nulo)", () => {
    expect(incidentAuthorLabel(OPEN_INCIDENT)).toBe(USER_ID);
    expect(
      incidentAuthorLabel({ ...OPEN_INCIDENT, createdByUserId: null })
    ).toBe("—");
    expect(incidentAuthorLabel(OPEN_INCIDENT)).not.toContain("@");
  });
});

/* -------------------------------------------------------------------------- */
/* Estado da view e derivados                                                  */
/* -------------------------------------------------------------------------- */

describe("decisão de renderização", () => {
  it("error tem precedência sobre empty; loading antes do ready", () => {
    expect(incidentsViewState("error", 0)).toBe("error");
    expect(incidentsViewState("error", 3)).toBe("error");
    expect(incidentsViewState("idle", 0)).toBe("loading");
    expect(incidentsViewState("loading", 0)).toBe("loading");
    expect(incidentsViewState("ready", 0)).toBe("empty");
    expect(incidentsViewState("ready", 1)).toBe("list");
  });

  it("openIncidentCount conta só o que ainda NÃO está resolvido", () => {
    expect(
      openIncidentCount([
        OPEN_INCIDENT,
        { ...OPEN_INCIDENT, status: "INVESTIGATING" },
        RESOLVED_INCIDENT,
      ])
    ).toBe(2);
    expect(openIncidentCount([RESOLVED_INCIDENT])).toBe(0);
    expect(openIncidentCount([])).toBe(0);
  });

  it("hasIncidentContentChanges detecta edição real e trata '' como limpar", () => {
    const draft = {
      title: OPEN_INCIDENT.title,
      description: OPEN_INCIDENT.description ?? "",
      severity: OPEN_INCIDENT.severity,
    };
    expect(hasIncidentContentChanges(OPEN_INCIDENT, draft)).toBe(false);

    expect(
      hasIncidentContentChanges(OPEN_INCIDENT, { ...draft, title: "Outro" })
    ).toBe(true);
    expect(
      hasIncidentContentChanges(OPEN_INCIDENT, { ...draft, severity: "SEV4" })
    ).toBe(true);

    // "" limpa a descrição → igual a null
    expect(
      hasIncidentContentChanges(OPEN_INCIDENT, { ...draft, description: "" })
    ).toBe(true);
    expect(
      hasIncidentContentChanges(
        { ...OPEN_INCIDENT, description: null },
        { ...draft, description: "" }
      )
    ).toBe(false);
  });

  it("badges são distintos por status e por severidade (palavra, não só cor)", () => {
    const statuses = INCIDENT_STATUS_OPTIONS.map(incidentStatusBadgeClasses);
    expect(new Set(statuses).size).toBe(4);
    for (const classes of statuses) expect(classes.trim()).not.toBe("");

    const severities = INCIDENT_STATUS_OPTIONS.length === 4
      ? (["SEV1", "SEV2", "SEV3", "SEV4"] as const).map(
          incidentSeverityBadgeClasses
        )
      : [];
    expect(new Set(severities).size).toBe(4);
  });

  it("badge de origem é distinto por MANUAL × ALERT_AUTOMATION", () => {
    const sources = (["MANUAL", "ALERT_AUTOMATION"] as const).map(
      incidentSourceBadgeClasses
    );
    for (const classes of sources) expect(classes.trim()).not.toBe("");
    expect(new Set(sources).size).toBe(2);
  });
});

/* -------------------------------------------------------------------------- */
/* Query string                                                                */
/* -------------------------------------------------------------------------- */

describe("buildIncidentListParams", () => {
  it("sem params → string vazia; só os informados entram, na ordem fixa", () => {
    expect(buildIncidentListParams()).toBe("");
    expect(buildIncidentListParams({})).toBe("");
    expect(buildIncidentListParams({ page: 0, size: 20 })).toBe(
      "?page=0&size=20"
    );
    expect(
      buildIncidentListParams({
        page: 2,
        size: 50,
        status: "OPEN",
        severity: "SEV1",
      })
    ).toBe("?page=2&size=50&status=OPEN&severity=SEV1");
    expect(buildIncidentListParams({ severity: "SEV2" })).toBe(
      "?severity=SEV2"
    );
  });

  it("undefined é omitido (nunca vira string vazia na query)", () => {
    const qs = buildIncidentListParams({ page: 1, status: undefined });
    expect(qs).toBe("?page=1");
    expect(qs).not.toContain("status");
  });
});

/* -------------------------------------------------------------------------- */
/* Cliente — paths dos NOVE endpoints                                          */
/* -------------------------------------------------------------------------- */

describe("listIncidents", () => {
  it("GET /incidents com a query montada", async () => {
    respondWith(200, {
      content: [OPEN_INCIDENT],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    });
    const page = await listIncidents(ORG_ID, PROJECT_ID, SERVICE_ID, {
      status: "OPEN",
    });
    expect(calls[0].init.method).toBeUndefined();
    expect(calls[0].url).toBe(`${BASE}?status=OPEN`);
    expect(page.content).toHaveLength(1);
    expect(page.content[0].id).toBe(INCIDENT_ID);
  });
});

describe("createIncident", () => {
  it("POST /incidents → 201 e body SÓ com os campos do contrato", async () => {
    respondWith(201, OPEN_INCIDENT);
    await createIncident(ORG_ID, PROJECT_ID, SERVICE_ID, {
      title: "Checkout fora do ar",
      severity: "SEV1",
    });
    expect(calls[0].init.method).toBe("POST");
    expect(calls[0].url).toBe(BASE);
    expect(bodyOf()).toEqual({
      title: "Checkout fora do ar",
      severity: "SEV1",
    });
    for (const forbidden of [
      "id",
      "organizationId",
      "projectId",
      "serviceId",
      "status",
      "source",
      "startedAt",
      "resolvedAt",
      "createdByUserId",
      "version",
      "role",
    ]) {
      expect(bodyOf()).not.toHaveProperty(forbidden);
    }
  });

  it("400 de validação expõe a mensagem de campo do server", async () => {
    respondWith(400, {
      code: "VALIDATION_ERROR",
      message: "title: must not be blank",
      timestamp: "2026-03-01T00:00:00Z",
      path: BASE,
    });
    const failure = await createIncident(ORG_ID, PROJECT_ID, SERVICE_ID, {
      title: "",
      severity: "SEV1",
    }).catch((err: unknown) => err);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(400);
    expect((failure as ApiError).message).toBe("title: must not be blank");
  });
});

describe("getIncident", () => {
  it("GET /incidents/{id} → 200 com a cadeia inteira", async () => {
    respondWith(200, OPEN_INCIDENT);
    const incident = await getIncident(ORG_ID, PROJECT_ID, SERVICE_ID, INCIDENT_ID);
    expect(calls[0].url).toBe(`${BASE}/${INCIDENT_ID}`);
    expect(incident.serviceId).toBe(SERVICE_ID);
    expect(incident.role).toBe("ADMIN");
  });

  it("404 genérico de outro tenant mantém o contrato de mensagem", async () => {
    respondWith(404, {
      code: "NOT_FOUND",
      message: "Resource not found",
      timestamp: "2026-03-01T00:00:00Z",
      path: BASE,
    });
    const failure = await getIncident(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID
    ).catch((err: unknown) => err);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe("NOT_FOUND");
    expect((failure as ApiError).message).toBe("Resource not found");
  });
});

describe("updateIncident", () => {
  it("PATCH /incidents/{id} é parcial: manda só o informado", async () => {
    respondWith(200, OPEN_INCIDENT);
    await updateIncident(ORG_ID, PROJECT_ID, SERVICE_ID, INCIDENT_ID, {
      title: "Renomeado",
    });
    expect(calls[0].init.method).toBe("PATCH");
    expect(calls[0].url).toBe(`${BASE}/${INCIDENT_ID}`);
    expect(bodyOf()).toEqual({ title: "Renomeado" });
    expect(bodyOf()).not.toHaveProperty("status");
    expect(bodyOf()).not.toHaveProperty("version");
  });

  it("409 INCIDENT_RESOLVED (pós-resolução) expõe a mensagem exata", async () => {
    respondWith(409, {
      code: "INCIDENT_RESOLVED",
      message: "Incident is resolved and can no longer be modified",
      timestamp: "2026-03-01T00:00:00Z",
      path: BASE,
    });
    const failure = await updateIncident(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID,
      { title: "tarde demais" }
    ).catch((err: unknown) => err);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe("INCIDENT_RESOLVED");
  });
});

describe("changeIncidentStatus", () => {
  it("PATCH /incidents/{id}/status manda EXATAMENTE {status}", async () => {
    respondWith(200, { ...OPEN_INCIDENT, status: "INVESTIGATING" });
    await changeIncidentStatus(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID,
      "INVESTIGATING"
    );
    expect(calls[0].init.method).toBe("PATCH");
    expect(calls[0].url).toBe(`${BASE}/${INCIDENT_ID}/status`);
    expect(bodyOf()).toEqual({ status: "INVESTIGATING" });
  });

  it("409 INCIDENT_STATUS_TRANSITION carrega a frase from → to", async () => {
    respondWith(409, {
      code: "INCIDENT_STATUS_TRANSITION",
      message: "Cannot transition from RESOLVED to OPEN",
      timestamp: "2026-03-01T00:00:00Z",
      path: `${BASE}/${INCIDENT_ID}/status`,
    });
    const failure = await changeIncidentStatus(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID,
      "OPEN"
    ).catch((err: unknown) => err);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe("INCIDENT_STATUS_TRANSITION");
    expect((failure as ApiError).message).toBe(
      "Cannot transition from RESOLVED to OPEN"
    );
  });
});

describe("listIncidentEvents", () => {
  it("GET /incidents/{id}/events devolve a LISTA (não é página)", async () => {
    respondWith(200, [CREATED_EVENT]);
    const events = await listIncidentEvents(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID
    );
    expect(calls[0].url).toBe(`${BASE}/${INCIDENT_ID}/events`);
    expect(Array.isArray(events)).toBe(true);
    expect(events[0].type).toBe("CREATED");
    expect(events[0]).not.toHaveProperty("updatedAt");
  });
});

describe("listIncidentAlerts", () => {
  it("GET /incidents/{id}/alerts é página Spring de alertas", async () => {
    const alert = {
      id: ALERT_ID,
      message: "Target unreachable 10× in 15 min",
    } as AlertResponse;
    respondWith(200, {
      content: [alert],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    });
    const page = await listIncidentAlerts(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID,
      { page: 0, size: 10 }
    );
    expect(calls[0].url).toBe(`${BASE}/${INCIDENT_ID}/alerts?page=0&size=10`);
    expect(page.totalElements).toBe(1);
  });
});

describe("linkIncidentAlert", () => {
  it("PUT /incidents/{id}/alerts/{alertId} → 200 incidente", async () => {
    respondWith(200, OPEN_INCIDENT);
    const incident = await linkIncidentAlert(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID,
      ALERT_ID
    );
    expect(calls[0].init.method).toBe("PUT");
    expect(calls[0].url).toBe(`${BASE}/${INCIDENT_ID}/alerts/${ALERT_ID}`);
    expect(calls[0].init.body).toBeUndefined();
    expect(incident.id).toBe(INCIDENT_ID);
  });

  it("409 ALERT_ALREADY_LINKED (alerta de outro incidente)", async () => {
    respondWith(409, {
      code: "ALERT_ALREADY_LINKED",
      message: "Alert is already linked to another incident",
      timestamp: "2026-03-01T00:00:00Z",
      path: `${BASE}/${INCIDENT_ID}/alerts/${ALERT_ID}`,
    });
    const failure = await linkIncidentAlert(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID,
      ALERT_ID
    ).catch((err: unknown) => err);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe("ALERT_ALREADY_LINKED");
  });
});

describe("unlinkIncidentAlert", () => {
  it("DELETE da ASSOCIAÇÃO → 204, idempotente, sem corpo", async () => {
    respondWith(204, null);
    await unlinkIncidentAlert(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID,
      ALERT_ID
    );
    expect(calls[0].init.method).toBe("DELETE");
    expect(calls[0].url).toBe(`${BASE}/${INCIDENT_ID}/alerts/${ALERT_ID}`);
    expect(calls[0].init.body).toBeUndefined();
  });
});

/* -------------------------------------------------------------------------- */
/* Fronteira: sem DELETE de incidente, sem DOM/rede em lib/incidents.ts        */
/* -------------------------------------------------------------------------- */

describe("fronteira do prompt", () => {
  it("lib/incidents.ts é puro — sem fetch, document, window ou localStorage", () => {
    const source = readFileSync("src/lib/incidents.ts", "utf8");
    const code = source
      .split("\n")
      .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
      .join("\n");
    expect(code).not.toContain("fetch(");
    expect(code).not.toContain("document.");
    expect(code).not.toContain("window.");
    expect(code).not.toContain("localStorage");
    expect(code).not.toContain("useState");
  });

  it("não existe remoção de incidente: só o DELETE da associação", () => {
    const source = readFileSync("src/lib/api.ts", "utf8");
    // nenhuma função apaga um incidente
    expect(source).not.toMatch(/deleteIncident\s*[:(]/);
    expect(source).not.toMatch(/removeIncident\s*[:(]/);
    // o ÚNICO DELETE deste recurso é o de vinculação e devolve 204
    const unlink = source.slice(source.indexOf("export async function unlinkIncidentAlert"));
    expect(unlink).toContain('method: "DELETE"');
    expect(unlink).toContain("/alerts/");
  });

  it("nenhum cliente de escrita envia escopo ou campos server-owned no body", async () => {
    // escopo (vive no path) + atributos de servidor — em NENHUM body
    const forbidden = [
      "id",
      "organizationId",
      "projectId",
      "serviceId",
      "status",
      "source",
      "resolvedAt",
      "createdByUserId",
      "version",
      "role",
    ];

    // POST (create) — escopo vive SÓ no path
    respondWith(201, OPEN_INCIDENT);
    await createIncident(ORG_ID, PROJECT_ID, SERVICE_ID, {
      title: "Novo",
      severity: "SEV2",
      description: "texto",
      startedAt: "2026-03-01T10:00:00Z",
    });
    const created = bodyOf();
    expect(created).toHaveProperty("title", "Novo");
    expect(created).toHaveProperty("startedAt", "2026-03-01T10:00:00Z");
    for (const key of forbidden) expect(created).not.toHaveProperty(key);

    // PATCH de conteúdo — parcial e sem estado
    calls.length = 0;
    respondWith(200, OPEN_INCIDENT);
    await updateIncident(ORG_ID, PROJECT_ID, SERVICE_ID, INCIDENT_ID, {
      title: "Editado",
      severity: "SEV3",
    });
    const updated = bodyOf();
    expect(Object.keys(updated).sort()).toEqual(["severity", "title"]);
    // startedAt é imutável: nem no PATCH nem aqui (só o POST pode enviá-lo)
    for (const key of [...forbidden, "startedAt"]) {
      expect(updated).not.toHaveProperty(key);
    }

    // PATCH de status — o ÚNICO corpo possível é { status }
    calls.length = 0;
    respondWith(200, { ...OPEN_INCIDENT, status: "RESOLVED" });
    await changeIncidentStatus(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      INCIDENT_ID,
      "RESOLVED"
    );
    expect(Object.keys(bodyOf())).toEqual(["status"]);
  });
});
