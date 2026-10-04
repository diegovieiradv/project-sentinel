/**
 * Helpers puros de Alert Rules (Prompt 12) — matriz type × operator, campos
 * do formulário por tipo, validação de UX e builders de body.
 *
 * Espelha o contrato do backend (`AlertRuleValidationMatrix`) para feedback
 * IMEDIATO no cliente — a autoridade continua no backend (400
 * `VALIDATION_ERROR` devolve a mensagem definitiva). Nada aqui fala rede nem
 * DOM: ambiente `node` do vitest cobre tudo (ver `alert-rules.test.ts`).
 *
 * Fronteira Prompt 13: este módulo só trata CONFIGURAÇÃO de regra — não existe
 * avaliação, firing, estado de alerta, notificação ou Incident aqui.
 */
import type {
  AlertOperator,
  AlertRuleCreateRequest,
  AlertRuleResponse,
  AlertRuleType,
  AlertRuleUpdateRequest,
  AlertSeverity,
  EvaluationWindow,
  OrganizationRole,
  ServiceStatus,
} from "./api";

/* -------------------------------------------------------------------------- */
/* Matriz type × operator (espelho de AlertRuleValidationMatrix)              */
/* -------------------------------------------------------------------------- */

/**
 * Operadores permitidos POR TIPO — mesmo mapa do backend:
 * percentuais só `<`/`≤`, latência só `>`/`≥`, contagem `>`/`≥`/`=`,
 * `SERVICE_STATUS` só `=`.
 */
export const ALLOWED_OPERATORS: Record<AlertRuleType, readonly AlertOperator[]> = {
  AVAILABILITY: ["LESS_THAN", "LESS_THAN_OR_EQUAL"],
  SUCCESS_RATE: ["LESS_THAN", "LESS_THAN_OR_EQUAL"],
  AVERAGE_LATENCY: ["GREATER_THAN", "GREATER_THAN_OR_EQUAL"],
  TARGET_FAILURE_COUNT: ["GREATER_THAN", "GREATER_THAN_OR_EQUAL", "EQUAL"],
  SERVICE_STATUS: ["EQUAL"],
};

/** Operadores permitidos para o tipo (lista pronta para o `<select>`). */
export function allowedOperatorsFor(type: AlertRuleType): readonly AlertOperator[] {
  return ALLOWED_OPERATORS[type];
}

/* -------------------------------------------------------------------------- */
/* Rótulos — sempre TEXTO legível (severidade nunca é só cor)                 */
/* -------------------------------------------------------------------------- */

/** Símbolo do operador na UI (`<`, `≤`, `>`, `≥`, `=`). */
export const OPERATOR_LABELS: Record<AlertOperator, string> = {
  LESS_THAN: "<",
  LESS_THAN_OR_EQUAL: "≤",
  GREATER_THAN: ">",
  GREATER_THAN_OR_EQUAL: "≥",
  EQUAL: "=",
};

/** Rótulo legível de cada tipo de condição. */
export const TYPE_LABELS: Record<AlertRuleType, string> = {
  AVAILABILITY: "Availability",
  SUCCESS_RATE: "Success rate",
  AVERAGE_LATENCY: "Average latency",
  TARGET_FAILURE_COUNT: "Target failures",
  SERVICE_STATUS: "Service status",
};

/**
 * Severidade com TEXTO — `SEV1` nunca aparece só como cor/bolinha: o rótulo
 * traz a palavra (Critical/High/Moderate/Low) junto do código.
 */
export const SEVERITY_LABELS: Record<AlertSeverity, string> = {
  SEV1: "SEV1 — Critical",
  SEV2: "SEV2 — High",
  SEV3: "SEV3 — Moderate",
  SEV4: "SEV4 — Low",
};

/** Rótulo legível de cada janela de avaliação (só configuração). */
export const WINDOW_LABELS: Record<EvaluationWindow, string> = {
  FIVE_MINUTES: "5 minutes",
  FIFTEEN_MINUTES: "15 minutes",
  ONE_HOUR: "1 hour",
  SIX_HOURS: "6 hours",
  TWENTY_FOUR_HOURS: "24 hours",
};

/** Rótulo do threshold de status — contrato: apenas `DEGRADED`/`UNAVAILABLE`. */
export const STATUS_THRESHOLD_LABELS: Partial<Record<ServiceStatus, string>> = {
  DEGRADED: "Degraded",
  UNAVAILABLE: "Unavailable",
};

/** Rótulo do threshold de status (com fallback defensivo para valor externo). */
export function thresholdStatusLabel(status: ServiceStatus | null): string {
  if (!status) return "—";
  return STATUS_THRESHOLD_LABELS[status] ?? status;
}

