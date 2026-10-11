import { defineConfig, devices } from "@playwright/test";

/**
 * Configuração E2E do Sentinel — Etapa Pós-Roadmap 01 (§6), com ajuste de
 * browser para CI na Etapa Pós-Roadmap 02.2 (GitHub Actions).
 *
 * Os servidores já estão de pé fora do Playwright:
 *   - frontend Next.js em http://localhost:3000
 *   - backend Spring Boot em http://localhost:8080
 * Por isso NÃO há `webServer` aqui — o runner só aponta para o `baseURL`
 * (no CI o job `e2e` de `.github/workflows/ci.yml` sobe os dois serviços).
 *
 * Browser — dualidade local × CI:
 *   - LOCAL (`CI` indefinida): `channel: "chrome"` usa o Chrome do sistema.
 *     Os browsers empacotados do Playwright não estão em
 *     `~/.cache/ms-playwright` para esta versão (1.64.0 espera
 *     chromium-1248 / chromium_headless_shell-1248; o cache tem revisões de
 *     versões anteriores) e nenhuma instalação de software no SO é feita.
 *   - CI (`CI=true`, setada pelo GitHub Actions): o step
 *     `npx playwright install --with-deps chromium` do job `e2e` instala o
 *     Chromium versionado pelo próprio Playwright 1.64.0; `channel` fica de
 *     fora para não depender do Chrome do sistema no runner Linux.
 */

const isCI = process.env.CI === "true";

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
      use: {
        ...devices["Desktop Chrome"],
        ...(isCI ? {} : { channel: "chrome" }),
      },
    },
  ],
});
