import { expect, test } from "@playwright/test";
import { login, register } from "../helpers";

test.describe("logout", () => {
  test("Sign out redireciona para login", async ({ page }) => {
    const user = await register(page, "logout");
    await login(page, user);

    // Perfil único "Minha conta" — dropdown com nome e e-mail reais.
    const account = page.getByRole("button", { name: "Minha conta" });
    await expect(account).toBeVisible();
    await account.click();
    await expect(page.getByText(user.name)).toBeVisible();
    await expect(page.getByText(user.email)).toBeVisible();

    await page.getByRole("button", { name: "Sign Out" }).click();

    // Após logout, deve ser redirecionado para /login
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
    // O perfil não existe mais fora de sessão autenticada.
    await expect(account).toHaveCount(0);

    // Recarrega a página: deve permanecer em /login (sessão realmente limpa)
    await page.reload();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
    await expect(account).toHaveCount(0);
  });
});
