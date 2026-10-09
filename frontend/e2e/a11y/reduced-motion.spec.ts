import { expect, test } from "@playwright/test";

/**
 * A11Y-03 — prefers-reduced-motion.
 *
 * `globals.css` declara um bloco `@media (prefers-reduced-motion: reduce)`
 * que zera `animation-duration`/`transition-duration` com `!important`,
 * sobrescrevendo as 67 transições do Tailwind.
 */

/** `"0.15s"` / `"1e-05s"` → milissegundos. */
function firstDurationMs(value: string): number {
  return parseFloat(value) * 1000;
}

test.describe("A11Y-03 reduced motion", () => {
  test("transições caem para ~0.01ms com prefers-reduced-motion", async ({
    page,
  }) => {
    await page.goto("/login");
    const button = page.getByRole("button", { name: "Sign in" });
    await expect(button).toBeVisible();

    // Controle: sem a preferência, a transição padrão do Tailwind (150ms).
    const normal = firstDurationMs(
      await button.evaluate((el) => getComputedStyle(el).transitionDuration)
    );
    expect(normal).toBeGreaterThanOrEqual(100);

    // Com a preferência: 0.01ms (serializado pelo Chrome como 1e-05s).
    await page.emulateMedia({ reducedMotion: "reduce" });
    const reduced = firstDurationMs(
      await button.evaluate((el) => getComputedStyle(el).transitionDuration)
    );
    expect(reduced).toBeLessThanOrEqual(0.01);

    // Vale também para links (transition hover).
    const link = page.getByRole("link", { name: "Create one" });
    const reducedLink = firstDurationMs(
      await link.evaluate((el) => getComputedStyle(el).transitionDuration)
    );
    expect(reducedLink).toBeLessThanOrEqual(0.01);
  });

  test("scroll-behavior também é neutralizado", async ({ page }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/login");
    const scrollBehavior = await page.evaluate(
      () => getComputedStyle(document.documentElement).scrollBehavior
    );
    expect(scrollBehavior).toBe("auto");
  });
});
