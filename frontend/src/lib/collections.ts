/**
 * Helpers puros de coleção compartilhados pela home (`src/app/page.tsx`).
 *
 * Mantidos num módulo próprio para que a regra de imutabilidade/deduplicação
 * exista em UM lugar só e seja testável sem DOM nem React (ver
 * `collections.test.ts`).
 */

/**
 * Anexa `item` ao fim de `list` somente se `item.id` ainda não existir.
 *
 * Por quê: a criação de organização faz append otimista (`setOrgs(prev => ...)`)
 * enquanto o effect de load pode resolver DEPOIS com um `GET /organizations` que
 * JÁ contém a org recém-criada — a ordem da corrida é imprevisível e o guard
 * `cancelled` do effect não protege contra ela. Sem este guard, o React renderiza
 * duas entradas com a mesma chave (`key={org.id}`) e avisa
 * "Encountered two children with the same key".
 *
 * Contrato:
 * - ID já presente → retorna `list` inalterada (mesma referência, zero cópia);
 * - ID novo → retorna NOVA lista com `item` no fim;
 * - nunca muta a lista de entrada (seguro para uso dentro de updater do `setState`).
 */
export function appendUniqueById<T extends { id: string }>(
  list: readonly T[],
  item: T
): T[] {
  if (list.some((entry) => entry.id === item.id)) {
    return list as T[];
  }
  return [...list, item];
}
