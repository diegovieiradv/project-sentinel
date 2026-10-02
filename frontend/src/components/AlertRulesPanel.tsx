/* Sentinel Prompt 12 — Alert Rules aninhadas no serviço */
"use client";

import { FormEvent, Fragment, useEffect, useMemo, useState } from "react";
import {
  ApiError,
  activateAlertRule,
  createAlertRule,
  listAlertRules,
  listMonitors,
  updateAlertRule,
  type AlertOperator,
  type AlertRuleListParams,
  type AlertRuleResponse,
  type AlertRuleType,
  type AlertSeverity,
  type EvaluationWindow,
  type MonitorResponse,
  type ServiceResponse,
  type ServiceStatus,
} from "@/lib/api";
import {
  OPERATOR_LABELS,
  SEVERITY_LABELS,
  STATUS_THRESHOLD_LABELS,
  TYPE_LABELS,
  WINDOW_LABELS,
  alertRulesViewState,
  allowedOperatorsFor,
  buildCreateAlertRuleRequest,
  buildUpdateAlertRuleRequest,
  canManageAlertRules,
  emptyAlertRuleForm,
  formValuesFromRule,
  ruleScopeLabel,
  thresholdFieldFor,
  thresholdSummary,
  validateAlertRuleForm,
  withTypeChange,
  type AlertRuleFormValues,
  type AlertRulesListStatus,
} from "@/lib/alert-rules";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";

type RulesStatus = AlertRulesListStatus;

/** Aviso sob a lista — sucesso (accent) ou falha de ação (destructive). */
type RuleNotice = { kind: "success" | "error"; text: string } | null;

/** Filtro de ciclo de vida → query param opcional `active=true|false`. */
type RuleActiveFilter = "all" | "active" | "inactive";

/** Tamanho da página enviada ao server (contrato: default 20, máx 100). */
const RULES_PAGE_SIZE = 10;

/** Opções de filtro — espelham os enums do backend. */
const RULE_TYPE_OPTIONS: AlertRuleType[] = [
  "AVAILABILITY",
  "SUCCESS_RATE",
  "AVERAGE_LATENCY",
  "TARGET_FAILURE_COUNT",
  "SERVICE_STATUS",
];

const RULE_SEVERITY_OPTIONS: AlertSeverity[] = ["SEV1", "SEV2", "SEV3", "SEV4"];

const RULE_WINDOW_OPTIONS: EvaluationWindow[] = [
  "FIVE_MINUTES",
  "FIFTEEN_MINUTES",
  "ONE_HOUR",
  "SIX_HOURS",
  "TWENTY_FOUR_HOURS",
];

/**
 * Mensagem legível de uma falha de mutação de regra.
 * - **403** → papel insuficiente (feedback; NUNCA logout — só 401 derruba a sessão).
 * - **409** `ALERT_RULE_NAME_DUPLICATE` → nome já existe NESTE serviço.
 * - **409** `SERVICE_INACTIVE`/`PROJECT_INACTIVE` → pai inativo: leitura ok,
 *   mutação não (desativar continua permitido — fail-safe do backend).
 * - Demais (**400** `VALIDATION_ERROR`, …) → a mensagem do servidor, que é
 *   mais específica do que qualquer texto genérico aqui.
 */
function ruleActionError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You don't have permission to manage alert rules in this organization.";
    }
    if (err.code === "ALERT_RULE_NAME_DUPLICATE") {
      return "An alert rule with this name already exists in this service.";
    }
    if (err.code === "SERVICE_INACTIVE") {
      return "This service is inactive; alert rules can be viewed but not modified. Deactivating a rule is still allowed.";
    }
    if (err.code === "PROJECT_INACTIVE") {
      return "This project is inactive; alert rules can be viewed but not modified.";
    }
    return err.message;
  }
  return fallback;
}

/** Badge de ciclo de vida (contrato `active`). */
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

/**
 * Badge de severidade — SEMPRE com texto (`SEV1 — Critical`), nunca só cor.
 * A cor reforça; a palavra comunica.
 */
