import { expect, type Page } from "@playwright/test";

/**
 * Utilitários compartilhados da suíte E2E.
 *
 * Regras de segurança da suíte (Etapa Pós-Roadmap 01 §7):
 * - Usuários são SEMPRE descartáveis, criados via `/register` com e-mail único.
 * - NUNCA usar credenciais reais.
 * - NUNCA desabilitar CSRF/CORS/auth/RBAC/SSRF — os testes falam com o backend real.
 */

/** Política de senha do backend (`UserService.PASSWORD_POLICY`). */
export const VALID_PASSWORD = "SentinelE2e123";

/**
 * Senha que viola a política de propósito: 8 caracteres, mas sem dígito.
 * Passa na validação nativa do HTML (`minLength={8}`) e é rejeitada pelo
 * backend com `INVALID_PASSWORD`.
 */
export const WEAK_PASSWORD = "abcdefgh";

export interface TestUser {
  name: string;
  email: string;
  password: string;
}

/** E-mail único por execução — evita colisão entre testes e re-execuções. */
export function uniqueEmail(prefix: string): string {
  const rand = Math.random().toString(36).slice(2, 8);
  return `e2e-${prefix}-${Date.now()}-${rand}@test.local`;
}

/** Nome único para recursos (org/projeto) criados pela suíte. */
export function uniqueName(prefix: string): string {
  return `E2E ${prefix} ${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
}

/** Preenche o formulário de registro e submete; espera o redirect para /login. */
export async function register(
  page: Page,
  prefix = "user"
): Promise<TestUser> {
  const user: TestUser = {
    name: `E2E ${prefix}`,
    email: uniqueEmail(prefix),
    password: VALID_PASSWORD,
  };

  await page.goto("/register");
  await page.getByLabel("Name").fill(user.name);
  await page.getByLabel("Email").fill(user.email);
  await page.getByLabel("Password").fill(user.password);
  await page.getByRole("button", { name: "Create account" }).click();
  await page.waitForURL("**/login");

  return user;
}

/** Faz login e espera a home autenticada ("Signed in as …"). */
export async function login(page: Page, user: TestUser): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(user.email);
  await page.getByLabel("Password").fill(user.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL((url) => url.pathname === "/");
  await expect(page.getByText(/Signed in as/)).toBeVisible();
}

/** Registra um usuário descartável e já entra na sessão. */
export async function registerAndLogin(
  page: Page,
  prefix = "user"
): Promise<TestUser> {
  const user = await register(page, prefix);
  await login(page, user);
  return user;
}

/**
 * Garante que a organização ativa exista na home autenticada.
 *
 * Usuário novo não tem org — cria uma pelo formulário real da UI
 * (`#org-name`). Se a listagem já tem org, apenas garante que a primeira
 * esteja selecionada.
 */
export async function ensureOrganization(
  page: Page,
  prefix = "Org"
): Promise<string> {
  await expect(page.getByRole("heading", { name: "Organizations" })).toBeVisible();

  // Espera o fetch terminar: enquanto carrega aparece "Loading organizations…".
  // Sem essa espera a checagem do estado vazio roda cedo demais e o teste
  // cai no ramo da lista, que ainda não existe.
  const empty = page.getByText("No organizations yet");
  const listed = page.locator("ul button span.font-medium");
  await expect(empty.or(listed)).toBeVisible();

  if (await empty.isVisible()) {
    const name = uniqueName(prefix);
    await page.getByLabel("Organization name").fill(name);
    await page.getByRole("button", { name: "Create", exact: true }).click();
    await expect(listed.filter({ hasText: name })).toBeVisible();
    return name;
  }

  const firstName = await listed.first().textContent();
  expect(firstName, "organização ativa não encontrada na listagem").toBeTruthy();
  return (firstName ?? "").trim();
}