/* -------------------------------------------------------------------------- */
/* Campo de threshold por tipo (critérios 106–109)                            */
/* -------------------------------------------------------------------------- */

/** Qual controle o tipo pede: numérico com unidade ou select de status. */
export type ThresholdFieldKind = "percent" | "milliseconds" | "count" | "status";

/** Configuração do campo de threshold para um tipo. */
export interface ThresholdFieldConfig {
  kind: ThresholdFieldKind;
  /** Unidade exibida junto do input (`%`/`ms`) — ausente em status/contagem. */
  unit?: string;
  /** Opções do select — presente SOMENTE no kind `status`. */
  options?: readonly ServiceStatus[];
  /** Dica de formato exibida ao usuário (o backend valida de verdade). */
  hint: string;
  /** Placeholder do input numérico (kind ≠ status). */
  placeholder?: string;
}

/**
 * Campo de threshold por tipo — é daqui que a UI decide o que renderizar:
 * percentual com `%`, latência em `ms`, contagem inteira, e
 * `SERVICE_STATUS` SEM input numérico (select de status no lugar).
 */
export function thresholdFieldFor(type: AlertRuleType): ThresholdFieldConfig {
  switch (type) {
    case "AVAILABILITY":
    case "SUCCESS_RATE":
      return {
        kind: "percent",
        unit: "%",
        hint: "Between 0 and 100, up to 2 decimals.",
        placeholder: "99.9",
      };
    case "AVERAGE_LATENCY":
      return {
        kind: "milliseconds",
        unit: "ms",
        hint: "Greater than 0, up to 4 decimals.",
        placeholder: "800",
      };
    case "TARGET_FAILURE_COUNT":
      return {
        kind: "count",
        hint: "Whole number, 0 or more.",
        placeholder: "3",
      };
    case "SERVICE_STATUS":
      return {
        kind: "status",
        options: ["DEGRADED", "UNAVAILABLE"],
        hint: "Status that triggers the rule.",
      };
  }
}

/** `SERVICE_STATUS` NÃO usa input numérico (usa select de status). */
export function usesNumericThreshold(type: AlertRuleType): boolean {
  return thresholdFieldFor(type).kind !== "status";
}

/* -------------------------------------------------------------------------- */
/* Parsing/validação do threshold (UX; backend é a autoridade)                */
/* -------------------------------------------------------------------------- */

/** Resultado do parse: valor numérico pronto OU mensagem de UX. */
export type ThresholdParseResult =
  | { ok: true; value: number }
  | { ok: false; error: string };

/**
 * Parse do threshold digitado conforme o tipo — espelha as faixas do
 * backend (percentual 0..100 com ≤ 2 casas; latência > 0 com ≤ 4 casas;
 * contagem inteira ≥ 0; status via select, não via texto).
 */
export function parseThresholdInput(raw: string, type: AlertRuleType): ThresholdParseResult {
  const config = thresholdFieldFor(type);
  if (config.kind === "status") {
    return { ok: false, error: "Select a status threshold." };
  }

  const trimmed = raw.trim();
  if (trimmed === "") {
    return { ok: false, error: "Threshold is required." };
  }

  if (config.kind === "count") {
    if (!/^\d+$/.test(trimmed)) {
      return { ok: false, error: "Enter a whole number, 0 or more." };
    }
    const value = Number(trimmed);
    if (!Number.isSafeInteger(value)) {
      return { ok: false, error: "Enter a whole number, 0 or more." };
    }
    return { ok: true, value };
  }

  if (!/^\d+(\.\d+)?$/.test(trimmed)) {
    return {
      ok: false,
      error:
        config.kind === "percent"
          ? "Enter a number between 0 and 100."
          : "Enter a number greater than 0.",
    };
  }
  const value = Number(trimmed);
  const decimals = (trimmed.split(".")[1] ?? "").length;

  if (config.kind === "percent") {
    if (value < 0 || value > 100) {
      return { ok: false, error: "Enter a value between 0 and 100." };
    }
    if (decimals > 2) {
      return { ok: false, error: "Use at most 2 decimals." };
    }
    return { ok: true, value };
  }

  // AVERAGE_LATENCY
  if (value <= 0) {
    return { ok: false, error: "Enter a value greater than 0." };
  }
  if (decimals > 4) {
    return { ok: false, error: "Use at most 4 decimals." };
  }
  return { ok: true, value };
}

/* -------------------------------------------------------------------------- */
/* Formulário                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * Valores do formulário de criação/edição — números e status como TEXTO para
 * o usuário poder apagar; `monitorId: ""` = Service-level.
 */
