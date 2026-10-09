import { expect, test } from "@playwright/test";
import { ensureOrganization, registerAndLogin, uniqueName } from "../helpers";

/**
 * §9 — Expandir projeto.
 *
 * O botão `Details` (`aria-expanded`) monta inline o `DashboardPanel`
 * (heading "Dashboard") e o `ServicesPanel`
 * (heading `Services — {project.name}`).
 */
test.describe("projeto", () => {
  test("expandir Details mostra Dashboard e Services", async ({ page }) => {
    await registerAndLogin(page, "proj-expand");
    await ensureOrganization(page, "Org");

    const projectName = uniqueName("Project");
    await page.getByLabel("Project name").fill(projectName);
    await page.getByRole("button", { name: "Create project" }).click();

    // O label do botão vira "Close" quando expandido — seleção estável é o
    // próprio `aria-expanded`. `main` isola a página de produto do botão
    // aria-expanded da toolbar de dev do Next.js (fora do conteúdo).
    const toggles = page.locator("main button[aria-expanded]");
    await expect(toggles).toHaveCount(1);
    const details = toggles.first();
    await expect(details).toHaveAttribute("aria-expanded", "false");
    await details.click();
    await expect(details).toHaveAttribute("aria-expanded", "true");

    await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
    await expect(
      page.getByRole("heading", { name: `Services — ${projectName}` })
    ).toBeVisible();

    // Recolher: os painéis desmontam junto com a linha de detalhe.
    await details.click();
    await expect(
      page.getByRole("heading", { name: `Services — ${projectName}` })
    ).toHaveCount(0);
  });
});
