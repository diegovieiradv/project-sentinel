/* Sentinel Prompt 07 — Services aninhados no projeto */
"use client";

import { FormEvent, Fragment, useEffect, useState } from "react";
import {
  ApiError,
  activateService,
  createService,
  listServices,
  updateService,
  type ProjectResponse,
  type ServiceListParams,
  type ServiceResponse,
  type ServiceStatus,
} from "@/lib/api";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";
import AlertsPanel from "@/components/AlertsPanel";
import AlertRulesPanel from "@/components/AlertRulesPanel";
import IncidentsPanel from "@/components/IncidentsPanel";
import MetricsSection from "@/components/MetricsSection";
import MonitorsPanel from "@/components/MonitorsPanel";
import {
  announceExpandDone,
  clearPendingExpand,
  onExpandRequest,
  peekPendingExpand,
} from "@/components/section-nav";

type ServicesStatus = "idle" | "loading" | "ready" | "error";

/** Aviso sob a lista — sucesso (accent) ou falha de ação (destructive). */
type ServiceNotice = { kind: "success" | "error"; text: string } | null;

/** Filtro de ciclo de vida → query param opcional `active=true|false`. */
type ServiceActiveFilter = "all" | "active" | "inactive";

/** Filtro de status operacional → query param opcional `status=...`. */
type ServiceStatusFilter = "all" | ServiceStatus;

/** Tamanho da página enviada ao server (contrato: default 20, máx 100). */
const SERVICES_PAGE_SIZE = 10;

/** Opções do filtro de status — espelha o enum do backend. */
const SERVICE_STATUS_OPTIONS: ServiceStatus[] = [
  "OPERATIONAL",
  "DEGRADED",
  "UNAVAILABLE",
  "UNKNOWN",
];

/** Rótulo legível de cada status operacional. */
const STATUS_LABELS: Record<ServiceStatus, string> = {
  OPERATIONAL: "Operational",
  DEGRADED: "Degraded",
  UNAVAILABLE: "Unavailable",
  UNKNOWN: "Unknown",
};

/**
 * Pill do status OPERACIONAL — sempre com bolinha e borda colorida, para nunca
 * ser confundida com o badge de ciclo de vida (`active`/`Inactive`), que é um
 * retângulo sem bolinha. O cliente exibe o valor REAL da API (hoje `UNKNOWN`):
 * nada aqui calcula simula disponibilidade.
 */
const STATUS_BADGE_CLASS: Record<ServiceStatus, string> = {
  OPERATIONAL: "border-(--accent)/50 bg-(--accent)/10 text-(--accent)",
  DEGRADED: "border-(--accent-soft)/50 bg-(--accent-soft)/10 text-(--accent-soft)",
  UNAVAILABLE: "border-(--destructive)/50 bg-(--destructive)/10 text-(--destructive)",
  UNKNOWN: "border-(--border-hover) bg-(--muted)/10 text-(--muted)",
};

/**
 * Mensagem legível de uma falha de mutação de serviço.
 * - **403** → papel insuficiente (feedback de erro; NUNCA logout — só 401 derruba a sessão).
 * - **409** `PROJECT_INACTIVE` → o projeto está inativo: leitura funciona,
 *   mutação não (mensagem clara e específica).
 * - **409** `SERVICE_NAME_DUPLICATE` e **400** `VALIDATION_ERROR` → a mensagem
 *   do servidor, que é mais específica do que qualquer texto genérico aqui.
 */
function serviceActionError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You don't have permission to manage services in this organization.";
    }
    if (err.code === "PROJECT_INACTIVE") {
      return "This project is inactive; services can be viewed but not modified.";
    }
    return err.message;
  }
  return fallback;
}

/** Badge de ciclo de vida (contrato `active`) — distinto do status operacional. */
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

