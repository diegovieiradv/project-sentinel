import { expect, test } from "@playwright/test";

/**
 * A11Y-02 — alvos de toque ≥ 44px (WCAG 2.5.5 / 2.5.8).
 *
 * Mede a boundingBox real de todo elemento interativo dentro do `<main>`
 * (o conteúdo da aplicação — a toolbar de dev do Next fica fora do main e
 * não faz parte do produto).
 *
 * Escopo: `/login` e `/register`, conforme o plano da Etapa 01.
 * Badges são não-interativos e ficam fora da medição por definição.
 */
const PAGES: Array<{ path: string; label: string }> = [
  { path: "/login", label: "/login" },
  { path: "/register", label: "/register" },
];

for (const { path, label } of PAGES) {
  test(`alvos interativos de ${label} têm altura ≥ 44px`, async ({ page }) => {
    await page.goto(path);
    await expect(page.getByLabel("Email")).toBeVisible();

    const tooSmall = await page
      .locator("main")
      .evaluate((root) =>
        Array.from(
          root.querySelectorAll<HTMLElement>(
            "button, input, select, textarea, a[href]"
          )
        )
          .map((el) => {
            const r = el.getBoundingClientRect();
            return {
              tag: el.tagName.toLowerCase(),
              label: (
                el.textContent ||
                (el as HTMLInputElement).placeholder ||
                (el as HTMLInputElement).id ||
                ""
              )
                .trim()
                .slice(0, 40),
              width: Math.round(r.width),
              height: Math.round(r.height),
            };
          })
          .filter((x) => x.width > 0 && x.height > 0 && x.height < 44)
      );

    expect(tooSmall, "elementos interativos abaixo de 44px").toEqual([]);
  });
}

test("viewport mobile não cria overflow horizontal", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  for (const path of ["/login", "/register"]) {
    await page.goto(path);
    await expect(page.getByLabel("Email")).toBeVisible();
    const overflow = await page.evaluate(
      () =>
        document.documentElement.scrollWidth -
        document.documentElement.clientWidth
    );
    expect(overflow, `${path} com overflow horizontal`).toBeLessThanOrEqual(0);
  }
});