function SeverityBadge({ severity }: { severity: AlertSeverity }) {
  const className: Record<AlertSeverity, string> = {
    SEV1: "border-(--destructive)/50 bg-(--destructive)/10 text-(--destructive)",
    SEV2: "border-(--accent-soft)/50 bg-(--accent-soft)/10 text-(--accent-soft)",
    SEV3: "border-(--accent)/50 bg-(--accent)/10 text-(--accent)",
    SEV4: "border-(--border-hover) bg-(--muted)/10 text-(--muted)",
  };
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-semibold ${className[severity]}`}
    >
      <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-current" />
      {SEVERITY_LABELS[severity]}
    </span>
  );
}

/** Badge de escopo: `Service-level` ou `Monitor: {nome}`. */
function ScopeBadge({ label }: { label: string }) {
  return (
    <span className="rounded border border-(--border) px-1.5 py-0.5 text-xs font-semibold text-(--muted)">
      {label}
    </span>
  );
}

/**
 * Formulário de condição (criação e edição) — o campo de threshold muda
 * conforme o tipo: percentual `%`, latência `ms`, contagem inteira ou
 * select de status (`SERVICE_STATUS` NUNCA tem input numérico).
 */
function ThresholdInput({
  form,
  disabled,
  onChange,
  idPrefix,
}: {
  form: AlertRuleFormValues;
  disabled: boolean;
  onChange: (next: AlertRuleFormValues) => void;
  idPrefix: string;
}) {
  const field = thresholdFieldFor(form.type);
  if (field.kind === "status") {
    return (
      <div className="flex flex-col gap-1">
        <label htmlFor={`${idPrefix}-threshold-status`} className="text-xs text-(--muted)">
          Threshold status
        </label>
        <select
          id={`${idPrefix}-threshold-status`}
          value={form.thresholdStatus}
          onChange={(event) =>
            onChange({
              ...form,
              thresholdStatus: event.target.value as ServiceStatus | "",
            })
          }
          disabled={disabled}
          className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
        >
          <option value="">Select status…</option>
          {(field.options ?? []).map((option) => (
            <option key={option} value={option}>
              {STATUS_THRESHOLD_LABELS[option] ?? option}
            </option>
          ))}
        </select>
        <p className="text-xs text-(--muted)">{field.hint}</p>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={`${idPrefix}-threshold-numeric`} className="text-xs text-(--muted)">
        Threshold{field.unit ? ` (${field.unit})` : ""}
      </label>
      <span className="flex items-center gap-2">
        <input
          id={`${idPrefix}-threshold-numeric`}
          type="text"
          inputMode="decimal"
          value={form.thresholdNumeric}
          onChange={(event) =>
            onChange({ ...form, thresholdNumeric: event.target.value })
          }
          placeholder={field.placeholder}
          disabled={disabled}
          className="w-full rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white placeholder:text-(--muted) focus:border-(--accent) focus:outline-none disabled:opacity-60"
        />
        {field.unit && (
          <span className="shrink-0 text-xs text-(--muted)" aria-hidden="true">
            {field.unit}
          </span>
        )}
      </span>
      <p className="text-xs text-(--muted)">{field.hint}</p>
    </div>
  );
}

/**
 * Alert Rules do serviço selecionado (Prompt 12).
 *
 * Hierarquia: organização ativa → `ProjectsPanel` → projeto expandido →
 * `ServicesPanel` → serviço expandido → este painel. O pai o monta junto com a
 * linha de detalhe usando `key={`alert-rules-${service.id}`}`, então o estado
 * nasce limpo por serviço — mesmo padrão do `MonitorsPanel`.
 *
 * RBAC aqui é só UX: VIEWER não recebe controles mutáveis funcionais, mas a
 * autoridade continua no backend — um **403** vira mensagem na UI, jamais
 * logout. Serviço/projeto pai inativo mantém a leitura; o backend responde
 * **409** em criar/editar/ativar (exibido aqui) e continua permitindo
 * desativar (fail-safe).
 *
 * Sem dados fake: a lista vem SEMPRE de `listAlertRules` (a API é a única
 * fonte de regras deste componente).
 */
export default function AlertRulesPanel({
  service,
}: {
  service: ServiceResponse;
}) {
  const orgId = service.organizationId;
  const projectId = service.projectId;
  const serviceId = service.id;
  /** VIEWER é somente-leitura no cliente (o backend continua sendo a autoridade). */
  const canManage = canManageAlertRules(service.role);

  const [rules, setRules] = useState<AlertRuleResponse[]>([]);
  const [status, setStatus] = useState<RulesStatus>("idle");
  const [reload, setReload] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Busca por nome com debounce simples: `searchInput` é o digitado,
  // `nameFilter` é o valor já aplicado na query (evita refetch por tecla).
  const [searchInput, setSearchInput] = useState("");
  const [nameFilter, setNameFilter] = useState("");
  const [activeFilter, setActiveFilter] = useState<RuleActiveFilter>("all");
  const [typeFilter, setTypeFilter] = useState<AlertRuleType | "all">("all");
  const [severityFilter, setSeverityFilter] = useState<AlertSeverity | "all">("all");

  // Monitores do MESMO serviço — só para o select de escopo e os nomes dos
  // badges (o contrato de regra não envia o nome do monitor).
  const [monitors, setMonitors] = useState<MonitorResponse[]>([]);

  const [createForm, setCreateForm] = useState<AlertRuleFormValues>(
    emptyAlertRuleForm()
  );
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Detalhe inline (expansão da linha) = também editor da condição.
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<AlertRuleFormValues>(
    emptyAlertRuleForm()
  );
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [notice, setNotice] = useState<RuleNotice>(null);

  const busy = creating || saving || togglingId !== null;
  const hasFilter =
    nameFilter !== "" || activeFilter !== "all" ||
    typeFilter !== "all" || severityFilter !== "all";

  // Decisão de renderização da listagem — função pura em lib/alert-rules.ts
  // (critérios 99–102 testáveis sem DOM).
  const view = alertRulesViewState(status, rules.length);

  const monitorNameById = useMemo(() => {
    const map: Record<string, string> = {};
    for (const monitor of monitors) map[monitor.id] = monitor.name;
    return map;
  }, [monitors]);

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
        const params: AlertRuleListParams = { page, size: RULES_PAGE_SIZE };
        if (nameFilter) params.name = nameFilter;
        if (activeFilter !== "all") params.active = activeFilter === "active";
        if (typeFilter !== "all") params.type = typeFilter;
        if (severityFilter !== "all") params.severity = severityFilter;

        const data = await listAlertRules(orgId, projectId, serviceId, params);
        if (cancelled) return;
        setRules(data.content);
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
        setListError(messageOf(err, "Could not load alert rules"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, serviceId, page, nameFilter, activeFilter, typeFilter, severityFilter, reload]);

  // Monitores do serviço (escopo + nomes) — melhor esforço: falha aqui não
  // derruba a lista de regras (os badges caem no prefixo do id).
  useEffect(() => {
    let cancelled = false;

    async function loadMonitors(): Promise<void> {
      try {
        const data = await listMonitors(orgId, projectId, serviceId, {
          size: 100,
        });
        if (!cancelled) setMonitors(data.content);
      } catch {
        // Silencioso — nomes de monitor são enriquecimento, não dado crítico.
      }
    }

    void loadMonitors();
    return () => {
      cancelled = true;
    };
  }, [orgId, projectId, serviceId, reload]);

  /** Abre/fecha a linha de detalhe, semeando o form de edição com a regra. */
  function onToggleDetails(rule: AlertRuleResponse): void {
    if (expandedId === rule.id) {
      setExpandedId(null);
      setEditError(null);
      return;
    }
    setExpandedId(rule.id);
    setEditForm(formValuesFromRule(rule));
    setEditError(null);
  }

  /** POST .../alert-rules — criação da condição (validada no cliente e no server). */
  async function onCreateRule(
    event: FormEvent<HTMLFormElement>
  ): Promise<void> {
    event.preventDefault();
    if (!canManage || busy) return;

    const request = buildCreateAlertRuleRequest(createForm);
    if (!request) {
      setCreateError(validateAlertRuleForm(createForm) ?? "Fix the highlighted fields.");
      return;
    }

    setCreating(true);
    setCreateError(null);
    try {
      const created = await createAlertRule(orgId, projectId, serviceId, request);
      setCreateForm(emptyAlertRuleForm());
      setNotice({
        kind: "success",
        text: `Alert rule "${created.name}" created.`,
      });
      setReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setCreateError(
        ruleActionError(err, "Could not create the alert rule. Is the backend running?")
      );
    } finally {
      setCreating(false);
    }
  }

  /** PATCH .../alert-rules/{ruleId} — edição parcial da condição. */
  async function onSaveEdit(
    event: FormEvent<HTMLFormElement>,
    rule: AlertRuleResponse
  ): Promise<void> {
    event.preventDefault();
    if (!canManage || busy) return;

    const request = buildUpdateAlertRuleRequest(editForm);
    if (!request) {
      setEditError(validateAlertRuleForm(editForm) ?? "Fix the highlighted fields.");
      return;
    }

    setSaving(true);
    setEditError(null);
    try {
      const updated = await updateAlertRule(
        orgId,
        projectId,
        serviceId,
        rule.id,
        request
      );
      setRules((prev) =>
        prev.map((item) => (item.id === updated.id ? updated : item))
      );
      setNotice({
        kind: "success",
        text: `Alert rule "${updated.name}" updated.`,
      });
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setEditError(ruleActionError(err, "Could not update the alert rule."));
    } finally {
      setSaving(false);
    }
  }

  /**
   * PATCH .../alert-rules/{ruleId}/activation — ativar/desativar a regra
   * (idempotente). Desativar é SEMPRE permitido, mesmo com o pai inativo
   * (fail-safe do backend); ativar com o pai inativo devolve 409 (exibido).
   */
  async function onToggleActivation(rule: AlertRuleResponse): Promise<void> {
    if (!canManage || busy) return;

    setTogglingId(rule.id);
    setNotice(null);
    try {
      const updated = await activateAlertRule(
        orgId,
        projectId,
        serviceId,
        rule.id,
        !rule.active
      );
      setRules((prev) =>
        prev.map((item) => (item.id === updated.id ? updated : item))
      );
      setNotice({
        kind: "success",
        text: `Alert rule "${updated.name}" ${
          updated.active ? "activated" : "deactivated"
        }.`,
      });
      if (activeFilter !== "all") setReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setNotice({
        kind: "error",
        text: ruleActionError(err, "Could not update the alert rule activation."),
      });
    } finally {
      setTogglingId(null);
    }
  }

  return (
    <section className="mt-3 rounded-md border border-(--border) bg-(--card) p-4">
      <h3 className="font-semibold text-white">Alert rules — {service.name}</h3>

      {/* Fronteira do Prompt 13: aqui só se CONFIGURA a condição; nada avalia
          nem dispara (avaliação/firing são do Alert Engine, futuro). */}
      <p className="mt-1 text-xs text-(--muted)">
        Configure the conditions Sentinel will evaluate. Rules are stored now;
        evaluation comes with the Alert Engine.
      </p>

      {/* Serviço inativo → leitura ok, mutação devolve 409 SERVICE_INACTIVE */}
      {!service.active && (
        <p className="mt-2 rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-(--muted)">
          <span className="font-medium text-white">
            This service is inactive;
          </span>{" "}
          alert rules can be viewed but not modified.
        </p>
      )}

      {service.role === "VIEWER" && (
        <p className="mt-2 text-sm text-(--muted)">
          Your role in this organization is{" "}
          <span className="font-medium text-white">VIEWER</span>. You can view
          alert rules; creating, editing and activation require the{" "}
          <span className="font-medium text-white">ADMIN</span> or{" "}
          <span className="font-medium text-white">OPERATOR</span> role.
        </p>
      )}

      {/* Busca por nome (debounce) + filtros de ciclo de vida, tipo e severidade */}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <label htmlFor={`rule-search-${service.id}`} className="sr-only">
          Search alert rules by name
        </label>
        <input
          id={`rule-search-${service.id}`}
          type="search"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          placeholder="Search by name…"
          className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white placeholder:text-(--muted) focus:border-(--accent) focus:outline-none disabled:opacity-60"
        />
        <label htmlFor={`rule-active-filter-${service.id}`} className="sr-only">
          Filter alert rules by activation
        </label>
        <select
          id={`rule-active-filter-${service.id}`}
          value={activeFilter}
          onChange={(event) => {
            setActiveFilter(event.target.value as RuleActiveFilter);
            setPage(0);
          }}
          className="rounded-md border border-(--border) bg-(--background) px-2 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
        >
          <option value="all">All</option>
          <option value="active">Active</option>
          <option value="inactive">Inactive</option>
        </select>
        <label htmlFor={`rule-type-filter-${service.id}`} className="sr-only">
          Filter alert rules by type
        </label>
        <select
          id={`rule-type-filter-${service.id}`}
          value={typeFilter}
          onChange={(event) => {
            setTypeFilter(event.target.value as AlertRuleType | "all");
            setPage(0);
          }}
          className="rounded-md border border-(--border) bg-(--background) px-2 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
        >
          <option value="all">All types</option>
          {RULE_TYPE_OPTIONS.map((type) => (
            <option key={type} value={type}>
              {TYPE_LABELS[type]}
            </option>
          ))}
        </select>
        <label htmlFor={`rule-severity-filter-${service.id}`} className="sr-only">
          Filter alert rules by severity
        </label>
        <select
          id={`rule-severity-filter-${service.id}`}
          value={severityFilter}
          onChange={(event) => {
            setSeverityFilter(event.target.value as AlertSeverity | "all");
            setPage(0);
          }}
          className="rounded-md border border-(--border) bg-(--background) px-2 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
        >
          <option value="all">All severities</option>
          {RULE_SEVERITY_OPTIONS.map((severity) => (
            <option key={severity} value={severity}>
              {SEVERITY_LABELS[severity]}
            </option>
          ))}
        </select>
      </div>

      {/* Erro da listagem ≠ lista vazia: caixa própria com Retry */}
      {view === "error" ? (
        <div className="mt-3 rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-3 text-sm">
          <p className="text-(--destructive)">
            {listError ?? "Could not load alert rules"}
          </p>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            className="mt-2 rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white"
          >
            Retry
          </button>
        </div>
      ) : view === "empty" ? (
        <p className="mt-3 text-sm text-(--muted)">
          {hasFilter
            ? "No alert rules match your filters."
            : canManage
              ? "No alert rules yet. Define the conditions Sentinel should evaluate — add the first one below."
              : "No alert rules yet for this service."}
        </p>
      ) : view === "list" ? (
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-(--border) text-left text-(--muted)">
                <th className="py-2 pr-3 font-medium">Name</th>
                <th className="py-2 pr-3 font-medium">Condition</th>
                <th className="py-2 pr-3 font-medium">Scope</th>
                <th className="py-2 pr-3 font-medium">Severity</th>
                <th className="py-2 pr-3 font-medium">Window</th>
                <th className="py-2 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((rule) => {
                const isExpanded = expandedId === rule.id;
                return (
                  <Fragment key={rule.id}>
                    <tr className="border-b border-(--border)/30">
                      <td className="py-2 pr-3 font-medium text-white">
                        <span className="flex flex-wrap items-center gap-2">
                          {rule.name}
                          <ActiveBadge active={rule.active} />
                        </span>
                      </td>
                      <td className="py-2 pr-3 text-(--muted)">
                        {thresholdSummary(rule)}
                      </td>
                      <td className="py-2 pr-3">
                        <ScopeBadge
                          label={ruleScopeLabel(rule, monitorNameById)}
                        />
                      </td>
                      <td className="py-2 pr-3">
                        <SeverityBadge severity={rule.severity} />
                      </td>
                      <td className="py-2 pr-3 text-(--muted)">
                        {WINDOW_LABELS[rule.window]}
                      </td>
                      <td className="py-2 text-right">
                        <span className="flex justify-end gap-2">
                          <button
                            type="button"
                            onClick={() => onToggleDetails(rule)}
                            aria-expanded={isExpanded}
                            disabled={busy}
                            className="rounded-md border border-(--border) px-2 py-1 text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                          >
                            {isExpanded ? "Close" : "Details"}
                          </button>
                          <button
                            type="button"
                            onClick={() => void onToggleActivation(rule)}
                            disabled={!canManage || busy}
                            className="rounded-md border border-(--border) px-2 py-1 text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                          >
                            {togglingId === rule.id
                              ? "Saving…"
                              : rule.active
                                ? "Deactivate"
                                : "Activate"}
                          </button>
                        </span>
                      </td>
                    </tr>

                    {/* Detalhe inline = edição da condição completa */}
                    {isExpanded && (
                      <tr className="border-b border-(--border)/30">
                        <td colSpan={6} className="py-3 pr-3">
                          <div className="flex flex-col gap-2 rounded-md border border-(--border) bg-(--background) p-3">
                            <p className="text-xs text-(--muted)">
                              Created {rule.createdAt} · Updated{" "}
                              {rule.updatedAt} · Your role {rule.role}
                            </p>
                            <p className="flex flex-wrap items-center gap-2 text-xs text-(--muted)">
                              <span>Activation:</span>
                              <ActiveBadge active={rule.active} />
                              <span>Scope:</span>
                              <ScopeBadge
                                label={ruleScopeLabel(rule, monitorNameById)}
                              />
                              <span>Severity:</span>
                              <SeverityBadge severity={rule.severity} />
                            </p>

                            {canManage ? (
                              <form
                                onSubmit={(event) =>
                                  void onSaveEdit(event, rule)
                                }
                                className="flex flex-col gap-3"
                              >
                                <div className="flex flex-col gap-1">
                                  <label
                                    htmlFor={`rule-edit-name-${rule.id}`}
                                    className="text-xs text-(--muted)"
                                  >
                                    Name
                                  </label>
                                  <input
                                    id={`rule-edit-name-${rule.id}`}
                                    value={editForm.name}
                                    onChange={(event) =>
                                      setEditForm((form) => ({
                                        ...form,
                                        name: event.target.value,
                                      }))
                                    }
                                    disabled={busy}
                                    className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
                                  />
                                </div>
                                <div className="flex flex-wrap gap-3">
                                  <div className="flex flex-col gap-1">
                                    <label
                                      htmlFor={`rule-edit-type-${rule.id}`}
                                      className="text-xs text-(--muted)"
                                    >
                                      Type
                                    </label>
                                    <select
                                      id={`rule-edit-type-${rule.id}`}
                                      value={editForm.type}
                                      onChange={(event) =>
                                        setEditForm((form) =>
                                          withTypeChange(
                                            form,
                                            event.target.value as AlertRuleType
                                          )
                                        )
                                      }
                                      disabled={busy}
                                      className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
                                    >
                                      {RULE_TYPE_OPTIONS.map((type) => (
                                        <option key={type} value={type}>
                                          {TYPE_LABELS[type]}
                                        </option>
                                      ))}
                                    </select>
                                  </div>
                                  <div className="flex flex-col gap-1">
                                    <label
                                      htmlFor={`rule-edit-operator-${rule.id}`}
                                      className="text-xs text-(--muted)"
                                    >
                                      Operator
                                    </label>
                                    <select
                                      id={`rule-edit-operator-${rule.id}`}
                                      value={editForm.operator}
                                      onChange={(event) =>
                                        setEditForm((form) => ({
                                          ...form,
                                          operator: event.target
                                            .value as AlertOperator,
                                        }))
                                      }
                                      disabled={busy}
                                      className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
                                    >
                                      {allowedOperatorsFor(editForm.type).map(
                                        (operator) => (
                                          <option
                                            key={operator}
                                            value={operator}
                                          >
                                            {OPERATOR_LABELS[operator]}{" "}
                                            {operator}
                                          </option>
                                        )
                                      )}
                                    </select>
                                  </div>
                                  <div className="flex flex-col gap-1">
                                    <label
                                      htmlFor={`rule-edit-window-${rule.id}`}
                                      className="text-xs text-(--muted)"
                                    >
                                      Window
                                    </label>
                                    <select
                                      id={`rule-edit-window-${rule.id}`}
                                      value={editForm.window}
                                      onChange={(event) =>
                                        setEditForm((form) => ({
                                          ...form,
                                          window: event.target
                                            .value as EvaluationWindow,
                                        }))
                                      }
                                      disabled={busy}
                                      className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
                                    >
                                      {RULE_WINDOW_OPTIONS.map((window) => (
                                        <option key={window} value={window}>
                                          {WINDOW_LABELS[window]}
                                        </option>
                                      ))}
                                    </select>
                                  </div>
                                  <div className="flex flex-col gap-1">
                                    <label
                                      htmlFor={`rule-edit-severity-${rule.id}`}
                                      className="text-xs text-(--muted)"
                                    >
                                      Severity
                                    </label>
                                    <select
                                      id={`rule-edit-severity-${rule.id}`}
                                      value={editForm.severity}
                                      onChange={(event) =>
                                        setEditForm((form) => ({
                                          ...form,
                                          severity: event.target
                                            .value as AlertSeverity,
                                        }))
                                      }
                                      disabled={busy}
                                      className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
                                    >
                                      {RULE_SEVERITY_OPTIONS.map((severity) => (
                                        <option key={severity} value={severity}>
                                          {SEVERITY_LABELS[severity]}
                                        </option>
                                      ))}
                                    </select>
                                  </div>
                                </div>

                                <div className="flex flex-wrap gap-3">
                                  <ThresholdInput
                                    form={editForm}
                                    disabled={busy}
                                    onChange={setEditForm}
                                    idPrefix={`rule-edit-${rule.id}`}
                                  />
                                  <div className="flex flex-col gap-1">
                                    <label
                                      htmlFor={`rule-edit-scope-${rule.id}`}
                                      className="text-xs text-(--muted)"
                                    >
                                      Scope
                                    </label>
                                    <select
                                      id={`rule-edit-scope-${rule.id}`}
                                      value={editForm.monitorId}
                                      onChange={(event) =>
                                        setEditForm((form) => ({
                                          ...form,
                                          monitorId: event.target.value,
                                        }))
                                      }
                                      disabled={busy}
                                      className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
                                    >
                                      <option value="">
                                        Service-level (aggregate)
                                      </option>
                                      {monitors.map((monitor) => (
                                        <option
                                          key={monitor.id}
                                          value={monitor.id}
                                        >
                                          Monitor: {monitor.name}
                                        </option>
                                      ))}
                                    </select>
                                    <p className="text-xs text-(--muted)">
                                      Service-level evaluates the service
                                      aggregate; monitor-level evaluates that
                                      monitor.
                                    </p>
                                  </div>
                                </div>

                                <div className="flex flex-wrap items-center gap-2">
                                  <button
                                    type="submit"
                                    disabled={busy || editForm.name.trim() === ""}
                                    className="rounded-md bg-(--accent) px-3 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
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
                                    className="rounded-md border border-(--border) px-3 py-2 text-sm text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
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
                                Read-only — your role cannot edit alert rules.
                              </p>
                            )}
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
              {totalElements === 1 ? "alert rule" : "alert rules"} · Page{" "}
              {Math.min(page + 1, Math.max(totalPages, 1))} of{" "}
              {Math.max(totalPages, 1)}
            </span>
            <span className="flex gap-2">
              <button
                type="button"
                onClick={() => setPage((current) => Math.max(current - 1, 0))}
                disabled={page === 0 || busy}
                className="rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
              >
                Previous
              </button>
              <button
                type="button"
                onClick={() => setPage((current) => current + 1)}
                disabled={page + 1 >= totalPages || busy}
                className="rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
              >
                Next
              </button>
            </span>
          </div>
        </div>
      ) : (
        <p className="mt-3 text-sm text-(--muted)">Loading alert rules…</p>
      )}

      {/* Feedback de sucesso/falha de ações sobre a lista */}
      {view !== "error" && notice && (
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

      {/* Criar regra — oculto para VIEWER (controle mutável sem permissão) */}
      {canManage && (
        <form
          onSubmit={onCreateRule}
          className="mt-4 flex flex-col gap-3 border-t border-(--border) pt-4"
        >
          <p className="text-sm font-medium text-white">New alert rule</p>

          <div className="flex flex-wrap gap-3">
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <label
                htmlFor={`rule-create-name-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Name
              </label>
              <input
                id={`rule-create-name-${service.id}`}
                value={createForm.name}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    name: event.target.value,
                  }))
                }
                placeholder="Availability below 99.9"
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white placeholder:text-(--muted) focus:border-(--accent) focus:outline-none disabled:opacity-60"
              />
            </div>
          </div>

          <div className="flex flex-wrap gap-3">
            <div className="flex flex-col gap-1">
              <label
                htmlFor={`rule-create-type-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Type
              </label>
              <select
                id={`rule-create-type-${service.id}`}
                value={createForm.type}
                onChange={(event) =>
                  setCreateForm((form) =>
                    withTypeChange(form, event.target.value as AlertRuleType)
                  )
                }
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
              >
                {RULE_TYPE_OPTIONS.map((type) => (
                  <option key={type} value={type}>
                    {TYPE_LABELS[type]}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label
                htmlFor={`rule-create-operator-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Operator
              </label>
              <select
                id={`rule-create-operator-${service.id}`}
                value={createForm.operator}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    operator: event.target.value as AlertOperator,
                  }))
                }
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
              >
                {allowedOperatorsFor(createForm.type).map((operator) => (
                  <option key={operator} value={operator}>
                    {OPERATOR_LABELS[operator]} {operator}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label
                htmlFor={`rule-create-window-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Window
              </label>
              <select
                id={`rule-create-window-${service.id}`}
                value={createForm.window}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    window: event.target.value as EvaluationWindow,
                  }))
                }
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
              >
                {RULE_WINDOW_OPTIONS.map((window) => (
                  <option key={window} value={window}>
                    {WINDOW_LABELS[window]}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label
                htmlFor={`rule-create-severity-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Severity
              </label>
              <select
                id={`rule-create-severity-${service.id}`}
                value={createForm.severity}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    severity: event.target.value as AlertSeverity,
                  }))
                }
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
              >
                {RULE_SEVERITY_OPTIONS.map((severity) => (
                  <option key={severity} value={severity}>
                    {SEVERITY_LABELS[severity]}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="flex flex-wrap gap-3">
            <ThresholdInput
              form={createForm}
              disabled={busy}
              onChange={setCreateForm}
              idPrefix={`rule-create-${service.id}`}
            />
            <div className="flex flex-col gap-1">
              <label
                htmlFor={`rule-create-scope-${service.id}`}
                className="text-xs text-(--muted)"
              >
                Scope
              </label>
              <select
                id={`rule-create-scope-${service.id}`}
                value={createForm.monitorId}
                onChange={(event) =>
                  setCreateForm((form) => ({
                    ...form,
                    monitorId: event.target.value,
                  }))
                }
                disabled={busy}
                className="rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
              >
                <option value="">Service-level (aggregate)</option>
                {monitors.map((monitor) => (
                  <option key={monitor.id} value={monitor.id}>
                    Monitor: {monitor.name}
                  </option>
                ))}
              </select>
              <p className="text-xs text-(--muted)">
                Service-level evaluates the service aggregate; monitor-level
                evaluates that monitor.
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="submit"
              disabled={busy || createForm.name.trim() === ""}
              className="rounded-md bg-(--accent) px-3 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
            >
              {creating ? "Creating…" : "Create alert rule"}
            </button>
            {createError && (
              <p className="text-sm text-(--destructive)">{createError}</p>
            )}
          </div>
        </form>
      )}
    </section>
  );
}
