import { defineConfig } from "vitest/config";

/**
 * Testes unitários de `src/lib` — puros, sem DOM.
 * `environment: "node"` é proposital: nada aqui precisa de jsdom/testing-library.
 */
export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
