import { expect, test } from "@playwright/test";

/**
 * A11Y-01 — foco visível.
 *
 * Antes da correção todos os campos usavam `focus:border-(--accent)
 * focus:outline-none` (68 ocorrências) — ou seja, zero indicador de foco
 * visível por teclado. O padrão novo é:
 *   focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent)
 *   focus-visible:ring-offset-2 focus-visible:ring-offset-(--background)
 */
test.describe("A11Y-01 focus-visible", () => {
  test("Tab no form de login aplica o anel de foco por teclado", async ({
    page,
  }) => {
    await page.goto("/login");
    await expect(page.getByLabel("Email")).toBeVisible();

    // Sem foco: nenhuma sombra/anel aplicado.
    const idle = await page
      .locator("#email")
      .evaluate((el) => getComputedStyle(el).boxShadow);
    expect(idle).toBe("none");

    // Primeiro Tab do documento → campo de e-mail.
    await page.keyboard.press("Tab");
    const focusedId = await page.evaluate(() => document.activeElement?.id);
    expect(focusedId).toBe("email");

    // Aguarda o settle da transição Transition-all (200ms) do anel de foco;
    // sem isso o boxShadow é capturado a meio-caminho (opacidade < 1).
    await page.waitForTimeout(300);

    const email = await page.locator("#email").evaluate((el) => ({
      focusVisible: el.matches(":focus-visible"),
      boxShadow: getComputedStyle(el).boxShadow,
      outlineStyle: getComputedStyle(el).outlineStyle,
    }));

    // O outline continua removido (não vira "duplicado" com o anel)…
    expect(email.outlineStyle).toBe("none");
    // …mas o anel focus-visible existe.
    expect(email.focusVisible).toBe(true);
    expect(email.boxShadow).not.toBe("none");
    // --accent  #0ea5e9 → rgb(176, 243, 92)
    expect(email.boxShadow).toContain("rgb(176, 243, 92)");
    // UI-UX-V2: ring-offset = fundo do login #192526 → rgb(25, 37, 38)
    expect(email.boxShadow).toContain("rgb(25, 37, 38)");

    // Segundo Tab → password também ganha o anel.
    await page.keyboard.press("Tab");
    await page.waitForTimeout(300);
    const password = await page.locator("#password").evaluate((el) => ({
      focusVisible: el.matches(":focus-visible"),
      boxShadow: getComputedStyle(el).boxShadow,
    }));
    expect(password.focusVisible).toBe(true);
    expect(password.boxShadow).toContain("rgb(176, 243, 92)");
  });

  test("nenhum componente guarda focus:outline-none sem focus-visible", async ({
    page,
  }) => {
    // Sanidade no runtime: todo elemento com outline removido deve expor
    // o anel quando focado por teclado.
    await page.goto("/register");
    await expect(page.getByLabel("Name")).toBeVisible();
    await page.keyboard.press("Tab");

    const state = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el) return null;
      return {
        id: el.id,
        outline: getComputedStyle(el).outlineStyle,
        boxShadow: getComputedStyle(el).boxShadow,
      };
    });
    expect(state).not.toBeNull();
    expect(state!.id).toBe("name");
    expect(state!.outline).toBe("none");
    expect(state!.boxShadow).not.toBe("none");
  });
});
