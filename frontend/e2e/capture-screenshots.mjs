/**
 * Captura as evidências visuais da Etapa Pós-Roadmap 01 (§10).
 *
 * Uso (servidores de dev já de pé em :3000 e :8080):
 *   node e2e/capture-screenshots.mjs
 *
 * Os PNGs vão para `e2e/screenshots/` e são versionados como artefato de
 * revisão. Usos/órgãos/projetos são sempre descartáveis (`*-screenshot-*`).
 */
import { chromium } from "playwright-core";
import { mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), "screenshots");
const PASSWORD = "SentinelE2e123";

const stamp = () => Date.now().toString(36);
const email = (tag) => `e2e-shot-${tag}-${stamp()}@test.local`;

async function register(page, tag) {
  const mail = email(tag);
  await page.goto(new URL("/register", BASE_URL).href);
  await page.getByLabel("Name").fill(`E2E Shot ${tag}`);
  await page.getByLabel("Email").fill(mail);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await page.waitForURL("**/login");
  return mail;
}

async function login(page, mail) {
  await page.goto(new URL("/login", BASE_URL).href);
  await page.getByLabel("Email").fill(mail);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL((url) => url.pathname === "/");
  await page.getByRole("button", { name: "Minha conta" }).waitFor();
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  const browser = await chromium.launch({ channel: "chrome" });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const shot = (name) => page.screenshot({ path: join(OUT_DIR, name), fullPage: true });

  // ── A11Y-01: anel de foco por teclado ──────────────────────────────────
  await page.goto(new URL("/login", BASE_URL).href);
  await page.getByLabel("Email").waitFor();
  await shot("01-login-no-focus.png");
  await page.keyboard.press("Tab");
  await page.waitForTimeout(150);
  await shot("02-login-focus-visible-email.png");

  // ── Erro de login (role="alert") ───────────────────────────────────────
  await page.getByLabel("Email").fill(email("shot-bad"));
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.locator('main [role="alert"]').waitFor();
  await shot("03-login-error-alert.png");

  // ── Registro rejeitado pela política de senha ──────────────────────────
  await page.goto(new URL("/register", BASE_URL).href);
  await page.getByLabel("Name").fill("E2E Shot Weak");
  await page.getByLabel("Email").fill(email("shot-weak"));
  await page.getByLabel("Password").fill("abcdefgh");
  await page.getByRole("button", { name: "Create account" }).click();
  await page.locator('main [role="alert"]').waitFor();
  await shot("04-register-weak-password-alert.png");

  // ── Home autenticada com a primeira organização ────────────────────────
  const mail = await register(page, "shot-home");
  await login(page, mail);
  await page.getByRole("heading", { name: "Organizations" }).waitFor();
  await page.getByText("No organizations yet").waitFor();
  const org1 = `E2E Alpha ${stamp()}`;
  await page.getByLabel("Organization name").fill(org1);
  await page.getByRole("button", { name: "Create", exact: true }).click();
  await page.getByRole("heading", { name: `Members — ${org1}` }).waitFor();
  await shot("05-home-organization-selected.png");

  // ── Troca de organização ───────────────────────────────────────────────
  const org2 = `E2E Beta ${stamp()}`;
  await page.getByLabel("Organization name").fill(org2);
  await page.getByRole("button", { name: "Create", exact: true }).click();
  await page.getByRole("button", { name: org2 }).waitFor();
  await page.getByRole("button", { name: org2 }).click();
  await page.getByRole("heading", { name: `Members — ${org2}` }).waitFor();
  await shot("06-home-organization-switched.png");

  // ── Projeto expandido: Dashboard + Services ────────────────────────────
  const project = `E2E Shot ${stamp()}`;
  await page.getByLabel("Project name").fill(project);
  await page.getByRole("button", { name: "Create project" }).click();
  const toggle = page.locator("main button[aria-expanded]");
  await toggle.waitFor();
  await toggle.first().click();
  await page.getByRole("heading", { name: "Dashboard" }).waitFor();
  await page.getByRole("heading", { name: `Services — ${project}` }).waitFor();
  await shot("07-project-expanded-dashboard-services.png");

  // ── Mobile 375px (sem overflow horizontal) ─────────────────────────────
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(new URL("/login", BASE_URL).href);
  await page.getByLabel("Email").waitFor();
  await shot("08-login-mobile-375.png");

  await browser.close();
  console.log(`Evidências gravadas em ${OUT_DIR}`);
}

await main();
