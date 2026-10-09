import { expect, test } from "@playwright/test";
import {
  VALID_PASSWORD,
  login,
  register,
  uniqueEmail,
} from "../helpers";

test.describe("login", () => {
  test("credenciais válidas entram na home autenticada", async ({ page }) => {
    const user = await register(page, "login-ok");

    await login(page, user);

    await expect(page).toHaveURL("/");
    await expect(page.getByText(/Signed in as/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
    // A seção de organizações só monta com sessão confirmada.
    await expect(
      page.getByRole("heading", { name: "Organizations" })
    ).toBeVisible();
  });

  test("credenciais inválidas exibem mensagem de erro", async ({ page }) => {
    // Conta inexistente — nunca usar credenciais reais.
    await page.goto("/login");
    await page.getByLabel("Email").fill(uniqueEmail("login-bad"));
    await page.getByLabel("Password").fill(VALID_PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();

    // `main` exclui o route-announcer do Next.js, que também usa role="alert".
    const alert = page.locator('main [role="alert"]');
    await expect(alert).toBeVisible();
    await expect(alert).toContainText("Invalid email or password");
    // Continua em /login — sem sessão.
    await expect(page).toHaveURL("/login");
    await expect(page.getByText(/Signed in as/)).toHaveCount(0);
  });
});
