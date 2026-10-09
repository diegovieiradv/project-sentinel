/**
 * Testes unitários do cliente de API (`src/lib/api.ts`) — só as partes puras e
 * críticas: validação de formato UUID da org ativa, ciclo de vida da org ativa
 * (memória + localStorage), o gatilho de sessão do `apiFetch` (401 vs 403) e o
 * timeout de requisição (FE-01).
 *
 * Ambiente `node` (ver `vitest.config.ts`): sem jsdom. Onde o caminho de
 * produção exige `window`/`localStorage`, o teste injeta um stub mínimo.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  REQUEST_TIMEOUT_MESSAGE,
  REQUEST_TIMEOUT_MS,
  SESSION_EXPIRED_EVENT,
  checkHealth,
  clearOrganizationId,
  ensureCsrfToken,
  getAccessToken,
  getOrganizationId,
  isValidOrganizationId,
  setAccessToken,
  setOrganizationId,
  me,
} from "./api";

/** Chave persistida no localStorage (espelha a constante privada do módulo). */
const ACTIVE_ORG_KEY = "sentinel.activeOrganizationId";
const VALID_UUID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
const OTHER_UUID = "3f2b8f0e-1c4d-4a6b-9c8d-0e1f2a3b4c5d";

/** Acesso dinâmico aos globals para instalar/remover stubs sem quebrar o TS. */
const globals = globalThis as unknown as Record<string, unknown>;
const originalFetch = globals.fetch;

/** localStorage mínimo em memória (mesma semântica de getItem/setItem/removeItem). */
function makeStorage(seed: Record<string, string> = {}): Map<string, string> {
  const store = new Map<string, string>(Object.entries(seed));
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
  return store;
}

/** `window` mínimo: storage + registrador de eventos disparados pelo apiFetch. */
function makeWindow(dispatched: string[]): void {
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

function stubFetch(status: number, body: unknown): void {
  globals.fetch = () =>
    Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { "content-type": "application/json" },
      })
    );
}

describe("isValidOrganizationId", () => {
  it("aceita UUIDs canônicos (8-4-4-4-12 hex), em qualquer versão", () => {
    expect(isValidOrganizationId(VALID_UUID)).toBe(true);
    expect(isValidOrganizationId(OTHER_UUID)).toBe(true);
    expect(isValidOrganizationId("00000000-0000-0000-0000-000000000000")).toBe(
      true
    );
    expect(isValidOrganizationId("6F9619FF-8B86-4D01-B42D-00CF4FC964FF")).toBe(
      true
    );
  });

  it("rejeita valores malformados", () => {
    const malformed = [
      "",
      " ",
      "not-a-uuid",
      "null",
      "undefined",
      "6f9619ff8b864d01b42d00cf4fc964ff", // sem hífens
      "6f9619ff-8b86-4d01-b42d-00cf4fc964f", // curto
      "6f9619ff-8b86-4d01-b42d-00cf4fc964ff0", // longo
      "6f9619ff-8b86-4d01-b42d-00cf4fc964fg", // não-hex
      ` ${VALID_UUID} `, // com espaços
      `{${VALID_UUID}}`, // com chaves
      "../..",
      `<script>${VALID_UUID}</script>`,
    ];
    for (const value of malformed) {
      expect(isValidOrganizationId(value)).toBe(false);
    }
  });

  it("rejeita entradas não-string (defesa fora do TypeScript)", () => {
    const notStrings = [null, undefined, 123, true, {}, [], 0];
    for (const value of notStrings) {
      expect(isValidOrganizationId(value)).toBe(false);
    }
  });
});

describe("org ativa em memória (sem window)", () => {
  beforeEach(() => {
    clearOrganizationId();
  });

  afterEach(() => {
    clearOrganizationId();
  });

  it("roda sem window — o caminho testado não depende de navegador", () => {
    expect(typeof window).toBe("undefined");
    expect(getOrganizationId()).toBeNull();
    expect(() => getOrganizationId()).not.toThrow();
  });

  it("guarda e devolve um UUID válido", () => {
    setOrganizationId(VALID_UUID);
    expect(getOrganizationId()).toBe(VALID_UUID);
  });

  it("rejeita UUID malformado (nunca entra na memória)", () => {
    setOrganizationId("not-a-uuid");
    expect(getOrganizationId()).toBeNull();
  });

  it("setOrganizationId(null) e clearOrganizationId() limpam o estado", () => {
    setOrganizationId(VALID_UUID);
    setOrganizationId(null);
    expect(getOrganizationId()).toBeNull();

    setOrganizationId(OTHER_UUID);
    clearOrganizationId();
    expect(getOrganizationId()).toBeNull();
  });

  it("setOrganizationId malformado equivale a limpar (não deixa lixo anterior)", () => {
    setOrganizationId(VALID_UUID);
    setOrganizationId("corrupted-value");
    expect(getOrganizationId()).toBeNull();
  });
});

