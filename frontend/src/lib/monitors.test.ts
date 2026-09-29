/**
 * Testes do contrato de MONITORS (Prompt 08) — `src/lib/api.ts`.
 *
 * Cobrem o que é puro/verificável sem DOM: montagem da query string
 * (`buildMonitorListParams`), o path de QUATRO níveis
 * (`org/project/service/monitor`), body mínimo de create/update (APENAS os
 * campos editáveis — nunca ids/type/active/timestamps), o PATCH
 * `.../activation` e o tratamento de 400 `MONITOR_TARGET_NOT_ALLOWED`,
 * 409 (MONITOR_NAME_DUPLICATE/PROJECT_INACTIVE/SERVICE_INACTIVE), 403 e do
 * gatilho de sessão (401 vs 403).
 *
 * Ambiente `node` (ver `vitest.config.ts`): sem jsdom. Onde o caminho de
 * produção usa `document`/`window`/`localStorage`, o teste injeta um stub
 * mínimo — mesmo padrão de `api.test.ts`/`projects.test.ts`/`services.test.ts`.
 *
 * A RENDERIZAÇÃO da UI (MonitorsPanel) não é coberta aqui: exigiria
 * jsdom/testing-library, que não é dependência deste projeto (lacuna
 * documentada no relatório desta frente).
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  API_URL,
  ApiError,
  SESSION_EXPIRED_EVENT,
  activateMonitor,
  buildMonitorListParams,
  clearOrganizationId,
  createMonitor,
  getAccessToken,
  getMonitor,
  getOrganizationId,
  listMonitors,
  setAccessToken,
  setOrganizationId,
  updateMonitor,
} from "./api";

const ORG_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
const OTHER_ORG_ID = "3f2b8f0e-1c4d-4a6b-9c8d-0e1f2a3b4c5d";
const PROJECT_ID = "9b2c1f6e-4d5a-4e8f-8a1b-2c3d4e5f6a7b";
const SERVICE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
const MONITOR_ID = "7d4e2b1a-9c3f-4d6e-8b2a-5f1e0c9d8a7b";

const MONITOR = {
  id: MONITOR_ID,
  organizationId: ORG_ID,
  projectId: PROJECT_ID,
  serviceId: SERVICE_ID,
  name: "Health check",
  type: "HTTP",
  url: "https://example.com/health",
  method: "GET",
  intervalSeconds: 60,
  timeoutMillis: 5000,
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
const dispatched: string[] = [];

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

function headersOf(index = 0): Record<string, string> {
  return (calls[index].init.headers ?? {}) as Record<string, string>;
}

function bodyOf(index = 0): Record<string, unknown> {
  return JSON.parse(String(calls[index].init.body)) as Record<string, unknown>;
}

/** localStorage mínimo em memória (mesma semântica de api.test.ts). */
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

/** `window` mínimo: registrador dos eventos disparados pelo apiFetch. */
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
  // Cookie XSRF presente → ensureCsrfToken() não precisa chamar /health.
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

describe("buildMonitorListParams (query string)", () => {
  it("sem params (ou objeto vazio) → string vazia, sem '?'", () => {
    expect(buildMonitorListParams()).toBe("");
    expect(buildMonitorListParams({})).toBe("");
  });

  it("omite campos undefined e strings vazias", () => {
    expect(
      buildMonitorListParams({
        page: undefined,
        size: undefined,
        active: undefined,
        method: undefined,
        name: "",
        sort: "",
      })
    ).toBe("");
    expect(buildMonitorListParams({ page: 3, name: undefined })).toBe("?page=3");
  });

  it("serializa boolean como 'true'/'false' (não '1'/'0')", () => {
    expect(buildMonitorListParams({ active: true })).toBe("?active=true");
    expect(buildMonitorListParams({ active: false })).toBe("?active=false");
  });

  it("method entra como string do enum (sem transformação de case)", () => {
    expect(buildMonitorListParams({ method: "GET" })).toBe("?method=GET");
    expect(buildMonitorListParams({ method: "HEAD" })).toBe("?method=HEAD");
  });

  it("paginação entra mesmo quando page=0", () => {
    expect(buildMonitorListParams({ page: 0, size: 100 })).toBe(
      "?page=0&size=100"
    );
  });

  it("codifica o nome da busca (espaços) e o sort", () => {
    expect(buildMonitorListParams({ name: "Health check" })).toBe(
      "?name=Health+check"
    );
    expect(buildMonitorListParams({ sort: "createdAt,asc" })).toBe(
      "?sort=createdAt%2Casc"
    );
  });

  it("ordem determinística: page, size, active, method, name, sort", () => {
    expect(
      buildMonitorListParams({
        sort: "createdAt,asc",
        name: "health",
        method: "HEAD",
        active: false,
        size: 20,
        page: 2,
      })
    ).toBe(
      "?page=2&size=20&active=false&method=HEAD&name=health&sort=createdAt%2Casc"
    );
  });
});

