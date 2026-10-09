import { expect, test } from "@playwright/test";
import { ensureOrganization, registerAndLogin, uniqueName } from "../helpers";

/**
 * §8 — Seleção de organização.
 *
 * A home só monta `MembersPanel`/`ProjectsPanel` quando há `activeOrg`;
 * os títulos são `Members — {org.name}` e `Projects — {org.name}`.
 * Criar uma segunda org NÃO troca a ativa (só `handleSelectOrg` faz isso).
 */
test.describe("organização", () => {
  test("selecionar organização mostra Members e Projects", async ({
    page,
  }) => {
    await registerAndLogin(page, "org-select");

    const org1 = await ensureOrganization(page, "Alpha");
    await expect(
      page.getByRole("heading", { name: `Members — ${org1}` })
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: `Projects — ${org1}` })
    ).toBeVisible();

    // Criar uma segunda org não troca a ativa.
    const org2 = uniqueName("Beta");
    await page.getByLabel("Organization name").fill(org2);
    await page.getByRole("button", { name: "Create", exact: true }).click();
    await expect(page.getByRole("button", { name: org2 })).toBeVisible();
    await expect(
      page.getByRole("heading", { name: `Members — ${org1}` })
    ).toBeVisible();

    // Trocar para a segunda org re-renderiza os painéis com ela.
    await page.getByRole("button", { name: org2 }).click();
    await expect(
      page.getByRole("heading", { name: `Members — ${org2}` })
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: `Projects — ${org2}` })
    ).toBeVisible();

    // A org original continua listada (só o ativo mudou).
    await expect(page.getByRole("button", { name: org1 })).toBeVisible();
  });
});
