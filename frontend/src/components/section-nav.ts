/**
 * Âncoras da navegação central da home (referência `public/menu-central.png`).
 *
 * Módulo pequeno e compartilhado entre `app/page.tsx` (a nav e os painéis de
 * organização) e `components/ServicesPanel.tsx` (outro arquivo): os itens de
 * menu precisam fazer o CONTEÚDO aparecer antes de rolar até a âncora, porque
 * nem sempre o alvo existe no DOM — `#services` só existe com um projeto
 * expandido e `#metrics` só com um serviço expandido.
 *
 * O pedido de expansão vive num "pendente" em memória do módulo (e não só no
 * evento): o `ServicesPanel` só monta DEPOIS que o `ProjectsPanel` expande a
 * linha do projeto, então ele consulta o pendente no próprio mount. O evento é
 * só o gatilho para os painéis que já estão montados reavaliarem.
 */

/** Ids das seções ancoradas, na ordem em que aparecem na navegação. */
export const NAV_SECTION_IDS = [
  "dashboard",
  "services",
  "members",
  "projects",
  "metrics",
] as const;

export type NavSectionId = (typeof NAV_SECTION_IDS)[number];

/**
 * Escopos de conteúdo que podem precisar ser expandidos antes do scroll:
 * - `services` → expandir o primeiro projeto (o `ServicesPanel` mora no
 *   detalhe expandido);
 * - `metrics` → expandir o primeiro projeto E o primeiro serviço (a âncora é
 *   a seção de métricas do serviço expandido).
 */
export type NavExpandScope = "services" | "metrics";

/** Nome do evento disparado quando um item de menu pede conteúdo novo. */
const EXPAND_REQUEST_EVENT = "sentinel:nav-expand-request";

/** Escopo ainda não consumido pelos painéis (memória do módulo = singleton). */
let pendingScope: NavExpandScope | null = null;

/**
 * Desfecho real de um pedido de expansão:
 * - `expanded` → o alvo foi criado (ou já existia): a nav pode rolar até ele;
 * - `unavailable` → não há como criar o alvo; `fallbackId` aponta para uma
 *   seção que JÁ EXISTE (ex.: `#projects` com a lista vazia, onde se cria o
 *   primeiro), ou `null` quando não há destino legítimo.
 *
 * O fallback é sempre DECIDIDO QUEM CONHECE O DADO (`ProjectsPanel` sabe se
 * há projetos) — nunca um timeout esgotando.
 */
export type NavExpandOutcome =
  | { status: "expanded" }
  | { status: "unavailable"; fallbackId: string | null };

/** Resolve a expansão pendente — `null` = ninguém aguardando o resultado. */
let pendingResolve: ((outcome: NavExpandOutcome) => void) | null = null;

/**
 * Guarda o escopo pedido, acorda os painéis montados e DEVOLVE a promessa de
 * conclusão: a navegação espera o desfecho anunciado pelo painel em vez de
 * contar tempo (com backend real, `#metrics` encadeia expandir projeto →
 * fetch de serviços → expandir serviço → render e quase sempre ultrapassa
 * qualquer janela fixa).
 *
 * O resolver é registrado ANTES do disparo: um painel que conclua de forma
 * síncrona dentro do evento já encontra quem aguarda. Um pedido novo
 * substitui o anterior — o scroll antigo recebe `unavailable` e não fica
 * pendurado para sempre.
 */
export function requestExpandFor(
  scope: NavExpandScope
): Promise<NavExpandOutcome> {
  pendingResolve?.({ status: "unavailable", fallbackId: null });
  const wait = new Promise<NavExpandOutcome>((resolve) => {
    pendingResolve = resolve;
  });
  pendingScope = scope;
  window.dispatchEvent(new Event(EXPAND_REQUEST_EVENT));
  return wait;
}

/**
 * Anuncia o desfecho do pedido pendente. Chamado por quem CONSUME o pendente
 * (`ProjectsPanel`/`ServicesPanel`) em todos os caminhos — expandiu, lista
 * vazia ou erro. Sem pendente é um no-op.
 */
