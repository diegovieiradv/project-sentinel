/* Sentinel Prompt 08 — Monitors aninhados no serviço */
"use client";

import { FormEvent, Fragment, useEffect, useState } from "react";
import {
  ApiError,
  activateMonitor,
  createMonitor,
  listMonitorChecks,
  listMonitors,
  updateMonitor,
  type CheckOutcome,
  type MonitorCheckResponse,
  type MonitorListParams,
  type MonitorMethod,
  type MonitorResponse,
  type ServiceResponse,
} from "@/lib/api";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";
import MetricsSection from "@/components/MetricsSection";

type MonitorsStatus = "idle" | "loading" | "ready" | "error";

/** Aviso sob a lista — sucesso (accent) ou falha de ação (destructive). */
type MonitorNotice = { kind: "success" | "error"; text: string } | null;

/** Filtro de ciclo de vida → query param opcional `active=true|false`. */
type MonitorActiveFilter = "all" | "active" | "inactive";

/** Filtro de método → query param opcional `method=GET|HEAD`. */
type MonitorMethodFilter = "all" | MonitorMethod;

/** Estado da carga do histórico de checagens do monitor expandido. */
type ChecksStatus = "idle" | "loading" | "ready" | "error";

/** Tamanho da página enviada ao server (contrato: default 20, máx 100). */
const MONITORS_PAGE_SIZE = 10;

/** Tamanho da página do histórico de checagens (mesmo contrato: máx 100). */
const CHECKS_PAGE_SIZE = 10;

/** Opções de método — espelha o enum do backend (`GET`/`HEAD`). */
const MONITOR_METHOD_OPTIONS: MonitorMethod[] = ["GET", "HEAD"];

/**
 * Limites do contrato de criação/edição. O backend continua sendo quem
 * valida (400 `VALIDATION_ERROR`) — aqui são só dica/feedback imediato.
 */
const INTERVAL_MIN = 30;
const INTERVAL_MAX = 86400;
const INTERVAL_DEFAULT = 60;
const TIMEOUT_MIN = 100;
const TIMEOUT_MAX = 30000;
const TIMEOUT_DEFAULT = 5000;

/** Formulário de monitor — números como texto para o usuário poder apagar. */
interface MonitorForm {
  name: string;
  url: string;
  method: MonitorMethod;
  intervalSeconds: string;
  timeoutMillis: string;
}

/** Formulário limpo, já com os defaults do contrato (GET, 60s, 5000ms). */
const EMPTY_MONITOR_FORM: MonitorForm = {
  name: "",
  url: "",
  method: "GET",
  intervalSeconds: String(INTERVAL_DEFAULT),
  timeoutMillis: String(TIMEOUT_DEFAULT),
};

/** Inteiro dentro de `[min, max]`, ou `null` quando fora/inválido. */
function parseBoundedInt(
  value: string,
  min: number,
  max: number
): number | null {
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  const parsed = Number(trimmed);
  if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) {
    return null;
  }
  return parsed;
}

/**
 * Mensagem legível de uma falha de mutação de monitor.
 * - **403** → papel insuficiente (feedback de erro; NUNCA logout — só 401 derruba a sessão).
 * - **400** `MONITOR_TARGET_NOT_ALLOWED` → texto FIXO: o backend já recusou o
 *   alvo por segurança (SSRF/allowlist) e a UI não ecoa ranges, detalhes nem
 *   caminhos de contorno.
 * - **409** `MONITOR_NAME_DUPLICATE`, `PROJECT_INACTIVE` e `SERVICE_INACTIVE` →
 *   mensagens específicas (inclui o caso "pai inativo: desativar segue ok").
 * - Demais (**400** `VALIDATION_ERROR`, **404**, …) → a mensagem do servidor.
 */
function monitorActionError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You don't have permission to manage monitors in this organization.";
    }
    if (err.code === "MONITOR_TARGET_NOT_ALLOWED") {
      return "This target is not allowed for security reasons.";
    }
    if (err.code === "MONITOR_NAME_DUPLICATE") {
      return "A monitor with this name already exists in this service.";
    }
    if (err.code === "SERVICE_INACTIVE") {
      return "This service is inactive; monitors can be viewed but not modified. Deactivating a monitor is still allowed.";
    }
    if (err.code === "PROJECT_INACTIVE") {
      return "This project is inactive; monitors can be viewed but not modified.";
    }
    return err.message;
  }
  return fallback;
}

/** Rótulo legível do desfecho persistido (enum `CheckOutcome`). */
const CHECK_OUTCOME_LABEL: Record<CheckOutcome, string> = {
  SUCCESS: "OK",
  DEGRADED_SIGNAL: "Redirect",
  TARGET_FAILURE: "Failed",
  SENTINEL_INTERNAL_FAILURE: "Internal error",
};