export interface AlertRuleFormValues {
  name: string;
  type: AlertRuleType;
  operator: AlertOperator;
  thresholdNumeric: string;
  thresholdStatus: ServiceStatus | "";
  window: EvaluationWindow;
  severity: AlertSeverity;
  monitorId: string;
  /** Automação de incidente (Prompt 15) — opt-in, o form nasce com `false`. */
  autoCreateIncident: boolean;
}

const MAX_NAME_LENGTH = 120;

/** Formulário limpo com defaults sensatos (percentual de disponibilidade). */
export function emptyAlertRuleForm(): AlertRuleFormValues {
  return {
    name: "",
    type: "AVAILABILITY",
    operator: "LESS_THAN",
    thresholdNumeric: "",
    thresholdStatus: "",
    window: "FIFTEEN_MINUTES",
    severity: "SEV2",
    monitorId: "",
    autoCreateIncident: false,
  };
}

/**
 * Primeiro erro do form, ou `null` quando válido — espelha a matriz do
 * backend para falhar AGORA, sem round-trip (o 400 do backend continua
 * sendo exibido quando o estado real divergir).
 */
export function validateAlertRuleForm(form: AlertRuleFormValues): string | null {
  const name = form.name.trim();
  if (name === "") return "Name is required.";
  if (name.length > MAX_NAME_LENGTH) {
    return `Name must be at most ${MAX_NAME_LENGTH} characters.`;
  }
  if (!ALLOWED_OPERATORS[form.type].includes(form.operator)) {
    return `Operator ${form.operator} is not allowed for ${form.type}.`;
  }
  if (form.type === "SERVICE_STATUS") {
    if (form.thresholdStatus === "") return "Select a status threshold.";
    return null;
  }
  const parsed = parseThresholdInput(form.thresholdNumeric, form.type);
  return parsed.ok ? null : parsed.error;
}

/**
 * Body de CRIAÇÃO a partir do form — `null` quando o form é inválido
 * (nunca monta payload que o backend vai rejeitar).
 */
export function buildCreateAlertRuleRequest(
  form: AlertRuleFormValues
): AlertRuleCreateRequest | null {
  if (validateAlertRuleForm(form) !== null) return null;

  const request: AlertRuleCreateRequest = {
    name: form.name.trim(),
    type: form.type,
    operator: form.operator,
    window: form.window,
    severity: form.severity,
    autoCreateIncident: form.autoCreateIncident,
  };
  if (form.monitorId !== "") request.monitorId = form.monitorId;

  if (form.type === "SERVICE_STATUS") {
    request.thresholdStatus = form.thresholdStatus as ServiceStatus;
  } else {
    const parsed = parseThresholdInput(form.thresholdNumeric, form.type);
    if (!parsed.ok) return null;
    request.thresholdNumeric = parsed.value;
  }
  return request;
}

/**
 * Body de EDIÇÃO (PATCH) a partir do form — `null` quando inválido.
 *
 * Envia a condição COMPLETA (type+operator+threshold+window+severity) para o
 * backend revalidar a condição efetiva por inteiro; `monitorId` vai sempre
 * (`""` vira `null` explícito) — assim trocar Monitor-level → Service-level
 * limpa a coluna no backend.
 */
export function buildUpdateAlertRuleRequest(
  form: AlertRuleFormValues
): AlertRuleUpdateRequest | null {
  if (validateAlertRuleForm(form) !== null) return null;

  const request: AlertRuleUpdateRequest = {
    name: form.name.trim(),
    type: form.type,
    operator: form.operator,
    window: form.window,
    severity: form.severity,
    monitorId: form.monitorId === "" ? null : form.monitorId,
    autoCreateIncident: form.autoCreateIncident,
  };

  if (form.type === "SERVICE_STATUS") {
    request.thresholdStatus = form.thresholdStatus as ServiceStatus;
  } else {
    const parsed = parseThresholdInput(form.thresholdNumeric, form.type);
    if (!parsed.ok) return null;
    request.thresholdNumeric = parsed.value;
  }
  return request;
}

/** Condição do form a partir de uma regra existente (semeia a edição). */
export function formValuesFromRule(rule: AlertRuleResponse): AlertRuleFormValues {
  return {
    name: rule.name,
    type: rule.type,
    operator: rule.operator,
    thresholdNumeric:
      rule.thresholdNumeric === null ? "" : String(rule.thresholdNumeric),
    thresholdStatus: rule.thresholdStatus ?? "",
    window: rule.window,
    severity: rule.severity,
    monitorId: rule.monitorId ?? "",
    autoCreateIncident: rule.autoCreateIncident,
  };
}