export function announceExpandDone(outcome: NavExpandOutcome): void {
  const resolve = pendingResolve;
  pendingResolve = null;
  resolve?.(outcome);
}

/** Lê o escopo pendente (sem consumir) — usado pelos painéis para reagir. */
export function peekPendingExpand(): NavExpandScope | null {
  return pendingScope;
}

/**
 * Consome (ou só limpa) o pendente.
 * - sem argumento → limpa qualquer escopo;
 * - com escopo → limpa apenas se ainda for o mesmo (não apaga pedido alheio).
 */
export function clearPendingExpand(scope?: NavExpandScope): void {
  if (!scope || pendingScope === scope) {
    pendingScope = null;
  }
}

/**
 * Assina os pedidos de expansão. Retorna a função de unsubscribe.
 * Combine com uma leitura imediata de `peekPendingExpand()`: quem monta depois
 * do disparo precisa checar o pendente sozinho.
 */
export function onExpandRequest(handler: () => void): () => void {
  window.addEventListener(EXPAND_REQUEST_EVENT, handler);
  return () => window.removeEventListener(EXPAND_REQUEST_EVENT, handler);
}

/** `prefers-reduced-motion` → scroll instantâneo, nunca `smooth`. */
function prefersReducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

function scrollTo(target: HTMLElement): void {
  target.scrollIntoView({
    behavior: prefersReducedMotion() ? "auto" : "smooth",
    block: "start",
  });
}

/**
 * Espera o elemento aparecer no DOM — MUTATION-observer-driven: resolve no
 * exato commit em que o alvo nasce. O `timeoutMs` é só uma trava de
 * segurança para o observer não ficar preso se o render nunca vier; ele
 * NUNCA decide o destino do scroll (o desfecho vem do painel).
 */
async function waitForElement(
  id: string,
  timeoutMs: number
): Promise<HTMLElement | null> {
  const existing = document.getElementById(id);
  if (existing) return existing;

  return new Promise((resolve) => {
    let settled = false;
    let observer: MutationObserver | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;

    function finish(element: HTMLElement | null): void {
      if (settled) return;
      settled = true;
      observer?.disconnect();
      if (timer) clearTimeout(timer);
      resolve(element);
    }

    observer = new MutationObserver(() => {
      const element = document.getElementById(id);
      if (element) finish(element);
    });
    timer = setTimeout(() => finish(document.getElementById(id)), timeoutMs);
    observer.observe(document.body, { childList: true, subtree: true });
  });
}

export type ScrollToSectionOptions = {
  /**
   * Conclusão da expansão pedida (retorno de `requestExpandFor`). Com ela a
   * decisão de destino é DETERMINÍSTICA: o desfecho vem do painel que sabe
   * se há projetos/serviços — nenhum timeout cai em `#projects`.
   */
  expansion?: Promise<NavExpandOutcome>;
};

/**
 * Rola até `#id`.
 *
 * - Alvo já existe → rola na hora (âncora nativa resolve o restante).
 * - `options.expansion` → aguarda o desfecho do painel: `expanded` espera o
 *   commit que materializa o alvo; `unavailable` só rola para o `fallbackId`
 *   que o próprio painel declarou.
 * - Sem expansão (seções sempre presentes: `#dashboard`, `#members`,
 *   `#projects`) → espera curta pelo render, como antes.
 */
export async function scrollToSection(
  id: string,
  options?: ScrollToSectionOptions
): Promise<void> {
  const immediate = document.getElementById(id);
  if (immediate) {
    scrollTo(immediate);
    return;
  }

  if (!options?.expansion) {
    const target = await waitForElement(id, 600);
    if (target) scrollTo(target);
    return;
  }

  const outcome = await options.expansion;
  if (outcome.status === "expanded") {
    const target = await waitForElement(id, 2000);
    if (target) scrollTo(target);
    return;
  }

  if (outcome.fallbackId) {
    const fallback = document.getElementById(outcome.fallbackId);
    if (fallback) scrollTo(fallback);
  }
}
