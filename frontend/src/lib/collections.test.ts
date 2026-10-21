/**
 * Testes do helper de coleção — `src/lib/collections.ts`.
 *
 * Cobrem o que é puro/sem DOM: deduplicação por `id` no append otimista da
 * lista de organizações (`OrganizationSection` em `src/app/page.tsx`), que
 * defende a UI contra a corrida entre o append de criação e o `setOrgs(data)`
 * do effect de load (duas chaves React iguais → warning do React).
 *
 * Ambiente `node` (ver `vitest.config.ts`): sem jsdom — a função é pura.
 */
import { describe, expect, it } from "vitest";
import { appendUniqueById } from "./collections";

interface Item {
  id: string;
  name: string;
}

const FIRST: Item = { id: "org-1", name: "Acme" };
const SECOND: Item = { id: "org-2", name: "Globex" };

describe("appendUniqueById", () => {
  it("appends an item with a new id at the end", () => {
    const result = appendUniqueById([FIRST], SECOND);

    expect(result).toHaveLength(2);
    expect(result[0]).toEqual(FIRST);
    expect(result[1]).toEqual(SECOND);
  });

  it("does not duplicate an item whose id is already present", () => {
    const result = appendUniqueById([FIRST], FIRST);

    expect(result).toHaveLength(1);
    expect(result.filter((entry) => entry.id === FIRST.id)).toHaveLength(1);
  });

  it("keeps the single existing entry when appending a duplicate id with different fields", () => {
    const renamed: Item = { id: FIRST.id, name: "Acme Inc." };
    const result = appendUniqueById([FIRST], renamed);

    expect(result).toHaveLength(1);
    expect(result[0]).toEqual(FIRST);
  });

  it("returns [item] for an empty list", () => {
    const result = appendUniqueById([], FIRST);

    expect(result).toEqual([FIRST]);
  });

  it("does not mutate the input list", () => {
    const list: readonly Item[] = [FIRST];
    const snapshot = [...list];

    const result = appendUniqueById(list, SECOND);

    expect(list).toEqual(snapshot);
    expect(list).toHaveLength(1);
    expect(result).not.toBe(list);
  });

  it("returns the same reference when the id already exists", () => {
    const list: readonly Item[] = [FIRST];

    const result = appendUniqueById(list, FIRST);

    expect(result).toBe(list);
  });

  it("preserves extra fields of the generic type", () => {
    const withRole: Item & { role: string } = { ...FIRST, role: "ADMIN" };

    const result = appendUniqueById([withRole], { ...SECOND, role: "VIEWER" });

    expect(result).toHaveLength(2);
    expect(result[0]).toEqual(withRole);
    expect(result[1].role).toBe("VIEWER");
  });
});