/**
 * Troca o `type` do form coerentemente: mantém o operador atual SE ainda for
 * permitido no novo tipo (senão cai para o primeiro permitido) e garante um
 * `thresholdStatus` válido ao entrar em `SERVICE_STATUS` (o backend revalida
 * a condição efetiva completa; este helper só evita estados internos óbvios).
 */
export function withTypeChange(
  form: AlertRuleFormValues,
  type: AlertRuleType
): AlertRuleFormValues {
  const allowed = ALLOWED_OPERATORS[type];
  const operator = allowed.includes(form.operator) ? form.operator : allowed[0];
  const thresholdStatus =
    type === "SERVICE_STATUS"
      ? form.thresholdStatus === ""
        ? "DEGRADED"
        : form.thresholdStatus
      : form.thresholdStatus;
  return { ...form, type, operator, thresholdStatus };
}

/* -------------------------------------------------------------------------- */
/* Rótulos de apresentação                                                    */
/* -------------------------------------------------------------------------- */

/**
 * Texto do checkbox de automação (Prompt 15) — uma fonte só para o rótulo e
 * o auxílio, usados nos forms de criação e de edição. A flag é opt-in e muda
 * por PATCH como qualquer outro campo de configuração.
 */
export const AUTO_CREATE_INCIDENT_LABEL = "Create incident automatically";
export const AUTO_CREATE_INCIDENT_HINT =
  "When this rule fires, open an incident with its severity and link the alert to it.";

/**
 * Badge de escopo: `Service-level` (monitorId nulo) ou `Monitor: {nome}` —
 * o nome vem da listagem de monitores já carregada (o contrato não envia o
 * nome); sem nome, usa o prefixo do id para não imprimir UUID inteiro.
 */
export function ruleScopeLabel(
  rule: Pick<AlertRuleResponse, "monitorId">,
  monitorNameById: Record<string, string>
): string {
  if (rule.monitorId === null) return "Service-level";
  const name = monitorNameById[rule.monitorId];
  return name ? `Monitor: ${name}` : `Monitor: ${rule.monitorId.slice(0, 8)}`;
}

/**
 * Condição em uma linha legível, ex.: `Availability < 99.9 %`.
 *
 * Aceita o shape que TEM os quatro campos da condição — a regra inteira
 * (`AlertRuleResponse`) OU o snapshot de um alerta adaptado em
 * `alerts.ts::alertCondition` (Prompt 13). Assim a formatação da condição
 * existe UMA vez só para regra e alerta.
 */
export function thresholdSummary(
  rule: Pick<
    AlertRuleResponse,
    "type" | "operator" | "thresholdNumeric" | "thresholdStatus"
  >
): string {
  const type = TYPE_LABELS[rule.type];
  const operator = OPERATOR_LABELS[rule.operator];
  if (rule.type === "SERVICE_STATUS") {
    return `${type} = ${thresholdStatusLabel(rule.thresholdStatus)}`;
  }
  const unit = thresholdFieldFor(rule.type).unit;
  const value = rule.thresholdNumeric === null ? "—" : String(rule.thresholdNumeric);
  return `${type} ${operator} ${value}${unit ? ` ${unit}` : ""}`;
}

/**
 * RBAC de UX (critério 111): VIEWER não recebe controles mutáveis. O backend
 * continua sendo a autoridade (um 403 vira mensagem na UI, jamais logout).
 */
export function canManageAlertRules(role: OrganizationRole): boolean {
  return role !== "VIEWER";
}

/* -------------------------------------------------------------------------- */
/* Estado da view da listagem (critérios 99–102)                               */
/* -------------------------------------------------------------------------- */

/** Status de carregamento da lista no componente (`idle` antes do 1º fetch). */
export type AlertRulesListStatus = "idle" | "loading" | "ready" | "error";

/** O que a listagem renderiza — decisão ÚNICA derivada de status + dados. */
export type AlertRulesViewState = "loading" | "error" | "empty" | "list";

/**
 * Estado da view da listagem — função pura consumida pelo `AlertRulesPanel`:
 * a decisão de renderizar a lista real (#99), o estado vazio (#100), o
 * loading (#101) ou a caixa de erro com Retry (#102) vive AQUI, para ser
 * testável sem DOM (nó puro). `error` tem precedência sobre `empty` —
 * falha de carregar nunca se disfarça de "sem regras".
 */
export function alertRulesViewState(
  status: AlertRulesListStatus,
  ruleCount: number
): AlertRulesViewState {
  if (status === "error") return "error";
  if (status !== "ready") return "loading";
  return ruleCount === 0 ? "empty" : "list";
}
