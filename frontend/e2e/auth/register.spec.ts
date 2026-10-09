import { expect, test } from "@playwright/test";
import { WEAK_PASSWORD, uniqueEmail } from "../helpers";

test.describe("registro", () => {
  test("registro válido redireciona para o login", async ({ page }) => {
    const email = uniqueEmail("reg-ok");

    await page.goto("/register");
    await page.getByLabel("Name").fill("E2E Register OK");
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill("SentinelE2e123");
    await page.getByRole("button", { name: "Create account" }).click();

    await page.waitForURL("**/login");
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
    // `main` exclui o route-announcer do Next.js, que também usa role="alert".
    await expect(page.locator('main [role="alert"]')).toHaveCount(0);
  });

  test("senha fraca rejeitada pela política do backend", async ({ page }) => {
    await page.goto("/register");
    await page.getByLabel("Name").fill("E2E Register Weak");
    await page.getByLabel("Email").fill(uniqueEmail("reg-weak"));
    // 8 chars (passa no minLength nativo) mas sem dígito → INVALID_PASSWORD.
    await page.getByLabel("Password").fill(WEAK_PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();

    const alert = page.locator('main [role="alert"]');
    await expect(alert).toBeVisible();
    await expect(alert).toContainText(
      "Password must be at least 8 characters and include at least one letter and one digit"
    );
    // Não saiu da tela de registro.
    await expect(page).toHaveURL("/register");
  });
});