describe("listMonitors", () => {
  it("path de 4 níveis + query montada e resposta da página Spring", async () => {
    respondWith(200, {
      content: [MONITOR],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 10,
    });

    const page = await listMonitors(ORG_ID, PROJECT_ID, SERVICE_ID, {
      page: 0,
      size: 10,
      active: false,
      method: "HEAD",
      name: "health",
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors?page=0&size=10&active=false&method=HEAD&name=health`
    );
    expect(calls[0].init.method ?? "GET").toBe("GET");
    expect(calls[0].init.credentials).toBe("include");

    const headers = headersOf();
    expect(headers["Authorization"]).toBe("Bearer access-token-em-memoria");
    expect(headers["Accept"]).toBe("application/json");
    // GET não precisa de CSRF.
    expect(headers["X-XSRF-TOKEN"]).toBeUndefined();
    // Path já carrega a org → o header condicional NÃO pode ser injetado.
    expect(headers["X-Organization-Id"]).toBeUndefined();

    expect(page.content).toHaveLength(1);
    expect(page.content[0].id).toBe(MONITOR_ID);
    expect(page.content[0].type).toBe("HTTP");
    expect(page.content[0].method).toBe("GET");
    expect(page.totalElements).toBe(1);
    expect(page.totalPages).toBe(1);
  });

  it("sem params → path sem '?' nem query", async () => {
    respondWith(200, EMPTY_PAGE);

    await listMonitors(ORG_ID, PROJECT_ID, SERVICE_ID);

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors`
    );
  });

  it("NÃO injeta X-Organization-Id mesmo com outra org ativa em memória", async () => {
    setOrganizationId(OTHER_ORG_ID);
    respondWith(200, EMPTY_PAGE);

    await listMonitors(ORG_ID, PROJECT_ID, SERVICE_ID);

    expect(headersOf()["X-Organization-Id"]).toBeUndefined();
  });
});

describe("createMonitor", () => {
  it("POST no path do serviço com body APENAS dos campos editáveis", async () => {
    respondWith(201, MONITOR);

    const created = await createMonitor(ORG_ID, PROJECT_ID, SERVICE_ID, {
      name: "Health check",
      url: "https://example.com/health",
      method: "HEAD",
      intervalSeconds: 120,
      timeoutMillis: 3000,
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors`
    );
    expect(calls[0].init.method).toBe("POST");

    const headers = headersOf();
    expect(headers["Content-Type"]).toBe("application/json");
    expect(headers["X-XSRF-TOKEN"]).toBe("csrf-test-token");
    // Path já carrega a org → header condicional fora.
    expect(headers["X-Organization-Id"]).toBeUndefined();

    const body = bodyOf();
    expect(Object.keys(body).sort()).toEqual([
      "intervalSeconds",
      "method",
      "name",
      "timeoutMillis",
      "url",
    ]);
    expect(body.name).toBe("Health check");
    expect(body.url).toBe("https://example.com/health");
    expect(body.method).toBe("HEAD");
    expect(body.intervalSeconds).toBe(120);
    expect(body.timeoutMillis).toBe(3000);
    // Campos do servidor nunca são enviados no body de criação.
    expect(body).not.toHaveProperty("id");
    expect(body).not.toHaveProperty("organizationId");
    expect(body).not.toHaveProperty("projectId");
    expect(body).not.toHaveProperty("serviceId");
    expect(body).not.toHaveProperty("active");
    expect(body).not.toHaveProperty("type");
    expect(body).not.toHaveProperty("createdAt");
    expect(body).not.toHaveProperty("updatedAt");
    expect(body).not.toHaveProperty("role");

    expect(created.id).toBe(MONITOR_ID);
    expect(created.role).toBe("ADMIN");
  });

  it("opcionais omitidos → body só com {name, url} (defaults são do server)", async () => {
    respondWith(201, MONITOR);

    await createMonitor(ORG_ID, PROJECT_ID, SERVICE_ID, {
      name: "Health check",
      url: "https://example.com/health",
    });

    expect(Object.keys(bodyOf()).sort()).toEqual(["name", "url"]);
  });

  it("400 MONITOR_TARGET_NOT_ALLOWED → ApiError com status/code/message e ZERO eventos", async () => {
    setOrganizationId(ORG_ID);
    respondWith(400, {
      code: "MONITOR_TARGET_NOT_ALLOWED",
      message: "This target is not allowed for security reasons.",
      timestamp: "2026-01-01T00:00:00Z",
      path: `/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors`,
    });

    const err = await createMonitor(ORG_ID, PROJECT_ID, SERVICE_ID, {
      name: "Internal",
      url: "http://169.254.169.254/latest/meta-data",
    }).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(400);
    expect((err as ApiError).code).toBe("MONITOR_TARGET_NOT_ALLOWED");
    expect((err as ApiError).message).toBe(
      "This target is not allowed for security reasons."
    );
    // 400 de segurança não é 401 → nunca dispara sessão expirada.
    expect(dispatched).toEqual([]);
    expect(getAccessToken()).toBe("access-token-em-memoria");
    expect(getOrganizationId()).toBe(ORG_ID);
  });

  it("409 MONITOR_NAME_DUPLICATE expõe a mensagem do servidor e NÃO derruba a sessão", async () => {
    setOrganizationId(ORG_ID);
    respondWith(409, {
      code: "MONITOR_NAME_DUPLICATE",
      message: "Monitor with this name already exists in this service",
      timestamp: "2026-01-01T00:00:00Z",
      path: `/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors`,
    });

    const err = await createMonitor(ORG_ID, PROJECT_ID, SERVICE_ID, {
      name: "Dup",
      url: "https://example.com/health",
    }).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(409);
    expect((err as ApiError).code).toBe("MONITOR_NAME_DUPLICATE");
    expect((err as ApiError).message).toBe(
      "Monitor with this name already exists in this service"
    );
    // Só 401 dispara SESSION_EXPIRED_EVENT.
    expect(dispatched).toEqual([]);
    expect(getAccessToken()).toBe("access-token-em-memoria");
    expect(getOrganizationId()).toBe(ORG_ID);
  });

  it("409 PROJECT_INACTIVE expõe status/code/message (leitura ok, mutação bloqueada)", async () => {
    setOrganizationId(ORG_ID);
    respondWith(409, {
      code: "PROJECT_INACTIVE",
      message: "Cannot create or modify monitors in an inactive project",
      timestamp: "2026-01-01T00:00:00Z",
      path: `/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors`,
    });

    const err = await createMonitor(ORG_ID, PROJECT_ID, SERVICE_ID, {
      name: "New",
      url: "https://example.com/health",
    }).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(409);
    expect((err as ApiError).code).toBe("PROJECT_INACTIVE");
    expect((err as ApiError).message).toBe(
      "Cannot create or modify monitors in an inactive project"
    );
    expect(dispatched).toEqual([]);
    expect(getAccessToken()).toBe("access-token-em-memoria");
    expect(getOrganizationId()).toBe(ORG_ID);
  });
});

describe("getMonitor / updateMonitor / activateMonitor", () => {
  it("GET .../monitors/{monitorId}", async () => {
    respondWith(200, MONITOR);

    const monitor = await getMonitor(ORG_ID, PROJECT_ID, SERVICE_ID, MONITOR_ID);

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors/${MONITOR_ID}`
    );
    expect(calls[0].init.method ?? "GET").toBe("GET");
    expect(headersOf()["X-Organization-Id"]).toBeUndefined();
    expect(monitor.id).toBe(MONITOR_ID);
    expect(monitor.serviceId).toBe(SERVICE_ID);
  });

  it("PATCH parcial envia APENAS os campos informados", async () => {
    respondWith(200, { ...MONITOR, name: "Renamed" });

    await updateMonitor(ORG_ID, PROJECT_ID, SERVICE_ID, MONITOR_ID, {
      name: "Renamed",
    });

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors/${MONITOR_ID}`
    );
    expect(calls[0].init.method).toBe("PATCH");
    expect(headersOf()["X-XSRF-TOKEN"]).toBe("csrf-test-token");
    expect(headersOf()["X-Organization-Id"]).toBeUndefined();
    expect(Object.keys(bodyOf())).toEqual(["name"]);
  });

  it("PATCH .../activation envia {active} idempotente (sem status nem tipo)", async () => {
    respondWith(200, { ...MONITOR, active: false });

    const updated = await activateMonitor(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      MONITOR_ID,
      false
    );

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors/${MONITOR_ID}/activation`
    );
    expect(calls[0].init.method).toBe("PATCH");
    expect(headersOf()["X-XSRF-TOKEN"]).toBe("csrf-test-token");
    expect(headersOf()["X-Organization-Id"]).toBeUndefined();
    expect(bodyOf()).toEqual({ active: false });
    expect(updated.active).toBe(false);
    // O body nunca carrega ciclo de vida além de `active` (nada de execução).
    expect(bodyOf()).not.toHaveProperty("status");
    expect(bodyOf()).not.toHaveProperty("type");
    expect(bodyOf()).not.toHaveProperty("id");
  });

  it("PATCH .../activation true → {active: true}", async () => {
    respondWith(200, { ...MONITOR, active: true });

    await activateMonitor(ORG_ID, PROJECT_ID, SERVICE_ID, MONITOR_ID, true);

    expect(bodyOf()).toEqual({ active: true });
  });

  it("409 SERVICE_INACTIVE na activation vira ApiError SEM evento (pai inativo)", async () => {
    setOrganizationId(ORG_ID);
    respondWith(409, {
      code: "SERVICE_INACTIVE",
      message: "Cannot activate monitors in an inactive service",
      timestamp: "2026-01-01T00:00:00Z",
      path: `/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors/${MONITOR_ID}/activation`,
    });

    const err = await activateMonitor(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      MONITOR_ID,
      true
    ).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(409);
    expect((err as ApiError).code).toBe("SERVICE_INACTIVE");
    expect(dispatched).toEqual([]);
    expect(getAccessToken()).toBe("access-token-em-memoria");
    expect(getOrganizationId()).toBe(ORG_ID);
  });

  it("403 vira ApiError SEM evento de sessão nem logout (token/org preservados)", async () => {
    setOrganizationId(ORG_ID);
    respondWith(403, {
      code: "FORBIDDEN",
      message: "Insufficient role",
      timestamp: "2026-01-01T00:00:00Z",
      path: `/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/services/${SERVICE_ID}/monitors/${MONITOR_ID}/activation`,
    });

    const err = await activateMonitor(
      ORG_ID,
      PROJECT_ID,
      SERVICE_ID,
      MONITOR_ID,
      false
    ).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(403);
    expect((err as ApiError).code).toBe("FORBIDDEN");
    expect((err as ApiError).message).toBe("Insufficient role");
    expect(dispatched).toEqual([]);
    expect(getAccessToken()).toBe("access-token-em-memoria");
    expect(getOrganizationId()).toBe(ORG_ID);
  });

  it("401 dispara SESSION_EXPIRED_EVENT (gatilho de sessão preservado)", async () => {
    setOrganizationId(ORG_ID);
    respondWith(401, { code: "UNAUTHORIZED", message: "expired" });

    const err = await listMonitors(ORG_ID, PROJECT_ID, SERVICE_ID).catch(
      (e: unknown) => e
    );

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect(dispatched).toEqual([SESSION_EXPIRED_EVENT]);
  });
});
