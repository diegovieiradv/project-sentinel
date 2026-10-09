import { expect, test } from "@playwright/test";
import { login, register } from "../helpers";

test.describe("logout", () => {
  test("Sign out devolve a home ao estado anônimo", async ({ page }) => {
    const user = await register(page, "logout");
    await login(page, user);

    await expect(page.getByText(/Signed in as/)).toBeVisible();

    await page.getByRole("button", { name: "Sign out" }).click();

    // Estado anônimo imediato + a seção de orgs desmonta junto com a sessão.
    await expect(page.getByText("Signed out")).toBeVisible();
    await expect(page.getByText(/Signed in as/)).toHaveCount(0);
    await expect(
      page.getByText("Sign in to create organizations and manage their members.")
    ).toBeVisible();

    // Recarrega a página: sem sessão, o bootstrap cai em anônimo de novo
    // (o refresh token foi revogado/limpo no logout).
    await page.reload();
    await expect(page.getByText("Signed out")).toBeVisible();
  });
});
