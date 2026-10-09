import { defineConfig, devices } from "@playwright/test";

/**
 * Configuração E2E do Sentinel — Etapa Pós-Roadmap 01 (§6).
 *
 * Os servidores já estão de pé fora do Playwright:
 *   - frontend Next.js dev em http://localhost:3000
 *   - backend Spring Boot em http://localhost:8080
 * Por isso NÃO há `webServer` aqui — o runner só aponta para o `baseURL`.
 *
 * `channel: "chrome"`: os browsers empacotados do Playwright não estão
 * presentes em `~/.cache/ms-playwright` para esta versão (1.64.0 espera
 * chromium-1248 / chromium_headless_shell-1248; o cache tem revisões de
 * versões anteriores). O Chrome do sistema já está instalado e é usado como
 * engine — nenhuma instalação de software no SO é feita por este projeto.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: process.env.CI === "true",
  retries: 1,
  workers: 2,
  reporter: [["list"]],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    navigationTimeout: 30_000,
    actionTimeout: 15_000,
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], channel: "chrome" },
    },
  ],
});
