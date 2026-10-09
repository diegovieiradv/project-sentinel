/**
 * Guardas de duplo submit das páginas de autenticação (FE-02 — Hardening).
 *
 * Lê o fonte das páginas (mesmo padrão de `alerts.test.ts`): o runner é
 * `environment: "node"` (sem jsdom — ver `vitest.config.ts`), então asserimos a
 * ESTRUTURA do `onSubmit` em vez de montar o form. O que está sendo garantido:
 *
 * 1. `event.preventDefault()` vem ANTES da guarda (senão o form navegaria e
 *    recarregaria a página);
 * 2. `if (loading) return;` exista e venha ANTES de `setLoading(true)` e da
 *    chamada de rede — o `setState` só re-renderiza no próximo tick, então sem
 *    a guarda dois cliques/Enter rápidos disparam `login()`/`register()` duas
 *    vezes (o `disabled` do botão sozinho não cobre o clique já em voo).
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

/** Fonte da página de autenticação (`src/app/<page>/page.tsx`). */
function pageSource(page: "login" | "register"): string {
  return readFileSync(
    new URL(`../app/${page}/page.tsx`, import.meta.url),
    "utf8"
  );
}

describe.each(["login", "register"] as const)(
  "FE-02 — guarda de duplo submit na página %s",
  (page) => {
    it("previne reenvio enquanto `loading` (antes do estado e da chamada de rede)", () => {
      const source = pageSource(page);
      const call = page === "login" ? "await login(" : "await register(";

      const preventDefault = source.indexOf("event.preventDefault();");
      const guard = source.indexOf("if (loading) return;");
      // `;` final: a guarda comenta `setLoading(true)` (backticks) — só o
      // statement real tem `);` logo em seguida.
      const setLoadingTrue = source.indexOf("setLoading(true);");
      const submitCall = source.indexOf(call);

      // Os quatro marcos existem exatamente nesta ordem.
      expect(preventDefault).toBeGreaterThan(-1);
      expect(guard).toBeGreaterThan(-1);
      expect(setLoadingTrue).toBeGreaterThan(-1);
      expect(submitCall).toBeGreaterThan(-1);
      expect(preventDefault).toBeLessThan(guard);
      expect(guard).toBeLessThan(setLoadingTrue);
      expect(guard).toBeLessThan(submitCall);
    });

    it("o botão continua `disabled` enquanto carrega (rede de segurança)", () => {
      const source = pageSource(page);
      expect(source).toContain("disabled={loading}");
      expect(source).toContain("{loading ?");
    });
  }
);