/**
 * Pill do desfecho de UMA checagem — mesma linguagem visual do `StatusBadge`
 * dos serviços, mas vocabulário próprio: aqui é o resultado de uma execução,
 * não o estado agregado do serviço.
 */
const CHECK_OUTCOME_CLASS: Record<CheckOutcome, string> = {
  SUCCESS: "border-(--accent)/50 bg-(--accent)/10 text-(--accent)",
  DEGRADED_SIGNAL:
    "border-(--accent-soft)/50 bg-(--accent-soft)/10 text-(--accent-soft)",
  TARGET_FAILURE:
    "border-(--destructive)/50 bg-(--destructive)/10 text-(--destructive)",
  SENTINEL_INTERNAL_FAILURE:
    "border-(--border-hover) bg-(--muted)/10 text-(--muted)",
};

function CheckOutcomeBadge({ outcome }: { outcome: CheckOutcome }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 font-semibold ${CHECK_OUTCOME_CLASS[outcome]}`}
    >
      <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-current" />
      {CHECK_OUTCOME_LABEL[outcome]}
    </span>
  );
}

/** Badge de ciclo de vida (contrato `active`) — não é status de execução. */
function ActiveBadge({ active }: { active: boolean }) {
  return (
    <span
      className={
        active
          ? "rounded bg-(--accent) px-1.5 py-0.5 text-xs font-semibold text-white"
          : "rounded border border-(--border) px-1.5 py-0.5 text-xs font-semibold text-(--muted)"
      }
    >
      {active ? "active" : "Inactive"}
    </span>
  );
}

/** Badge do método HTTP configurado (`GET`/`HEAD`). */
function MethodBadge({ method }: { method: MonitorMethod }) {
  return (
    <span className="rounded border border-(--border) px-1.5 py-0.5 text-xs font-semibold text-(--muted)">
      {method}
    </span>
  );
}

/** Badge do tipo do monitor — contrato atual: apenas `HTTP`. */
function TypeBadge({ type }: { type: MonitorResponse["type"] }) {
  return (
    <span className="rounded border border-(--border) px-1.5 py-0.5 text-xs font-semibold text-(--muted)">
      {type}
    </span>
  );
}

/**
 * Monitors do serviço selecionado (Prompt 08).
 *
 * Hierarquia: organização ativa → `ProjectsPanel` → projeto expandido →
 * `ServicesPanel` → serviço expandido → este painel. O pai o monta junto com a
 * linha de detalhe do serviço usando `key={service.id}`, então o estado nasce
 * limpo por serviço — mesmo padrão do `MembersPanel`/`ProjectsPanel`.
 *
 * RBAC aqui é só UX: `role === "VIEWER"` não recebe controles mutáveis
 * funcionais, mas a autoridade continua no backend — um **403** vira mensagem
 * na UI, jamais logout. Serviço/projeto pai inativo mantém a leitura; o backend
 * responde **409** em criar/editar/ativar (exibido aqui) e continua permitindo
 * desativar (fail-safe) — por isso o toggle de desativação não é bloqueado.
 *
 * Este painel NÃO exibe execução (run, resultado, latência, uptime, status de
 * check): isso pertence a outro prompt. Os badges são só ciclo de vida,
 * método e tipo.
 */
export default function MonitorsPanel({
  service,
}: {
  service: ServiceResponse;
}) {
  const orgId = service.organizationId;
  const projectId = service.projectId;
  const serviceId = service.id;
  /** VIEWER é somente-leitura no cliente (o backend continua sendo a autoridade). */
  const canManage = service.role !== "VIEWER";

  const [monitors, setMonitors] = useState<MonitorResponse[]>([]);
  const [status, setStatus] = useState<MonitorsStatus>("idle");
  const [reload, setReload] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Busca por nome com debounce simples: `searchInput` é o digitado,
  // `nameFilter` é o valor já aplicado na query (evita refetch por tecla).
  const [searchInput, setSearchInput] = useState("");
  const [nameFilter, setNameFilter] = useState("");
  const [activeFilter, setActiveFilter] = useState<MonitorActiveFilter>("all");
  const [methodFilter, setMethodFilter] = useState<MonitorMethodFilter>("all");

  const [createForm, setCreateForm] = useState<MonitorForm>(
    EMPTY_MONITOR_FORM
  );
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Detalhe inline (expansão da linha) = também editor dos campos do monitor.
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<MonitorForm>(EMPTY_MONITOR_FORM);
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [notice, setNotice] = useState<MonitorNotice>(null);

  // Histórico de checagens do monitor expandido (Prompt 10) — somente leitura.
  const [checks, setChecks] = useState<MonitorCheckResponse[]>([]);
  const [checksStatus, setChecksStatus] = useState<ChecksStatus>("idle");
  const [checksError, setChecksError] = useState<string | null>(null);
  const [checksPage, setChecksPage] = useState(0);
  const [checksTotalPages, setChecksTotalPages] = useState(0);
  const [checksTotalElements, setChecksTotalElements] = useState(0);

  const busy = creating || saving || togglingId !== null;
  const hasFilter =
    nameFilter !== "" || activeFilter !== "all" || methodFilter !== "all";

  // Debounce da busca (300ms) — trocar de filtro/página deixa a lista coerente.
  useEffect(() => {
    const timer = setTimeout(() => {
      setNameFilter(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // Listagem — recarrega em mudança de página, filtro, serviço ou reload.
  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      setStatus("loading");
      setListError(null);
      try {
        const params: MonitorListParams = {
          page,
          size: MONITORS_PAGE_SIZE,
        };
        if (nameFilter) params.name = nameFilter;
        if (activeFilter !== "all") params.active = activeFilter === "active";
        if (methodFilter !== "all") params.method = methodFilter;

        const data = await listMonitors(orgId, projectId, serviceId, params);
        if (cancelled) return;
        setMonitors(data.content);
        setTotalPages(data.totalPages);
        setTotalElements(data.totalElements);
        setStatus("ready");
      } catch (err) {
        if (cancelled) return;
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        setStatus("error");
        setListError(messageOf(err, "Could not load monitors"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, serviceId, page, nameFilter, activeFilter, methodFilter, reload]);

  // Histórico — só carrega com um monitor aberto; página e `reload` (Retry ou
  // mutação na lista) regridem juntos. Fechar o detalhe zera o estado no
  // handler do toggle (nunca setState síncrono dentro do effect).
  useEffect(() => {
    const monitorId = expandedId;
    if (!monitorId) return;

    let cancelled = false;

    async function loadChecks(id: string): Promise<void> {
      setChecksStatus("loading");
      setChecksError(null);
      try {
        const data = await listMonitorChecks(orgId, projectId, serviceId, id, {
          page: checksPage,
          size: CHECKS_PAGE_SIZE,
        });
        if (cancelled) return;
        setChecks(data.content);
        setChecksTotalPages(data.totalPages);
        setChecksTotalElements(data.totalElements);
        setChecksStatus("ready");
      } catch (err) {
        if (cancelled) return;
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        setChecksStatus("error");
        setChecksError(messageOf(err, "Could not load checks"));
      }
    }

    void loadChecks(monitorId);

    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, serviceId, expandedId, checksPage, reload]);

  /** Abre/fecha a linha de detalhe, semeando o form de edição com o monitor. */
  function onToggleDetails(monitor: MonitorResponse): void {
    if (expandedId === monitor.id) {
      setExpandedId(null);
      setEditError(null);
      // histórico é do monitor expandido: fechar descarta tudo
      setChecksPage(0);
      setChecks([]);
      setChecksStatus("idle");
      setChecksError(null);
      setChecksTotalPages(0);
      setChecksTotalElements(0);
      return;
    }
    setExpandedId(monitor.id);
    setChecksPage(0);
    setEditForm({
      name: monitor.name,
      url: monitor.url,
      method: monitor.method,
      intervalSeconds: String(monitor.intervalSeconds),
      timeoutMillis: String(monitor.timeoutMillis),
    });
    setEditError(null);
  }

  /** POST .../monitors — criação (name e url obrigatórios, demais com default). */
  async function onCreateMonitor(
    event: FormEvent<HTMLFormElement>
  ): Promise<void> {
    event.preventDefault();
    const name = createForm.name.trim();
    const url = createForm.url.trim();
    if (!canManage || !name || !url || busy) return;

    const intervalSeconds = parseBoundedInt(
      createForm.intervalSeconds,
      INTERVAL_MIN,
      INTERVAL_MAX
    );
    if (intervalSeconds === null) {
      setCreateError(
        `Interval must be a whole number between ${INTERVAL_MIN} and ${INTERVAL_MAX} seconds (default ${INTERVAL_DEFAULT}).`
      );
      return;
    }
    const timeoutMillis = parseBoundedInt(
      createForm.timeoutMillis,
      TIMEOUT_MIN,
      TIMEOUT_MAX
    );
    if (timeoutMillis === null) {
      setCreateError(
        `Timeout must be a whole number between ${TIMEOUT_MIN} and ${TIMEOUT_MAX} milliseconds (default ${TIMEOUT_DEFAULT}).`
      );
      return;
    }

    setCreating(true);
    setCreateError(null);
    try {
      const created = await createMonitor(orgId, projectId, serviceId, {
        name,
        url,
        method: createForm.method,
        intervalSeconds,
        timeoutMillis,
      });
      setCreateForm(EMPTY_MONITOR_FORM);
      setNotice({
        kind: "success",
        text: `Monitor "${created.name}" created.`,
      });
      setReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setCreateError(
        monitorActionError(
          err,
          "Could not create the monitor. Is the backend running?"
        )
      );
    } finally {
      setCreating(false);
    }
  }

  /** PATCH .../monitors/{monitorId} — edição parcial dos campos do form. */
  async function onSaveEdit(
    event: FormEvent<HTMLFormElement>,
    monitor: MonitorResponse
  ): Promise<void> {
    event.preventDefault();
    const name = editForm.name.trim();
    const url = editForm.url.trim();
    if (!canManage || !name || !url || busy) return;

    const intervalSeconds = parseBoundedInt(
      editForm.intervalSeconds,
      INTERVAL_MIN,
      INTERVAL_MAX
    );
    if (intervalSeconds === null) {
      setEditError(
        `Interval must be a whole number between ${INTERVAL_MIN} and ${INTERVAL_MAX} seconds (default ${INTERVAL_DEFAULT}).`
      );
      return;
    }
    const timeoutMillis = parseBoundedInt(
      editForm.timeoutMillis,
      TIMEOUT_MIN,
      TIMEOUT_MAX
    );
    if (timeoutMillis === null) {
      setEditError(
        `Timeout must be a whole number between ${TIMEOUT_MIN} and ${TIMEOUT_MAX} milliseconds (default ${TIMEOUT_DEFAULT}).`
      );
      return;
    }

    setSaving(true);
    setEditError(null);
    try {
      const updated = await updateMonitor(
        orgId,
        projectId,
        serviceId,
        monitor.id,
        {
          name,
          url,
          method: editForm.method,
          intervalSeconds,
          timeoutMillis,
        }
      );
      setMonitors((prev) =>
        prev.map((item) => (item.id === updated.id ? updated : item))
      );
      setNotice({
        kind: "success",
        text: `Monitor "${updated.name}" updated.`,
      });
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setEditError(monitorActionError(err, "Could not update the monitor."));
    } finally {
      setSaving(false);
    }
  }

  /**
   * PATCH .../monitors/{monitorId}/activation — ativar/desativar o cadastro
   * (idempotente). NÃO executa um check: `active` é só o ciclo de vida.
   * Com o serviço/projeto pai inativo o backend aceita desativar e recusa
   * ativar com **409** — feedback exibido, sem desabilitar o toggle.
   */
  async function onToggleActivation(monitor: MonitorResponse): Promise<void> {
    if (!canManage || busy) return;

    setTogglingId(monitor.id);
    setNotice(null);
    try {
      const updated = await activateMonitor(
        orgId,
        projectId,
        serviceId,
        monitor.id,
        !monitor.active
      );
      setMonitors((prev) =>
        prev.map((item) => (item.id === updated.id ? updated : item))
      );
      setNotice({
        kind: "success",
        text: `Monitor "${updated.name}" ${
          updated.active ? "activated" : "deactivated"
        }.`,
      });
      // Com filtro de ciclo de vida ativo o monitor pode sair/entrar da página.
      if (activeFilter !== "all") setReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setNotice({
        kind: "error",
        text: monitorActionError(
          err,
          "Could not update the monitor activation."
        ),
      });
    } finally {
      setTogglingId(null);
    }
  }

  return (
    <section className="mt-3 rounded-md border border-(--border) bg-(--surface) p-3">
      <h4 className="font-semibold text-white">Monitors — {service.name}</h4>

      {/* Serviço inativo → leitura ok; criar/editar/ativar devolvem 409
          (SERVICE_INACTIVE), desativar segue permitido (fail-safe). */}
      {!service.active && (
        <p className="mt-2 rounded-md border border-(--border) bg-(--card) px-3 py-2 text-sm text-(--muted)">
          <span className="font-medium text-white">
            This service is inactive;
          </span>{" "}
          monitors can be viewed but not modified. Creating, editing and
          activating are rejected by the server; deactivating a monitor is
          still allowed.
        </p>
      )}

      {service.role === "VIEWER" && (
        <p className="mt-2 text-sm text-(--muted)">
          Your role in this organization is{" "}
          <span className="font-medium text-white">VIEWER</span>. You can view
          monitors; creating, editing and activation require the{" "}
          <span className="font-medium text-white">ADMIN</span> or{" "}
          <span className="font-medium text-white">OPERATOR</span> role.
        </p>
      )}

      {/* Busca por nome (debounce) + filtros de ciclo de vida e de método */}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <label htmlFor={`monitor-search-${service.id}`} className="sr-only">
          Search monitors by name
        </label>
        <input
          id={`monitor-search-${service.id}`}
          type="search"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          placeholder="Search by name…"
          className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--card) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        />
        <label
          htmlFor={`monitor-active-filter-${service.id}`}
          className="sr-only"
        >
          Filter monitors by activation
        </label>
        <select
          id={`monitor-active-filter-${service.id}`}
          value={activeFilter}
          onChange={(event) => {
            setActiveFilter(event.target.value as MonitorActiveFilter);
            setPage(0);
          }}
          className="rounded-md border border-(--border) bg-(--card) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        >
          <option value="all">All</option>
          <option value="active">Active</option>
          <option value="inactive">Inactive</option>
        </select>
        <label
          htmlFor={`monitor-method-filter-${service.id}`}
          className="sr-only"
        >
          Filter monitors by method
        </label>
        <select
          id={`monitor-method-filter-${service.id}`}
          value={methodFilter}
          onChange={(event) => {
            setMethodFilter(event.target.value as MonitorMethodFilter);
            setPage(0);
          }}
          className="rounded-md border border-(--border) bg-(--card) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        >
          <option value="all">All methods</option>
          {MONITOR_METHOD_OPTIONS.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </div>

      {/* Erro da listagem ≠ lista vazia: caixa própria com Retry */}
      {status === "error" ? (
        <div className="mt-3 rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-3 text-sm">
          <p className="text-(--destructive)">
            {listError ?? "Could not load monitors"}
          </p>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            className="mt-2 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white"
          >
            Retry
          </button>
        </div>
      ) : status === "ready" && monitors.length === 0 ? (
        <p className="mt-3 text-sm text-(--muted)">
          {hasFilter
            ? "No monitors match your filters."
            : canManage
              ? "No monitors yet for this service. Add the first one below."
              : "No monitors yet for this service."}
        </p>
      ) : status === "ready" ? (
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-(--border) text-left text-(--muted)">
                <th className="py-2 pr-3 font-medium">Name</th>
                <th className="py-2 pr-3 font-medium">Details</th>
                <th className="py-2 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {monitors.map((monitor) => {
                const isExpanded = expandedId === monitor.id;
                return (
                  <Fragment key={monitor.id}>
                    <tr className="border-b border-(--border)/30">
                      <td className="py-2 pr-3 font-medium text-white">
                        {monitor.name}
                      </td>
                      <td className="py-2 pr-3">
                        {/* Resumo da linha: só ciclo de vida, método e tipo.
                            O resultado de cada execução (histórico de checks,
                            Prompt 10) fica DENTRO do detalhe expandido. */}
                        <span className="flex flex-wrap items-center gap-2">
                          <ActiveBadge active={monitor.active} />
                          <MethodBadge method={monitor.method} />
                          <TypeBadge type={monitor.type} />
                        </span>
                      </td>
                      <td className="py-2 text-right">
                        <span className="flex justify-end gap-2">
                          <button
                            type="button"
                            onClick={() => onToggleDetails(monitor)}
                            aria-expanded={isExpanded}
                            disabled={busy}
                            className="rounded-md border border-(--border) px-3 py-2.5 min-h-[44px] text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                          >
                            {isExpanded ? "Close" : "Details"}
                          </button>
                          <button
                            type="button"
                            onClick={() => void onToggleActivation(monitor)}
                            disabled={!canManage || busy}
                            className="rounded-md border border-(--border) px-3 py-2.5 min-h-[44px] text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                          >
                            {togglingId === monitor.id
                              ? "Saving…"
                              : monitor.active
                                ? "Deactivate"
                                : "Activate"}
                          </button>
                        </span>
                      </td>
                    </tr>

                    {/* Detalhe inline = edição de name/url/method/intervals */}
                    {isExpanded && (
                      <tr className="border-b border-(--border)/30">
                        <td colSpan={3} className="py-3 pr-3">
                          <div className="flex flex-col gap-2 rounded-md border border-(--border) bg-(--card) p-3">
                            <p className="break-all text-sm text-(--muted)">
                              {monitor.url}
                            </p>
                            <p className="text-xs text-(--muted)">
                              {monitor.method} · every {monitor.intervalSeconds}s
                              · timeout {monitor.timeoutMillis}ms ·{" "}
                              {monitor.type}
                            </p>
                            <p className="text-xs text-(--muted)">
                              Created {monitor.createdAt} · Updated{" "}
                              {monitor.updatedAt} · Your role {monitor.role}
                            </p>
                            <p className="flex flex-wrap items-center gap-2 text-xs text-(--muted)">
                              <span>Activation:</span>
                              <ActiveBadge active={monitor.active} />
                              <span>Method:</span>
                              <MethodBadge method={monitor.method} />
                              <span>Type:</span>
                              <TypeBadge type={monitor.type} />
                            </p>

                            {canManage ? (
                              <form
                                onSubmit={(event) =>
                                  void onSaveEdit(event, monitor)
                                }
                                className="flex flex-col gap-2"
                              >
                                <label
                                  htmlFor={`monitor-edit-name-${monitor.id}`}
                                  className="sr-only"
                                >
                                  Monitor name
                                </label>
                                <input
                                  id={`monitor-edit-name-${monitor.id}`}
                                  value={editForm.name}
                                  onChange={(event) =>
                                    setEditForm((form) => ({
                                      ...form,
                                      name: event.target.value,
                                    }))
                                  }
                                  placeholder="Monitor name"
                                  disabled={busy}
                                  className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
                                />
                                <label
                                  htmlFor={`monitor-edit-url-${monitor.id}`}
                                  className="sr-only"
                                >
                                  Monitor target URL
                                </label>
                                <input
                                  id={`monitor-edit-url-${monitor.id}`}
                                  type="url"
                                  value={editForm.url}
                                  onChange={(event) =>
                                    setEditForm((form) => ({
                                      ...form,
                                      url: event.target.value,
                                    }))
                                  }
                                  placeholder="https://example.com/health"
                                  disabled={busy}
                                  className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
                                />
                                <div className="flex flex-wrap items-end gap-2">
                                  <span className="flex flex-col gap-1">
                                    <label
                                      htmlFor={`monitor-edit-method-${monitor.id}`}
                                      className="text-xs text-(--muted)"
                                    >
                                      Method
                                    </label>
                                    <select
                                      id={`monitor-edit-method-${monitor.id}`}
                                      value={editForm.method}
                                      onChange={(event) =>
                                        setEditForm((form) => ({
                                          ...form,
                                          method: event.target
                                            .value as MonitorMethod,
                                        }))
                                      }
                                      disabled={busy}
                                      className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
                                    >
                                      {MONITOR_METHOD_OPTIONS.map((option) => (
                                        <option key={option} value={option}>
                                          {option}
                                        </option>
                                      ))}
                                    </select>
                                  </span>
                                  <span className="flex flex-col gap-1">
                                    <label
                                      htmlFor={`monitor-edit-interval-${monitor.id}`}
                                      className="text-xs text-(--muted)"
                                    >
                                      Interval (seconds)
                                    </label>
                                    <input
                                      id={`monitor-edit-interval-${monitor.id}`}
                                      type="number"
                                      inputMode="numeric"
                                      min={INTERVAL_MIN}
                                      max={INTERVAL_MAX}
                                      value={editForm.intervalSeconds}
                                      onChange={(event) =>
                                        setEditForm((form) => ({
                                          ...form,
                                          intervalSeconds: event.target.value,
                                        }))
                                      }
                                      disabled={busy}
                                      className="w-28 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
                                    />
                                    <span className="text-xs text-(--muted)">
                                      {INTERVAL_MIN}–{INTERVAL_MAX}s, default{" "}
                                      {INTERVAL_DEFAULT}
                                    </span>
                                  </span>
                                  <span className="flex flex-col gap-1">
                                    <label
                                      htmlFor={`monitor-edit-timeout-${monitor.id}`}
                                      className="text-xs text-(--muted)"
                                    >
                                      Timeout (milliseconds)
                                    </label>
                                    <input
                                      id={`monitor-edit-timeout-${monitor.id}`}
                                      type="number"
                                      inputMode="numeric"
                                      min={TIMEOUT_MIN}
                                      max={TIMEOUT_MAX}
                                      value={editForm.timeoutMillis}
                                      onChange={(event) =>
                                        setEditForm((form) => ({
                                          ...form,
                                          timeoutMillis: event.target.value,
                                        }))
                                      }
                                      disabled={busy}
                                      className="w-32 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
                                    />
                                    <span className="text-xs text-(--muted)">
                                      {TIMEOUT_MIN}–{TIMEOUT_MAX}ms, default{" "}
                                      {TIMEOUT_DEFAULT}
                                    </span>
                                  </span>
                                </div>
                                <div className="flex flex-wrap items-center gap-2">
                                  <button
                                    type="submit"
                                    disabled={
                                      busy ||
                                      editForm.name.trim() === "" ||
                                      editForm.url.trim() === ""
                                    }
                                    className="rounded-md bg-(--accent) px-4 py-3 min-h-[44px] text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
                                  >
                                    {saving ? "Saving…" : "Save changes"}
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setExpandedId(null);
                                      setEditError(null);
                                    }}
                                    disabled={busy}
                                    className="rounded-md border border-(--border) px-4 py-3 min-h-[44px] text-sm text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                                  >
                                    Close
                                  </button>
                                  {editError && (
                                    <p className="text-sm text-(--destructive)">
                                      {editError}
                                    </p>
                                  )}
                                </div>
                              </form>
                            ) : (
                              <p className="text-xs text-(--muted)">
                                Read-only — your role cannot edit monitors.
                              </p>
                            )}

                            {/* Métricas históricas do monitor (Prompt 11) —
                                `key` nasce limpo por monitor (trocar o
                                expandido sem fechar descarta o estado). */}
                            <MetricsSection
                              key={`metrics-${monitor.id}`}
                              title="Monitor metrics"
                              organizationId={orgId}
                              projectId={projectId}
                              serviceId={serviceId}
                              monitorId={monitor.id}
                            />

                            {/* Histórico de checagens (Prompt 10): somente
                                leitura, ordenado pelo server (mais novo
                                primeiro) — a UI só pagina. */}
                            <div className="mt-2 border-t border-(--border) pt-3">
                              <div className="flex flex-wrap items-center justify-between gap-2">
                                <p className="text-xs font-semibold uppercase tracking-wide text-(--muted)">
                                  Last checks
                                </p>
                                {checksStatus === "ready" &&
                                  checksTotalElements > 0 && (
                                    <span className="text-xs text-(--muted)">
                                      {checksTotalElements}{" "}
                                      {checksTotalElements === 1
                                        ? "check"
                                        : "checks"}{" "}
                                      · Page{" "}
                                      {Math.min(
                                        checksPage + 1,
                                        Math.max(checksTotalPages, 1)
                                      )}{" "}
                                      of {Math.max(checksTotalPages, 1)}
                                    </span>
                                  )}
                              </div>

                              {checksStatus === "loading" ? (
                                <p className="mt-2 text-sm text-(--muted)" role="status">
                                  Loading checks…
                                </p>
                              ) : checksStatus === "error" ? (
                                <div className="mt-2 rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-2 text-sm">
                                  <p className="text-(--destructive)">
                                    {checksError ?? "Could not load checks"}
                                  </p>
                                  <button
                                    type="button"
                                    onClick={() => setReload((count) => count + 1)}
                                    disabled={busy}
                                    className="mt-2 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                                  >
                                    Retry
                                  </button>
                                </div>
                              ) : checks.length === 0 ? (
                                <p className="mt-2 text-sm text-(--muted)">
                                  No checks recorded yet for this monitor.
                                </p>
                              ) : (
                                <ul className="mt-2 flex flex-col gap-1.5">
                                  {checks.map((check) => (
                                    <li
                                      key={check.id}
                                      className="flex flex-wrap items-center gap-2 rounded-md border border-(--border) bg-(--surface) px-2 py-1.5 text-xs"
                                    >
                                      <CheckOutcomeBadge
                                        outcome={check.outcome}
                                      />
                                      <span className="text-(--muted)">
                                        {check.startedAt}
                                      </span>
                                      <span className="text-(--muted)">
                                        {check.durationMillis}ms
                                      </span>
                                      {check.httpStatus !== null && (
                                        <span className="text-(--muted)">
                                          HTTP {check.httpStatus}
                                        </span>
                                      )}
                                      {check.failureCategory && (
                                        <span className="text-(--muted)">
                                          {check.failureCategory}
                                        </span>
                                      )}
                                      <span className="min-w-0 flex-1 break-all text-(--muted)">
                                        {check.message}
                                      </span>
                                    </li>
                                  ))}
                                </ul>
                              )}

                              {checksStatus === "ready" &&
                                checksTotalPages > 1 && (
                                  <div className="mt-2 flex justify-end gap-2 text-xs">
                                    <button
                                      type="button"
                                      onClick={() =>
                                        setChecksPage((current) =>
                                          Math.max(current - 1, 0)
                                        )
                                      }
                                      disabled={checksPage === 0 || busy}
                                      className="rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                                    >
                                      Previous
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() =>
                                        setChecksPage((current) => current + 1)
                                      }
                                      disabled={
                                        checksPage + 1 >= checksTotalPages ||
                                        busy
                                      }
                                      className="rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                                    >
                                      Next
                                    </button>
                                  </div>
                                )}
                            </div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>

          {/* Paginação simples: anterior/próxima + total */}
          <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-sm">
            <span className="text-(--muted)">
              {totalElements}{" "}
              {totalElements === 1 ? "monitor" : "monitors"} · Page{" "}
              {Math.min(page + 1, Math.max(totalPages, 1))} of{" "}
              {Math.max(totalPages, 1)}
            </span>
            <span className="flex gap-2">
              <button
                type="button"
                onClick={() => setPage((current) => Math.max(current - 1, 0))}
                disabled={page === 0 || busy}
                className="rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
              >
                Previous
              </button>
              <button
                type="button"
                onClick={() => setPage((current) => current + 1)}
                disabled={page + 1 >= totalPages || busy}
                className="rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
              >
                Next
              </button>
            </span>
          </div>
        </div>
      ) : (
        <p className="mt-3 text-sm text-(--muted)" role="status">Loading monitors…</p>
      )}

      {/* Feedback de sucesso/falha de ações sobre a lista */}
      {status !== "error" && notice && (
        <p
          className={`mt-3 text-sm ${
            notice.kind === "error"
              ? "text-(--destructive)"
              : "text-(--accent)"
          }`}
        >
          {notice.text}
        </p>
      )}

      {/* Criar monitor — oculto para VIEWER (controle mutável sem permissão) */}
      {canManage && (
        <form
          onSubmit={onCreateMonitor}
          className="mt-4 flex flex-col gap-3 border-t border-(--border) pt-4"
        >
          <div className="flex flex-wrap items-end gap-2">
            <span className="flex min-w-0 flex-1 flex-col gap-1">
              <label
                htmlFor={`monitor-create-name-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Name
              </label>
              <input
                id={`monitor-create-name-${service.id}`}
                value={createForm.name}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    name: event.target.value,
                  }))
                }
                placeholder="New monitor name"
                disabled={busy}
                className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
              />
            </span>
            <span className="flex min-w-0 flex-1 flex-col gap-1">
              <label
                htmlFor={`monitor-create-url-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Target URL
              </label>
              <input
                id={`monitor-create-url-${service.id}`}
                type="url"
                value={createForm.url}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    url: event.target.value,
                  }))
                }
                placeholder="https://example.com/health"
                disabled={busy}
                className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
              />
            </span>
          </div>

          <div className="flex flex-wrap items-end gap-2">
            <span className="flex flex-col gap-1">
              <label
                htmlFor={`monitor-create-method-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Method
              </label>
              <select
                id={`monitor-create-method-${service.id}`}
                value={createForm.method}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    method: event.target.value as MonitorMethod,
                  }))
                }
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
              >
                {MONITOR_METHOD_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </span>
            <span className="flex flex-col gap-1">
              <label
                htmlFor={`monitor-create-interval-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Interval (seconds)
              </label>
              <input
                id={`monitor-create-interval-${service.id}`}
                type="number"
                inputMode="numeric"
                min={INTERVAL_MIN}
                max={INTERVAL_MAX}
                value={createForm.intervalSeconds}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    intervalSeconds: event.target.value,
                  }))
                }
                disabled={busy}
                className="w-28 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
              />
              <span className="text-xs text-(--muted)">
                {INTERVAL_MIN}–{INTERVAL_MAX}s, default {INTERVAL_DEFAULT}
              </span>
            </span>
            <span className="flex flex-col gap-1">
              <label
                htmlFor={`monitor-create-timeout-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Timeout (milliseconds)
              </label>
              <input
                id={`monitor-create-timeout-${service.id}`}
                type="number"
                inputMode="numeric"
                min={TIMEOUT_MIN}
                max={TIMEOUT_MAX}
                value={createForm.timeoutMillis}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    timeoutMillis: event.target.value,
                  }))
                }
                disabled={busy}
                className="w-32 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
              />
              <span className="text-xs text-(--muted)">
                {TIMEOUT_MIN}–{TIMEOUT_MAX}ms, default {TIMEOUT_DEFAULT}
              </span>
            </span>
            <button
              type="submit"
              disabled={
                busy ||
                createForm.name.trim() === "" ||
                createForm.url.trim() === ""
              }
              className="rounded-md bg-(--accent) px-4 py-3 min-h-[44px] text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
            >
              {creating ? "Creating…" : "Create monitor"}
            </button>
          </div>

          {createError && (
            <p className="text-sm text-(--destructive)">{createError}</p>
          )}
        </form>
      )}
    </section>
  );
}