describe("org ativa com localStorage (stub mínimo)", () => {
  const dispatched: string[] = [];

  beforeEach(() => {
    clearOrganizationId();
    makeStorage();
    makeWindow(dispatched);
    dispatched.length = 0;
  });

  afterEach(() => {
    clearOrganizationId();
    delete globals.window;
    delete globals.localStorage;
    globals.fetch = originalFetch;
    dispatched.length = 0;
  });

  it("persiste um UUID válido no storage e o remove no clear", () => {
    const store = makeStorage();
    makeWindow(dispatched);

    setOrganizationId(VALID_UUID);
    expect(store.get(ACTIVE_ORG_KEY)).toBe(VALID_UUID);
    expect(getOrganizationId()).toBe(VALID_UUID);

    clearOrganizationId();
    expect(store.has(ACTIVE_ORG_KEY)).toBe(false);
    expect(getOrganizationId()).toBeNull();
  });

  it("lê UUID válido já persistido pelo usuário", () => {
    makeStorage({ [ACTIVE_ORG_KEY]: OTHER_UUID });
    makeWindow(dispatched);
    expect(getOrganizationId()).toBe(OTHER_UUID);
  });

  it("valor malformado no storage é tratado como ausente e REMOVIDO", () => {
    const store = makeStorage({ [ACTIVE_ORG_KEY]: "evil-value" });
    makeWindow(dispatched);
    expect(store.has(ACTIVE_ORG_KEY)).toBe(true);
    expect(getOrganizationId()).toBeNull();
    expect(store.has(ACTIVE_ORG_KEY)).toBe(false);
  });
});

describe("gatilho de sessão do apiFetch (401 vs 403)", () => {
  const dispatched: string[] = [];

  beforeEach(() => {
    clearOrganizationId();
    makeStorage({ [ACTIVE_ORG_KEY]: VALID_UUID });
    makeWindow(dispatched);
    dispatched.length = 0;
    setAccessToken("access-token-em-memoria");
  });

  afterEach(() => {
    clearOrganizationId();
    setAccessToken(null);
    delete globals.window;
    delete globals.localStorage;
    globals.fetch = originalFetch;
    dispatched.length = 0;
  });

  it("401 dispara SESSION_EXPIRED_EVENT (o ouvinte limpa a sessão)", async () => {
    stubFetch(401, { code: "UNAUTHORIZED", message: "expired" });

    const err = await me().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect(dispatched).toEqual([SESSION_EXPIRED_EVENT]);
  });

  it("403 NÃO dispara evento nem limpa token/org — só 401 derruba a sessão", async () => {
    stubFetch(403, { code: "FORBIDDEN", message: "admin only" });

    const err = await me().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(403);
    expect(dispatched).toEqual([]);
    expect(getAccessToken()).toBe("access-token-em-memoria");
    expect(getOrganizationId()).toBe(VALID_UUID);
  });
});

describe("timeout de requisição (FE-01)", () => {
  /** Último `RequestInit` visto pelo `fetch` (para inspecionar o `signal`). */
  let captured: RequestInit | undefined;

  /** Resposta 200 imediata que registra o `init` recebido. */
  function stubRecordingFetch(status: number, body: unknown): void {
    globals.fetch = (_input: unknown, init?: RequestInit) => {
      captured = init;
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { "content-type": "application/json" },
        })
      );
    };
  }

  /**
   * fetch que NUNCA resolve sozinho — só quando o `signal` aborta (uma API
   * pendurada: exatamente o cenário do FE-01).
   */
  function stubHangingFetch(): void {
    globals.fetch = (_input: unknown, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () =>
          reject(new DOMException("This operation was aborted", "AbortError"))
        );
      });
  }

  beforeEach(() => {
    captured = undefined;
    clearOrganizationId();
  });

  afterEach(() => {
    clearOrganizationId();
    vi.useRealTimers();
    delete globals.document;
    globals.fetch = originalFetch;
    captured = undefined;
  });

  it("toda chamada sai com signal armado e o timer é cancelado no fim", async () => {
    vi.useFakeTimers();
    stubRecordingFetch(200, {
      id: VALID_UUID,
      name: "Ada",
      email: "ada@example.com",
    });

    const user = await me();

    expect(user.email).toBe("ada@example.com");
    expect(captured?.signal).toBeInstanceOf(AbortSignal);
    expect(captured?.signal?.aborted).toBe(false);
    // cancelado no `finally` — nenhum timer de 15s sobrevive à chamada
    expect(vi.getTimerCount()).toBe(0);
  });

  it("API pendurada aborta e vira ApiError 408 REQUEST_TIMEOUT", async () => {
    vi.useFakeTimers();
    stubHangingFetch();

    const pending = me().catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS);
    const err = await pending;

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(408);
    expect((err as ApiError).code).toBe("REQUEST_TIMEOUT");
    expect((err as ApiError).message).toBe(REQUEST_TIMEOUT_MESSAGE);
    expect((err as ApiError).path).toBe("/api/v1/auth/me");
    // nenhum timer órfão depois do aborto
    expect(vi.getTimerCount()).toBe(0);
  });

  it("checkHealth com API pendurada → unreachable com a mensagem canônica", async () => {
    vi.useFakeTimers();
    stubHangingFetch();

    const pending = checkHealth();
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS);

    expect(await pending).toEqual({
      status: "unreachable",
      error: REQUEST_TIMEOUT_MESSAGE,
    });
    expect(vi.getTimerCount()).toBe(0);
  });

  it("ensureCsrfToken (materialização do XSRF) também sai com signal", async () => {
    // `document` mínimo: sem cookie XSRF-TOKEN (força o GET /health do fluxo).
    globals.document = { cookie: "" };
    stubRecordingFetch(200, { status: "UP" });

    const err = await ensureCsrfToken().catch((e: unknown) => e);

    // O stub não emite o cookie — o erro ORIGINAL é preservado (não vira 408).
    expect(err).toBeInstanceOf(Error);
    expect((err as Error).message).toBe(
      "XSRF-TOKEN cookie not found after health check"
    );
    expect(captured?.signal).toBeInstanceOf(AbortSignal);
  });
});
