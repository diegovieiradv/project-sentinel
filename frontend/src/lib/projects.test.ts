/**
 * Testes do contrato de PROJETOS (Prompt 06) — `src/lib/api.ts`.
 *
 * Cobrem o que é puro/verificável sem DOM: montagem da query string
 * (`buildProjectListParams`), paths com `{organizationId}`, body mínimo de
 * create/update (APENAS name/description) e o tratamento de 403/409.
 *
 * Ambiente `node` (ver `vitest.config.ts`): sem jsdom. Onde o caminho de
 * produção usa `document`/`window`/`localStorage`, o teste injeta um stub
 * mínimo — mesmo padrão de `api.test.ts`.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  API_URL,
  ApiError,
  buildProjectListParams,
  clearOrganizationId,
  createProject,
  getAccessToken,
  getOrganizationId,
  getProject,
  listProjects,
  me,
  setAccessToken,
  setOrganizationId,
  updateProject,
  updateProjectStatus,
} from "./api";

const ORG_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
const OTHER_ORG_ID = "3f2b8f0e-1c4d-4a6b-9c8d-0e1f2a3b4c5d";
const PROJECT_ID = "9b2c1f6e-4d5a-4e8f-8a1b-2c3d4e5f6a7b";

const PROJECT = {
  id: PROJECT_ID,
  organizationId: ORG_ID,
  name: "Web API",
  description: "Core services",
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

describe("buildProjectListParams (query string)", () => {
  it("sem params (ou objeto vazio) → string vazia, sem '?'", () => {
    expect(buildProjectListParams()).toBe("");
    expect(buildProjectListParams({})).toBe("");
  });

  it("omite campos undefined e strings vazias", () => {
    expect(
      buildProjectListParams({
        page: undefined,
        size: undefined,
        active: undefined,
        name: "",
        sort: "",
      })
    ).toBe("");
    expect(buildProjectListParams({ page: 2, name: undefined })).toBe("?page=2");
  });

  it("serializa boolean como 'true'/'false' (não '1'/'0')", () => {
    expect(buildProjectListParams({ active: true })).toBe("?active=true");
    expect(buildProjectListParams({ active: false })).toBe("?active=false");
  });

  it("paginação entra mesmo quando page=0", () => {
    expect(buildProjectListParams({ page: 0, size: 100 })).toBe(
      "?page=0&size=100"
    );
  });

  it("codifica o nome da busca (espaços) e o sort", () => {
    expect(buildProjectListParams({ name: "My API" })).toBe("?name=My+API");
    expect(buildProjectListParams({ sort: "createdAt,asc" })).toBe(
      "?sort=createdAt%2Casc"
    );
  });

  it("ordem determinística: page, size, active, name, sort", () => {
    expect(
      buildProjectListParams({
        sort: "createdAt,asc",
        name: "web",
        active: false,
        size: 20,
        page: 2,
      })
    ).toBe("?page=2&size=20&active=false&name=web&sort=createdAt%2Casc");
  });
});

describe("listProjects", () => {
  it("path com organizationId + query montada e resposta da página Spring", async () => {
    respondWith(200, {
      content: [PROJECT],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 10,
    });

    const page = await listProjects(ORG_ID, {
      page: 0,
      size: 10,
      active: false,
      name: "web",
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects?page=0&size=10&active=false&name=web`
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
    expect(page.content[0].id).toBe(PROJECT_ID);
    expect(page.totalElements).toBe(1);
    expect(page.totalPages).toBe(1);
  });

  it("sem params → path sem '?' nem query", async () => {
    respondWith(200, EMPTY_PAGE);

    await listProjects(ORG_ID);

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects`
    );
  });
});

describe("createProject", () => {
  it("POST no path da org com body APENAS {name, description}", async () => {
    respondWith(201, PROJECT);

    const created = await createProject(ORG_ID, {
      name: "Web API",
      description: "Core services",
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects`
    );
    expect(calls[0].init.method).toBe("POST");

    const headers = headersOf();
    expect(headers["Content-Type"]).toBe("application/json");
    expect(headers["X-XSRF-TOKEN"]).toBe("csrf-test-token");

    const body = bodyOf();
    expect(Object.keys(body).sort()).toEqual(["description", "name"]);
    expect(body.name).toBe("Web API");
    expect(body.description).toBe("Core services");
    // Campos do servidor nunca são enviados no body de criação.
    expect(body).not.toHaveProperty("id");
    expect(body).not.toHaveProperty("organizationId");
    expect(body).not.toHaveProperty("active");
    expect(body).not.toHaveProperty("createdAt");
    expect(body).not.toHaveProperty("updatedAt");
    expect(body).not.toHaveProperty("role");

    expect(created.id).toBe(PROJECT_ID);
    expect(created.role).toBe("ADMIN");
  });

  it("description não informada → body só com {name}", async () => {
    respondWith(201, { ...PROJECT, description: null });

    await createProject(ORG_ID, { name: "Web API" });

    expect(Object.keys(bodyOf())).toEqual(["name"]);
  });

  it("409 PROJECT_NAME_DUPLICATE expõe a mensagem do servidor e NÃO derruba a sessão", async () => {
    setOrganizationId(ORG_ID);
    respondWith(409, {
      code: "PROJECT_NAME_DUPLICATE",
      message: "Project with this name already exists in this organization",
      timestamp: "2026-01-01T00:00:00Z",
      path: `/api/v1/organizations/${ORG_ID}/projects`,
    });

    const err = await createProject(ORG_ID, { name: "Dup" }).catch(
      (e: unknown) => e
    );

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(409);
    expect((err as ApiError).code).toBe("PROJECT_NAME_DUPLICATE");
    expect((err as ApiError).message).toBe(
      "Project with this name already exists in this organization"
    );
    // Só 401 dispara SESSION_EXPIRED_EVENT.
    expect(dispatched).toEqual([]);
    expect(getAccessToken()).toBe("access-token-em-memoria");
    expect(getOrganizationId()).toBe(ORG_ID);
  });
});

describe("getProject / updateProject / updateProjectStatus", () => {
  it("GET .../projects/{projectId}", async () => {
    respondWith(200, PROJECT);

    const project = await getProject(ORG_ID, PROJECT_ID);

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}`
    );
    expect(calls[0].init.method ?? "GET").toBe("GET");
    expect(project.id).toBe(PROJECT_ID);
  });

  it("PATCH parcial envia APENAS os campos informados", async () => {
    respondWith(200, { ...PROJECT, name: "Renamed" });

    await updateProject(ORG_ID, PROJECT_ID, { name: "Renamed" });

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}`
    );
    expect(calls[0].init.method).toBe("PATCH");
    expect(headersOf()["X-XSRF-TOKEN"]).toBe("csrf-test-token");
    expect(Object.keys(bodyOf())).toEqual(["name"]);
  });

  it("PATCH .../status envia {active} idempotente", async () => {
    respondWith(200, { ...PROJECT, active: false });

    const updated = await updateProjectStatus(ORG_ID, PROJECT_ID, false);

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/status`
    );
    expect(calls[0].init.method).toBe("PATCH");
    expect(bodyOf()).toEqual({ active: false });
    expect(updated.active).toBe(false);
  });

  it("403 vira ApiError SEM evento de sessão nem logout (token/org preservados)", async () => {
    setOrganizationId(ORG_ID);
    respondWith(403, {
      code: "FORBIDDEN",
      message: "Insufficient role",
      timestamp: "2026-01-01T00:00:00Z",
      path: `/api/v1/organizations/${ORG_ID}/projects/${PROJECT_ID}/status`,
    });

    const err = await updateProjectStatus(ORG_ID, PROJECT_ID, false).catch(
      (e: unknown) => e
    );

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(403);
    expect((err as ApiError).code).toBe("FORBIDDEN");
    expect((err as ApiError).message).toBe("Insufficient role");
    expect(dispatched).toEqual([]);
    expect(getAccessToken()).toBe("access-token-em-memoria");
    expect(getOrganizationId()).toBe(ORG_ID);
  });
});

describe("X-Organization-Id condicional (preservação do apiFetch)", () => {
  it("é injetado quando o path NÃO carrega a org (ex.: /auth/me)", async () => {
    setOrganizationId(ORG_ID);
    respondWith(200, { id: "user-1", name: "Ana", email: "ana@example.com" });

    await me();

    expect(calls[0].url).toBe(`${API_URL}/api/v1/auth/me`);
    expect(headersOf()["X-Organization-Id"]).toBe(ORG_ID);
  });

  it("NÃO é injetado quando o path já carrega outra org (não conflita com o TenantInterceptor)", async () => {
    setOrganizationId(ORG_ID);
    respondWith(200, EMPTY_PAGE);

    await listProjects(OTHER_ORG_ID);

    expect(calls[0].url).toBe(
      `${API_URL}/api/v1/organizations/${OTHER_ORG_ID}/projects`
    );
    expect(headersOf()["X-Organization-Id"]).toBeUndefined();
  });
});