/** Pill de status operacional (contrato `status`) — com bolinha, forma distinta. */
function StatusBadge({ status }: { status: ServiceStatus }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-semibold ${STATUS_BADGE_CLASS[status]}`}
    >
      <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-current" />
      {STATUS_LABELS[status]}
    </span>
  );
}

/**
 * Serviços do projeto expandido (Prompt 07).
 *
 * Hierarquia: organização ativa → `ProjectsPanel` → linha expandida do projeto
 * → este painel. O pai o monta só com um projeto selecionado e o desmonta junto
 * (`key={project.id}`), então o estado nasce limpo por projeto — mesmo padrão
 * do `MembersPanel`/`ProjectsPanel`.
 *
 * RBAC aqui é só UX: `role === "VIEWER"` não recebe controles mutáveis
 * funcionais, mas a autoridade continua no backend — um **403** vira mensagem
 * na UI, jamais logout. Projeto inativo mantém a leitura e devolve **409**
 * `PROJECT_INACTIVE` em qualquer mutação, também exibido na UI.
 */
export default function ServicesPanel({
  project,
}: {
  project: ProjectResponse;
}) {
  const orgId = project.organizationId;
  const projectId = project.id;
  /** VIEWER é somente-leitura no cliente (o backend continua sendo a autoridade). */
  const canManage = project.role !== "VIEWER";

  const [services, setServices] = useState<ServiceResponse[]>([]);
  const [status, setStatus] = useState<ServicesStatus>("idle");
  const [reload, setReload] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Busca por nome com debounce simples: `searchInput` é o digitado,
  // `nameFilter` é o valor já aplicado na query (evita refetch por tecla).
  const [searchInput, setSearchInput] = useState("");
  const [nameFilter, setNameFilter] = useState("");
  const [activeFilter, setActiveFilter] = useState<ServiceActiveFilter>("all");
  const [statusFilter, setStatusFilter] = useState<ServiceStatusFilter>("all");

  const [createForm, setCreateForm] = useState({ name: "", description: "" });
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Detalhe inline (expansão da linha) = também editor name/description.
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState({ name: "", description: "" });
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [notice, setNotice] = useState<ServiceNotice>(null);

  const busy = creating || saving || togglingId !== null;
  const hasFilter =
    nameFilter !== "" || activeFilter !== "all" || statusFilter !== "all";

  // Debounce da busca (300ms) — trocar de filtro/página deixa a lista coerente.
  useEffect(() => {
    const timer = setTimeout(() => {
      setNameFilter(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  /**
   * Expansão pedida pela navegação central da home (âncora `#metrics`): o menu
   * só rola até as métricas depois que o conteúdo aparece, então o primeiro
   * serviço é expandido aqui quando há pedido pendente.
   *
   * Roda no mount (este painel só existe depois que o projeto foi expandido)
   * e a cada mudança de lista: o pedido pode chegar ANTES do fetch terminar —
   * nesse caso não consumimos e reavaliamos quando o load termina.
   *
   * Todos os caminhos CONCLUEM o pedido (`announceExpandDone`): é o desfecho
   * determinístico que o scroll da nav aguarda. Sem serviços (ou em erro) o
   * fallback é o próprio `#services` — a seção que mostra "No services yet" /
   * o erro com Retry. `#projects` NUNCA é usado aqui: há projetos (senão o
   * `ProjectsPanel` já teria encerrado o pedido).
   *
   * "Serviço já expandido" é decidido pelo DOM (`#metrics`), não por
   * `expandedId`: com filtro ativo a linha pode ter saído da página visível e
   * aí o primeiro serviço VISÍVEL é quem expande.
   */
  useEffect(() => {
    function tryExpandFirstService(): void {
      if (peekPendingExpand() !== "metrics") return;

      if (status === "error") {
        clearPendingExpand("metrics");
        announceExpandDone({ status: "unavailable", fallbackId: "services" });
        return;
      }
      if (status !== "ready") return;

      if (document.getElementById("metrics")) {
        clearPendingExpand("metrics");
        announceExpandDone({ status: "expanded" });
        return;
      }

      const first = services[0];
      if (!first) {
        clearPendingExpand("metrics");
        announceExpandDone({ status: "unavailable", fallbackId: "services" });
        return;
      }

      setExpandedId(first.id);
      setEditForm({ name: first.name, description: first.description ?? "" });
      setEditError(null);
      clearPendingExpand("metrics");
      announceExpandDone({ status: "expanded" });
    }

    tryExpandFirstService();
    return onExpandRequest(tryExpandFirstService);
  }, [status, services, expandedId]);

  // Listagem — recarrega em mudança de página, filtro, projeto ou reload.
  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      setStatus("loading");
      setListError(null);
      try {
        const params: ServiceListParams = {
          page,
          size: SERVICES_PAGE_SIZE,
        };
        if (nameFilter) params.name = nameFilter;
        if (activeFilter !== "all") params.active = activeFilter === "active";
        if (statusFilter !== "all") params.status = statusFilter;

        const data = await listServices(orgId, projectId, params);
        if (cancelled) return;
        setServices(data.content);
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
        setListError(messageOf(err, "Could not load services"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, page, nameFilter, activeFilter, statusFilter, reload]);

  /** Abre/fecha a linha de detalhe, semeando o form de edição com o serviço. */
  function onToggleDetails(service: ServiceResponse): void {
    if (expandedId === service.id) {
      setExpandedId(null);
      setEditError(null);
      return;
    }
    setExpandedId(service.id);
    setEditForm({
      name: service.name,
      description: service.description ?? "",
    });
    setEditError(null);
  }

  /** POST .../services — criação (name obrigatório, description opcional). */
  async function onCreateService(
    event: FormEvent<HTMLFormElement>
  ): Promise<void> {
    event.preventDefault();
    const name = createForm.name.trim();
    const description = createForm.description.trim();
    if (!canManage || !name || busy) return;

    setCreating(true);
    setCreateError(null);
    try {
      const created = await createService(orgId, projectId, {
        name,
        ...(description ? { description } : {}),
      });
      setCreateForm({ name: "", description: "" });
      setNotice({
        kind: "success",
        text: `Service "${created.name}" created.`,
      });
      setReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setCreateError(
        serviceActionError(
          err,
          "Could not create the service. Is the backend running?"
        )
      );
    } finally {
      setCreating(false);
    }
  }

  /** PATCH .../services/{serviceId} — edição parcial de name/description. */
  async function onSaveEdit(
    event: FormEvent<HTMLFormElement>,
    service: ServiceResponse
  ): Promise<void> {
    event.preventDefault();
    const name = editForm.name.trim();
    if (!canManage || !name || busy) return;

    setSaving(true);
    setEditError(null);
    try {
      const updated = await updateService(orgId, projectId, service.id, {
        name,
        description: editForm.description.trim(),
      });
      setServices((prev) =>
        prev.map((item) => (item.id === updated.id ? updated : item))
      );
      setNotice({
        kind: "success",
        text: `Service "${updated.name}" updated.`,
      });
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setEditError(serviceActionError(err, "Could not update the service."));
    } finally {
      setSaving(false);
    }
  }

  /**
   * PATCH .../services/{serviceId}/activation — ativar/desativar o cadastro
   * (idempotente). NÃO confundir com o status operacional: `active` é o ciclo
   * de vida do serviço, `status` é o que a API reporta sobre a saúde dele.
   */
  async function onToggleActivation(service: ServiceResponse): Promise<void> {
    if (!canManage || busy) return;

    setTogglingId(service.id);
    setNotice(null);
    try {
      const updated = await activateService(
        orgId,
        projectId,
        service.id,
        !service.active
      );
      setServices((prev) =>
        prev.map((item) => (item.id === updated.id ? updated : item))
      );
      setNotice({
        kind: "success",
        text: `Service "${updated.name}" ${
          updated.active ? "activated" : "deactivated"
        }.`,
      });
      // Com filtro de ciclo de vida ativo o serviço pode sair/entrar da página.
      if (activeFilter !== "all") setReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setNotice({
        kind: "error",
        text: serviceActionError(
          err,
          "Could not update the service activation."
        ),
      });
    } finally {
      setTogglingId(null);
    }
  }

  return (
    <section
      id="services"
      className="mt-3 rounded-md border border-(--border) bg-(--card) p-4"
    >
      <h3 className="font-semibold text-white">Services — {project.name}</h3>

      {/* Projeto inativo → leitura ok, mutação devolve 409 PROJECT_INACTIVE */}
      {!project.active && (
        <p className="mt-2 rounded-md border border-(--border) bg-(--surface) px-3 py-2 text-sm text-(--muted)">
          <span className="font-medium text-white">
            This project is inactive;
          </span>{" "}
          services can be viewed but not modified.
        </p>
      )}

      {project.role === "VIEWER" && (
        <p className="mt-2 text-sm text-(--muted)">
          Your role in this organization is{" "}
          <span className="font-medium text-white">VIEWER</span>. You can view
          services; creating, editing and activation require the{" "}
          <span className="font-medium text-white">ADMIN</span> or{" "}
          <span className="font-medium text-white">OPERATOR</span> role.
        </p>
      )}

      {/* Busca por nome (debounce) + filtros de ciclo de vida e de status */}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <label htmlFor={`service-search-${project.id}`} className="sr-only">
          Search services by name
        </label>
        <input
          id={`service-search-${project.id}`}
          type="search"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          placeholder="Search by name…"
          className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        />
        <label
          htmlFor={`service-active-filter-${project.id}`}
          className="sr-only"
        >
          Filter services by activation
        </label>
        <select
          id={`service-active-filter-${project.id}`}
          value={activeFilter}
          onChange={(event) => {
            setActiveFilter(event.target.value as ServiceActiveFilter);
            setPage(0);
          }}
          className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        >
          <option value="all">All</option>
          <option value="active">Active</option>
          <option value="inactive">Inactive</option>
        </select>
        <label
          htmlFor={`service-status-filter-${project.id}`}
          className="sr-only"
        >
          Filter services by status
        </label>
        <select
          id={`service-status-filter-${project.id}`}
          value={statusFilter}
          onChange={(event) => {
            setStatusFilter(event.target.value as ServiceStatusFilter);
            setPage(0);
          }}
          className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        >
          <option value="all">All statuses</option>
          {SERVICE_STATUS_OPTIONS.map((option) => (
            <option key={option} value={option}>
              {STATUS_LABELS[option]}
            </option>
          ))}
        </select>
      </div>

      {/* Erro da listagem ≠ lista vazia: caixa própria com Retry */}
      {status === "error" ? (
        <div className="mt-3 rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-3 text-sm">
          <p className="text-(--destructive)">
            {listError ?? "Could not load services"}
          </p>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            className="mt-2 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white"
          >
            Retry
          </button>
        </div>
      ) : status === "ready" && services.length === 0 ? (
        <p className="mt-3 text-sm text-(--muted)">
          {hasFilter
            ? "No services match your filters."
            : canManage
              ? "No services yet. Sentinel will monitor these services for availability and degradation — add the first one below."
              : "No services yet. Sentinel will monitor services for availability and degradation."}
        </p>
      ) : status === "ready" ? (
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-(--border) text-left text-(--muted)">
                <th className="py-2 pr-3 font-medium">Name</th>
                <th className="py-2 pr-3 font-medium">Status</th>
                <th className="py-2 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {services.map((service) => {
                const isExpanded = expandedId === service.id;
                return (
                  <Fragment key={service.id}>
                    <tr className="border-b border-(--border)/30">
                      <td className="py-2 pr-3 font-medium text-white">
                        {service.name}
                      </td>
                      <td className="py-2 pr-3">
                        {/* Dois badges independentes: ciclo de vida × status
                            operacional — nunca um no lugar do outro. */}
                        <span className="flex flex-wrap items-center gap-2">
                          <ActiveBadge active={service.active} />
                          <StatusBadge status={service.status} />
                        </span>
                      </td>
                      <td className="py-2 text-right">
                        <span className="flex justify-end gap-2">
                          <button
                            type="button"
                            onClick={() => onToggleDetails(service)}
                            aria-expanded={isExpanded}
                            disabled={busy}
                            className="rounded-md border border-(--border) px-3 py-2.5 min-h-[44px] text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                          >
                            {isExpanded ? "Close" : "Details"}
                          </button>
                          <button
                            type="button"
                            onClick={() => void onToggleActivation(service)}
                            disabled={!canManage || busy}
                            className="rounded-md border border-(--border) px-3 py-2.5 min-h-[44px] text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                          >
                            {togglingId === service.id
                              ? "Saving…"
                              : service.active
                                ? "Deactivate"
                                : "Activate"}
                          </button>
                        </span>
                      </td>
                    </tr>

                    {/* Detalhe inline = edição de name/description */}
                    {isExpanded && (
                      <tr className="border-b border-(--border)/30">
                        <td colSpan={3} className="py-3 pr-3">
                          <div className="flex flex-col gap-2 rounded-md border border-(--border) bg-(--surface) p-3">
                            <p className="text-sm text-(--muted)">
                              {service.description ?? "No description."}
                            </p>
                            <p className="text-xs text-(--muted)">
                              Created {service.createdAt} · Updated{" "}
                              {service.updatedAt} · Your role {service.role}
                            </p>
                            <p className="flex flex-wrap items-center gap-2 text-xs text-(--muted)">
                              <span>Activation:</span>
                              <ActiveBadge active={service.active} />
                              <span>Status:</span>
                              <StatusBadge status={service.status} />
                            </p>

                            {canManage ? (
                              <form
                                onSubmit={(event) =>
                                  void onSaveEdit(event, service)
                                }
                                className="flex flex-col gap-2"
                              >
                                <label
                                  htmlFor={`service-edit-name-${service.id}`}
                                  className="sr-only"
                                >
                                  Service name
                                </label>
                                <input
                                  id={`service-edit-name-${service.id}`}
                                  value={editForm.name}
                                  onChange={(event) =>
                                    setEditForm((form) => ({
                                      ...form,
                                      name: event.target.value,
                                    }))
                                  }
                                  disabled={busy}
                                  className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
                                />
                                <label
                                  htmlFor={`service-edit-description-${service.id}`}
                                  className="sr-only"
                                >
                                  Service description
                                </label>
                                <input
                                  id={`service-edit-description-${service.id}`}
                                  value={editForm.description}
                                  onChange={(event) =>
                                    setEditForm((form) => ({
                                      ...form,
                                      description: event.target.value,
                                    }))
                                  }
                                  placeholder="Description (optional)"
                                  disabled={busy}
                                  className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
                                />
                                <div className="flex flex-wrap items-center gap-2">
                                  <button
                                    type="submit"
                                    disabled={
                                      busy || editForm.name.trim() === ""
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
                                Read-only — your role cannot edit services.
                              </p>
                            )}

                            {/* Métricas históricas do serviço (Prompt 11) —
                                `key` nasce limpo por serviço, mesmo padrão do
                                MonitorsPanel abaixo. */}
                            <MetricsSection
                              key={`metrics-${service.id}`}
                              title="Service metrics"
                              organizationId={orgId}
                              projectId={projectId}
                              serviceId={service.id}
                              anchorId="metrics"
                            />

                            {/* Alert Rules do serviço (Prompt 12) — monta/
                                desmonta com o serviço (`key={service.id}`),
                                estado nasce limpo por serviço. */}
                            <AlertRulesPanel
                              key={`alert-rules-${service.id}`}
                              service={service}
                            />

                            {/* Alertas disparados do serviço (Prompt 13) —
                                monta/desmonta com o serviço (`key={service.id}`),
                                estado nasce limpo por serviço. Somente leitura. */}
                            <AlertsPanel
                              key={`alerts-${service.id}`}
                              service={service}
                            />

                            {/* Incidentes do serviço (Prompt 14) — monta/
                                desmonta com o serviço (`key={service.id}`),
                                estado nasce limpo por serviço. Ciclo de vida
                                manual + timeline append-only. */}
                            <IncidentsPanel
                              key={`incidents-${service.id}`}
                              service={service}
                            />

                            {/* Monitors do serviço expandido (Prompt 08) —
                                monta/desmonta com o serviço (`key={service.id}`),
                                então o estado nasce limpo por serviço. */}
                            <MonitorsPanel key={service.id} service={service} />
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
              {totalElements === 1 ? "service" : "services"} · Page{" "}
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
        <p className="mt-3 text-sm text-(--muted)" role="status">Loading services…</p>
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

      {/* Criar serviço — oculto para VIEWER (controle mutável sem permissão) */}
      {canManage && (
        <form
          onSubmit={onCreateService}
          className="mt-4 flex flex-col gap-2 border-t border-(--border) pt-4"
        >
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor={`service-create-name-${project.id}`} className="sr-only">
              Service name
            </label>
            <input
              id={`service-create-name-${project.id}`}
              value={createForm.name}
              onChange={(event) =>
                setCreateForm((form) => ({
                  ...form,
                  name: event.target.value,
                }))
              }
              placeholder="New service name"
              disabled={busy}
              className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
            />
            <button
              type="submit"
              disabled={busy || createForm.name.trim() === ""}
              className="rounded-md bg-(--accent) px-4 py-3 min-h-[44px] text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
            >
              {creating ? "Creating…" : "Create service"}
            </button>
          </div>
          <label
            htmlFor={`service-create-description-${project.id}`}
            className="sr-only"
          >
            Service description
          </label>
          <input
            id={`service-create-description-${project.id}`}
            value={createForm.description}
            onChange={(event) =>
              setCreateForm((form) => ({
                ...form,
                description: event.target.value,
              }))
            }
            placeholder="Description (optional)"
            disabled={busy}
            className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
          />
          {createError && (
            <p className="text-sm text-(--destructive)">{createError}</p>
          )}
        </form>
      )}
    </section>
  );
}
