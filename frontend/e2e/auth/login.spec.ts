import { expect, test } from "@playwright/test";
import {
  VALID_PASSWORD,
  login,
  register,
  uniqueEmail,
} from "../helpers";

test.describe("login", () => {
  test("usuário anônimo em '/' é redirecionado para '/login'", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  });
 
  test("usuário autenticado em '/' permanece na home", async ({ page }) => {
    const user = await register(page, "login-stay");
    await login(page, user);
    
    await page.goto("/");
    await expect(page).toHaveURL("/");
    // Marcador estável da sessão autenticada: o perfil "Minha conta".
    await expect(page.getByRole("button", { name: "Minha conta" })).toBeVisible();
  });
 
  test("credenciais válidas entram na home autenticada", async ({ page }) => {
    const user = await register(page, "login-ok");


    await login(page, user);

    await expect(page).toHaveURL("/");
    const account = page.getByRole("button", { name: "Minha conta" });
    await expect(account).toBeVisible();

    // O dropdown expõe os dados reais do usuário e a ação de sair.
    await account.click();
    await expect(page.getByText(user.email)).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign Out" })).toBeVisible();
    // Escape fecha o dropdown (o botão continua com aria-expanded correto).
    await page.keyboard.press("Escape");
    await expect(page.getByRole("button", { name: "Sign Out" })).toHaveCount(0);
    await expect(account).toBeVisible();

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
    await expect(page.getByRole("button", { name: "Minha conta" })).toHaveCount(0);
  });
});
