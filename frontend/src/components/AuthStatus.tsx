"use client";

import { useEffect, useState } from "react";
import {
  me,
  refreshSession,
  getAccessToken,
  clearAccessToken,
  clearOrganizationId,
  ApiError,
  SESSION_EXPIRED_EVENT,
  type UserResponse,
} from "@/lib/api";

type AuthState =
  | { kind: "loading" }
  | { kind: "anon" }
  | { kind: "authed"; user: UserResponse }
  | { kind: "error"; message: string };

/** Fases de sessão expostas ao componente pai (a home usa para gatear a lista de orgs). */
export type AuthStateKind = AuthState["kind"];

export interface AuthSessionEvent {
  kind: AuthStateKind;
  /** Presente apenas quando `kind === "authed"` — dados reais do usuário. */
  user?: UserResponse;
  /**
   * Presente apenas quando `kind === "error"` — a mensagem legível da falha
   * (5xx/rede), reaproveitada pela home para mostrar o estado de erro em vez
   * de expulsar o usuário sem explicação.
   */
  message?: string;
}

export interface AuthStatusProps {
  /**
   * Notifica o componente pai a cada transição de sessão.
   *
   * A home usa isto para (a) só consultar `/organizations` depois que a sessão
   * está confirmada — sem isso a listagem dispararia junto com o bootstrap do
   * refresh e cairia em 401 na corrida —, (b) limpar o estado de organizações
   * quando o usuário sai ou a sessão expira e (c) exibir o estado de erro da
   * sessão com a `message` vinda do bootstrap (em vez de redirecionar).
   * Deve ser estável (`useCallback` com deps `[]`) para o efeito notificar só
   * quando o estado de fato muda.
   */
  onAuthStateChange?: (session: AuthSessionEvent) => void;
}

/**
 * Bootstrap de sessão em andamento (single-flight — AUTH-01).
 *
 * Em React StrictMode (padrão do App Router — ativo neste projeto) o efeito de
 * bootstrap é montado 2× em dev: duas execuções independentes disparariam 2×
 * `POST /auth/refresh`, e a rotação atômica do backend daria 1×200 + 1×401 — o
 * 401 de uma delas derrubava a sessão recém-renovada ("Signed out" no F5).
 *
 * Aqui só UMA tentativa voa por janela de bootstrap: as instâncias concorrentes
 * aguardam a MESMA Promise e aplicam o resultado no próprio estado (a
 * desmontada ignora via `cancelled`). Limpo em `finally` (sucesso, falha ou
 * desmontagem) para a próxima montagem iniciar um bootstrap novo.
 */
let sessionBootstrap: Promise<UserResponse> | null = null;

/** Roda (ou reutiliza) o bootstrap: refresh quando necessário + `me()` para confirmar. */
function bootstrapSession(): Promise<UserResponse> {
  if (!sessionBootstrap) {
    sessionBootstrap = (async () => {
      if (getAccessToken()) {
        // Já tem token em memória — confirma a sessão com me()
        return me();
      }
      // Nenhum token em memória — tenta refreshSession usando o cookie HttpOnly do refresh
      await refreshSession();
      // Depois do refresh, tenta me() novamente para confirmar
      return me();
    })().finally(() => {
      sessionBootstrap = null;
    });
  }
  return sessionBootstrap;
}

/**
 * Bootstrap de sessão Prompt 03:
 * - Se houver access token em memória → chama me() para confirmar.
 * - Se não houver token → tenta refreshSession() (que usa o cookie HttpOnly do refresh)
 *   e depois me() para confirmar a sessão.
 * - 401 em qualquer etapa → estado anônimo.
 * - Erro de rede/5xx → estado de erro (não finge que o usuário está autenticado).
 * - Durante todo o bootstrap mantém loading — não mostra "Signed in" antes de confirmar.
 * - Escuta SESSION_EXPIRED_EVENT: um 401 em QUALQUER chamada da API (ex.:
 *   listagem de organizações) derruba a sessão inteira para estado anônimo.
 * - O bootstrap é single-flight por montagem (ver `sessionBootstrap`): em
 *   StrictMode o efeito monta duas vezes em dev e só 1 refresh voa (AUTH-01).
 */
export default function AuthStatus({ onAuthStateChange }: AuthStatusProps = {}) {
  const [state, setState] = useState<AuthState>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;

    // Single-flight: montagens concorrentes (StrictMode monta o efeito 2× em
    // dev) aguardam o MESMO bootstrap — 1 refresh + 1 me() por janela (AUTH-01).
    bootstrapSession().then(
      (user) => {
        if (cancelled) return;
        setState({
          kind: "authed",
          user,
        });
      },
      (err) => {
        // 401 = sessão expirada/inválida → anônimo
        // Qualquer outro erro → estado de erro (não finge autenticação)
        if (cancelled) return;

        if (err instanceof ApiError && err.status === 401) {
          // Sessão expirada ou token inválido — limpa token, descarta a org
          // ativa (memória + localStorage) e vai para anônimo
          clearAccessToken();
          clearOrganizationId();
          setState({ kind: "anon" });
          return;
        }

        // Erro de rede, 5xx, etc — não fingir que o usuário está autenticado
        setState({
          kind: "error",
          message:
            err instanceof Error ? err.message : "Could not verify session",
        });
      }
    );

    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * Reporta a fase de sessão ao componente pai (a home gateia a carga de
   * organizações nisso). O callback deve ser estável (`useCallback` no pai)
   * para o efeito só disparar quando `state.kind` realmente muda.
   */
  useEffect(() => {
    const event: AuthSessionEvent = { kind: state.kind };
    if (state.kind === "authed") {
      event.user = state.user;
    }
    if (state.kind === "error") {
      event.message = state.message;
    }
    onAuthStateChange?.(event);
    // Depende de `state` inteiro: o user (authed) e a mensagem (error) fazem
    // parte do evento notificado ao pai, não apenas o `kind`.
  }, [state, onAuthStateChange]);

  /**
   * 401 em qualquer chamada da API (o `apiFetch` dispara o evento) → limpa o
   * token em memória E a organização ativa (memória + localStorage) e assume
   * estado anônimo — derruba a sessão inteira, não apenas a chamada que falhou,
   * para a próxima conta não herdar a org da anterior.
   */
  useEffect(() => {
    function onSessionExpired(): void {
      clearAccessToken();
      clearOrganizationId();
      setState({ kind: "anon" });
    }

    window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    return () => {
      window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    };
  }, []);

  /**
   * Componente headless: toda a lógica de sessão (bootstrap, refresh, 401,
   * SESSION_EXPIRED_EVENT e notificação ao pai) permanece intacta, mas a
   * apresentação visual foi unificada no `UserAccount` do banner da home
   * (menu-central). Aqui não renderizamos nada.
   */
  return null;
}