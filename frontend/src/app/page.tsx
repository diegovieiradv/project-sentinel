/* Sentinel Prompt 03 — Identity & Prompt 04 — Multi-tenancy */
"use client";

import {
  FormEvent,
  Fragment,
  useCallback,
  useEffect,
  useRef,
  useState,
  type ComponentType,
  type ReactNode,
} from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import HealthStatus, { type HealthStatusValue } from "@/components/HealthStatus";
import AuthStatus, { type AuthStateKind, type AuthSessionEvent } from "@/components/AuthStatus";
import DashboardPanel from "@/components/DashboardPanel";
import ServicesPanel from "@/components/ServicesPanel";
import {
  NAV_SECTION_IDS,
  onExpandRequest,
  peekPendingExpand,
  clearPendingExpand,
  announceExpandDone,
  requestExpandFor,
  scrollToSection,
  type NavSectionId,
} from "@/components/section-nav";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";
import { appendUniqueById } from "@/lib/collections";
import {
  listOrganizations,
  createOrganization,
  listMembers,
  addMember,
  updateMemberRole,
  removeMember,
  listProjects,
  createProject,
  updateProject,
  updateProjectStatus,
  setOrganizationId,
  getOrganizationId,
  clearOrganizationId,
  logout,
  ApiError,
  type OrganizationResponse,
  type MemberResponse,
  type OrganizationRole,
  type ProjectListParams,
  type ProjectResponse,
  type UserResponse,
} from "@/lib/api";

/** Opções de papel — espelha o enum `Role` do backend. */
const ROLE_OPTIONS: OrganizationRole[] = ["ADMIN", "OPERATOR", "VIEWER"];

type OrgsStatus = "loading" | "ready" | "error";
type MembersStatus = "idle" | "loading" | "ready" | "error";
type ProjectsStatus = "idle" | "loading" | "ready" | "error";

/**
 * Indicadores reais dos cards do topo — nunca estimados: enquanto o dado não
 * chega o valor é `—`. Os painéis reportam pedaços (`Partial<HomeStats>`) via
 * `onStatsChange`; a home faz o merge com `setState` funcional.
 */
type HomeStats = {
  /** Total de organizações do usuário. */
  organizations: number | null;
  /** `totalElements` da organização ativa. */
  projects: number | null;
  /** Contagem de membros — a listagem é admin-only no backend. */
  members: number | null;
  /** `false` = org ativa sem papel ADMIN (mostra `requires ADMIN`). */
  membersAdmin: boolean | null;
  /** Status de saúde real do backend. */
  backend: HealthStatusValue | null;
};

const EMPTY_HOME_STATS: HomeStats = {
  organizations: null,
  projects: null,
  members: null,
  membersAdmin: null,
  backend: null,
};

/** Callback de merge de indicadores — identidade estável (useCallback na home). */
type OnStatsChange = (patch: Partial<HomeStats>) => void;

/**
 * Cabeçalho de seção da home: título + barra de acento (laranja/roxo da
 * identidade visual). Só apresentação — o texto do `h2` continua idêntico
 * (é o que os specs e2e usam como marcador).
 */
function SectionHeading({
  children,
  tone = "accent",
}: {
  children: ReactNode;
  tone?: "accent" | "purple";
}) {
  return (
    <h2 className="flex items-center gap-2.5 text-base font-semibold text-white">
      <span
        aria-hidden="true"
        className={`h-4 w-1 shrink-0 rounded-full ${
          tone === "purple" ? "bg-(--purple)" : "bg-(--accent)"
        }`}
      />
      <span>{children}</span>
    </h2>
  );
}

/**
 * Membros da organização ativa.
 *
 * Recebe `key={org.id}` do componente pai: trocar de organização desmonta e
 * remonta o painel, então o estado (lista, formulário, erros) começa limpo sem
 * precisar de um efeito que reseta estado.
 *
 * A listagem é admin-only no backend (403 para os demais papéis — é o payload
 * que expõe e-mails), então para não-ADMIN nem se faz a chamada: esconder é UX,
 * o backend continua sendo a autoridade.
 */
function MembersPanel({
  org,
  onStatsChange,
}: {
  org: OrganizationResponse;
  onStatsChange?: OnStatsChange;
}) {
  const isAdmin = org.role === "ADMIN";

  const [members, setMembers] = useState<MemberResponse[]>([]);
  const [status, setStatus] = useState<MembersStatus>("idle");
  const [reload, setReload] = useState(0);
  const [memberError, setMemberError] = useState<string | null>(null);
  const [memberForm, setMemberForm] = useState<{
    email: string;
    role: OrganizationRole;
  }>({ email: "", role: "VIEWER" });
  const [busy, setBusy] = useState(false);

  const orgId = org.id;

  useEffect(() => {
    if (org.role !== "ADMIN") return;

    let cancelled = false;

    async function load(): Promise<void> {
      setStatus("loading");
      setMemberError(null);
      try {
        const data = await listMembers(orgId);
        if (cancelled) return;
        setMembers(data);
        setStatus("ready");
      } catch (err) {
        if (cancelled) return;
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        setStatus("error");
        setMemberError(messageOf(err, "Could not load members"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [orgId, org.role, reload]);

  /**
   * Indicador "Members" do topo (Etapa menu-central). A listagem é admin-only,
   * então para papel não-admin nem se consulta: reporta `members: null` com
   * `membersAdmin: false` e o card mostra `—` + `requires ADMIN`. Sem a prop
   * não há efeito colateral algum.
   */
  useEffect(() => {
    if (!onStatsChange) return;
    if (!isAdmin) {
      onStatsChange({ members: null, membersAdmin: false });
      return;
    }
    onStatsChange({
      members: status === "ready" ? members.length : null,
      membersAdmin: true,
    });
  }, [onStatsChange, isAdmin, status, members.length]);

  /** POST .../members — adicionar por e-mail + papel (admin-only). */
  async function onAddMember(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    const email = memberForm.email.trim();
    if (!isAdmin || !email || busy) return;

    setBusy(true);
    setMemberError(null);
    try {
      const created = await addMember(orgId, email, memberForm.role);
      setMembers((prev) => [...prev, created]);
      setMemberForm({ email: "", role: "VIEWER" });
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      if (err instanceof ApiError) {
        if (err.code === "MEMBERSHIP_ALREADY_EXISTS") {
          setMemberError("This user is already a member of the organization.");
        } else if (err.status === 404) {
          setMemberError("No Sentinel account found for that email.");
        } else if (err.status === 403) {
          setMemberError("Only administrators can manage members.");
        } else {
          setMemberError(err.message);
        }
      } else {
        setMemberError("Could not add the member. Is the backend running?");
      }
    } finally {
      setBusy(false);
    }
  }

  /** PATCH .../members/{membershipId} — troca de papel (admin-only). */
  async function onChangeRole(
    membershipId: string,
    role: OrganizationRole
  ): Promise<void> {
    if (!isAdmin || busy) return;

    setBusy(true);
    setMemberError(null);
    try {
      const updated = await updateMemberRole(orgId, membershipId, role);
      setMembers((prev) =>
        prev.map((member) =>
          member.membershipId === updated.membershipId ? updated : member
        )
      );
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      if (err instanceof ApiError) {
        if (err.code === "LAST_ADMIN_REQUIRED") {
          setMemberError("An organization must keep at least one admin.");
        } else if (err.status === 404) {
          setMemberError("Member not found.");
        } else if (err.status === 403) {
          setMemberError("Only administrators can manage members.");
        } else {
          setMemberError(err.message);
        }
      } else {
        setMemberError("Could not update the member role.");
      }
      // Re-sync com o servidor — o <select> é controlado por `member.role`,
      // então a lista volta ao valor autoritativo do backend.
      setReload((count) => count + 1);
    } finally {
      setBusy(false);
    }
  }

  /** DELETE .../members/{membershipId} — remoção (admin-only, 204 sem corpo). */
  async function onRemoveMember(membershipId: string): Promise<void> {
    if (!isAdmin || busy) return;

    setBusy(true);
    setMemberError(null);
    try {
      await removeMember(orgId, membershipId);
      setMembers((prev) =>
        prev.filter((member) => member.membershipId !== membershipId)
      );
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      if (err instanceof ApiError) {
        if (err.code === "LAST_ADMIN_REQUIRED") {
          setMemberError("An organization must keep at least one admin.");
        } else if (err.status === 404) {
          setMemberError("Member not found.");
        } else if (err.status === 403) {
          setMemberError("Only administrators can manage members.");
        } else {
          setMemberError(err.message);
        }
      } else {
        setMemberError("Could not remove the member.");
      }
      setReload((count) => count + 1);
    } finally {
      setBusy(false);
    }
  }

  if (!isAdmin) {
    return (
      <section
        id="members"
        className="rounded-xl border border-(--border) bg-(--card) p-4 md:p-6"
      >
        <SectionHeading tone="purple">Members — {org.name}</SectionHeading>
        <p className="mt-2 text-sm text-(--muted)">
          Your role in this organization is{" "}
          <span className="font-medium text-white">{org.role}</span>. Viewing
          and managing members requires the{" "}
          <span className="font-medium text-white">ADMIN</span> role.
        </p>
      </section>
    );
  }

  return (
    <section
      id="members"
      className="rounded-xl border border-(--border) bg-(--card) p-4 md:p-6"
    >
      <SectionHeading tone="purple">Members — {org.name}</SectionHeading>

      {status === "error" ? (
        <div className="mt-3 rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-3 text-sm">
          <p className="text-(--destructive)">
            {memberError ?? "Could not load members"}
          </p>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            className="mt-2 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white"
          >
            Retry
          </button>
        </div>
      ) : status === "ready" && members.length === 0 ? (
        <p className="mt-2 text-sm text-(--muted)">
          No members yet — add the first one below.
        </p>
      ) : status === "ready" ? (
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-(--border) text-left text-(--muted)">
                <th className="py-2 pr-3 font-medium">Name</th>
                <th className="py-2 pr-3 font-medium">Email</th>
                <th className="py-2 pr-3 font-medium">Role</th>
                <th className="py-2 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {members.map((member) => (
                <tr
                  key={member.membershipId}
                  className="border-b border-(--border)/30"
                >
                  <td className="py-2 pr-3 font-medium text-white">
                    {member.name}
                  </td>
                  <td className="py-2 pr-3 text-(--muted)">{member.email}</td>
                  <td className="py-2 pr-3">
                    <select
                      aria-label={`Role of ${member.name}`}
                      value={member.role}
                      disabled={busy}
                      onChange={(event) =>
                        void onChangeRole(
                          member.membershipId,
                          event.target.value as OrganizationRole
                        )
                      }
                      className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-xs text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
                    >
                      {ROLE_OPTIONS.map((role) => (
                        <option key={role} value={role}>
                          {role}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="py-2 text-right">
                    <button
                      type="button"
                      onClick={() => void onRemoveMember(member.membershipId)}
                      disabled={busy}
                      className="rounded-md border border-(--destructive)/40 px-3 py-2.5 min-h-[44px] text-xs font-semibold text-(--destructive) transition hover:bg-(--destructive) hover:text-white disabled:opacity-50"
                    >
                      {busy ? "Working…" : "Remove"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="mt-2 text-sm text-(--muted)" role="status">Loading members…</p>
      )}

      {status !== "error" && memberError && (
        <p className="mt-3 text-sm text-(--destructive)">{memberError}</p>
      )}

      {/* Adicionar membro por e-mail + papel */}
      <form
        onSubmit={onAddMember}
        className="mt-4 flex flex-wrap items-center gap-2 border-t border-(--border) pt-4"
      >
        <label htmlFor="member-email" className="sr-only">
          Member email
        </label>
        <input
          id="member-email"
          type="email"
          value={memberForm.email}
          onChange={(event) =>
            setMemberForm((form) => ({ ...form, email: event.target.value }))
          }
          placeholder="user@example.com"
          disabled={busy}
          className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        />
        <label htmlFor="member-role" className="sr-only">
          Member role
        </label>
        <select
          id="member-role"
          value={memberForm.role}
          onChange={(event) =>
            setMemberForm((form) => ({
              ...form,
              role: event.target.value as OrganizationRole,
            }))
          }
          disabled={busy}
          className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        >
          {ROLE_OPTIONS.map((role) => (
            <option key={role} value={role}>
              {role}
            </option>
          ))}
        </select>
        <button
          type="submit"
          disabled={busy || memberForm.email.trim() === ""}
          className="rounded-md bg-(--accent) px-4 py-3 min-h-[44px] text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Working…" : "Add member"}
        </button>
      </form>
    </section>
  );
}

/**
 * Mensagem legível de uma falha de mutação de projeto.
 * - **403** → papel insuficiente (feedback de erro; NUNCA logout — só 401 derruba a sessão).
 * - **409** `PROJECT_NAME_DUPLICATE` e **400** `VALIDATION_ERROR` → a mensagem
 *   do servidor, que é mais específica do que qualquer texto genérico aqui.
 */
function projectActionError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You don't have permission to manage projects in this organization.";
    }
    return err.message;
  }
  return fallback;
}

/** Aviso sob a lista — sucesso (accent) ou falha de ação (destructive). */
type ProjectNotice = { kind: "success" | "error"; text: string } | null;

/** Filtro de status da listagem → query param opcional `active=true|false`. */
type ProjectStatusFilter = "all" | "active" | "inactive";

/** Tamanho da página enviada ao server (contrato: default 20, máx 100). */
const PROJECTS_PAGE_SIZE = 10;

/**
 * Projetos da organização ativa (Prompt 06).
 *
 * Segue o padrão do `MembersPanel`: recebe `key={org.id}` do pai — trocar de
 * organização desmonta e remonta o painel, então o estado nasce limpo — e usa
 * os estados `"idle" | "loading" | "ready" | "error"`.
 *
 * RBAC aqui é só UX: `role === "VIEWER"` não recebe controles mutáveis
 * funcionais (formulário de criação oculto, botões desabilitados), mas a
 * autoridade continua no backend — um **403** de resposta vira mensagem de
 * erro na UI, jamais logout.
 */
function ProjectsPanel({
  org,
  onStatsChange,
}: {
  org: OrganizationResponse;
  onStatsChange?: OnStatsChange;
}) {
  const orgId = org.id;
  /** VIEWER é somente-leitura no cliente. */
  const canManageOrg = org.role !== "VIEWER";

  const [projects, setProjects] = useState<ProjectResponse[]>([]);
  const [status, setStatus] = useState<ProjectsStatus>("idle");
  const [reload, setReload] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Busca por nome com debounce simples: `searchInput` é o digitado,
  // `nameFilter` é o valor já aplicado na query (evita refetch por tecla).
  const [searchInput, setSearchInput] = useState("");
  const [nameFilter, setNameFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState<ProjectStatusFilter>("all");

  const [createForm, setCreateForm] = useState({ name: "", description: "" });
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Detalhe inline (expansão da linha) = também editor name/description.
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState({ name: "", description: "" });
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [notice, setNotice] = useState<ProjectNotice>(null);

  const busy = creating || saving || togglingId !== null;
  const hasFilter = nameFilter !== "" || statusFilter !== "all";

  // Debounce da busca (300ms) — trocar de filtro/página deixa a lista coerente.
  useEffect(() => {
    const timer = setTimeout(() => {
      setNameFilter(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // Listagem — recarrega em mudança de página, filtro, organização ou reload.
  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      setStatus("loading");
      setListError(null);
      try {
        const params: ProjectListParams = {
          page,
          size: PROJECTS_PAGE_SIZE,
        };
        if (nameFilter) params.name = nameFilter;
        if (statusFilter !== "all") params.active = statusFilter === "active";

        const data = await listProjects(orgId, params);
        if (cancelled) return;
        setProjects(data.content);
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
        setListError(messageOf(err, "Could not load projects"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [orgId, page, nameFilter, statusFilter, reload]);

  /**
   * Indicador "Projects" do topo — `totalElements` real da listagem.
   * `idle` = painel recém-montado sem dado (zera para não deixar o número da
   * organização anterior); `loading`/`error` mantêm o último valor conhecido.
   */
  useEffect(() => {
    if (!onStatsChange) return;
    if (status === "idle") onStatsChange({ projects: null });
    else if (status === "ready") onStatsChange({ projects: totalElements });
  }, [onStatsChange, status, totalElements]);

  /**
   * Expansão pedida pela navegação central da home (âncoras `#services` e
   * `#metrics`): o menu precisa fazer o conteúdo aparecer antes de rolar até
   * ele, porque o `ServicesPanel` mora dentro do detalhe expandido. Expandimos
   * o primeiro projeto da lista; o escopo `#metrics` fica pendente para o
   * `ServicesPanel` expandir o primeiro serviço.
   *
   * Cada caminho CONCLUI o pedido com `announceExpandDone` — é o desfecho
   * determinístico que o `scrollToSection` aguarda (nenhum timeout decide o
   * destino):
   * - alvo criado/existente → `expanded`;
   * - lista vazia de fato → `unavailable` + `fallbackId: "projects"` (o único
   *   caso em que a nav pode cair em `#projects`);
   * - listagem em erro → `unavailable` sem fallback (não há como expandir e
   *   também não dá para afirmar que não há projetos);
   * - escopo `#metrics` → o pendente segue para o `ServicesPanel`, que anuncia.
   *
   * "Já expandido" é decidido pelo DOM (`#services`) e não por `expandedId`:
   * com filtro ativo a linha expandida pode ter saído da página visível, e aí
   * o primeiro projeto VISÍVEL é quem expande.
   */
  useEffect(() => {
    function tryExpandFirstProject(): void {
      const scope = peekPendingExpand();
      if (!scope) return;

      // A linha expandida (e o `#services` dela) só existe com a lista
      // pronta — por isso o status é checado antes de qualquer caminho.
      if (status === "error") {
        // Sem lista não há como expandir: encerra o pedido para a nav não
        // ficar esperando um alvo que não vai nascer (sem fallback: em erro
        // não dá para afirmar que não há projetos).
        clearPendingExpand();
        announceExpandDone({ status: "unavailable", fallbackId: null });
        return;
      }
      if (status !== "ready") return;

      if (document.getElementById("services")) {
        // Já há um projeto expandido visível: `#services` nasce dele.
        // `#metrics` continua pendente para o ServicesPanel consumir.
        if (scope === "services") {
          clearPendingExpand("services");
          announceExpandDone({ status: "expanded" });
        }
        return;
      }

      const first = projects[0];
      if (!first) {
        // De fato não há projetos (lista vazia / `totalElements` 0):
        // `#projects` é o destino legítimo — é ali que se cria o primeiro.
        clearPendingExpand();
        announceExpandDone({ status: "unavailable", fallbackId: "projects" });
        return;
      }

      setExpandedId(first.id);
      setEditForm({ name: first.name, description: first.description ?? "" });
      setEditError(null);
      if (scope === "services") {
        clearPendingExpand("services");
        announceExpandDone({ status: "expanded" });
      }
    }

    tryExpandFirstProject();
    return onExpandRequest(tryExpandFirstProject);
  }, [status, projects, expandedId]);

  /** Abre/fecha a linha de detalhe, semeando o form de edição com o projeto. */
  function onToggleDetails(project: ProjectResponse): void {
    if (expandedId === project.id) {
      setExpandedId(null);
      setEditError(null);
      return;
    }
    setExpandedId(project.id);
    setEditForm({
      name: project.name,
      description: project.description ?? "",
    });
    setEditError(null);
  }

  /** POST .../projects — criação (name obrigatório, description opcional). */
  async function onCreateProject(
    event: FormEvent<HTMLFormElement>
  ): Promise<void> {
    event.preventDefault();
    const name = createForm.name.trim();
    const description = createForm.description.trim();
    if (!canManageOrg || !name || busy) return;

    setCreating(true);
    setCreateError(null);
    try {
      const created = await createProject(orgId, {
        name,
        ...(description ? { description } : {}),
      });
      setCreateForm({ name: "", description: "" });
      setNotice({
        kind: "success",
        text: `Project "${created.name}" created.`,
      });
      setReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setCreateError(
        projectActionError(
          err,
          "Could not create the project. Is the backend running?"
        )
      );
    } finally {
      setCreating(false);
    }
  }

  /** PATCH .../projects/{projectId} — edição parcial de name/description. */
  async function onSaveEdit(
    event: FormEvent<HTMLFormElement>,
    project: ProjectResponse
  ): Promise<void> {
    event.preventDefault();
    const name = editForm.name.trim();
    if (!canManageOrg || !name || busy) return;

    setSaving(true);
    setEditError(null);
    try {
      const updated = await updateProject(orgId, project.id, {
        name,
        description: editForm.description.trim(),
      });
      setProjects((prev) =>
        prev.map((item) => (item.id === updated.id ? updated : item))
      );
      setNotice({
        kind: "success",
        text: `Project "${updated.name}" updated.`,
      });
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setEditError(
        projectActionError(err, "Could not update the project.")
      );
    } finally {
      setSaving(false);
    }
  }

  /** PATCH .../projects/{projectId}/status — ativar/desativar (idempotente). */
  async function onToggleStatus(project: ProjectResponse): Promise<void> {
    if (!canManageOrg || busy) return;

    setTogglingId(project.id);
    setNotice(null);
    try {
      const updated = await updateProjectStatus(orgId, project.id, !project.active);
      setProjects((prev) =>
        prev.map((item) => (item.id === updated.id ? updated : item))
      );
      setNotice({
        kind: "success",
        text: `Project "${updated.name}" ${
          updated.active ? "activated" : "deactivated"
        }.`,
      });
      // Com filtro de status ativo o projeto pode sair/entrar da página.
      if (statusFilter !== "all") setReload((count) => count + 1);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setNotice({
        kind: "error",
        text: projectActionError(err, "Could not update the project status."),
      });
    } finally {
      setTogglingId(null);
    }
  }

  return (
    <section
      id="projects"
      className="rounded-xl border border-(--border) bg-(--card) p-4 md:p-6"
    >
      <SectionHeading>Projects — {org.name}</SectionHeading>

      {!canManageOrg && (
        <p className="mt-2 text-sm text-(--muted)">
          Your role in this organization is{" "}
          <span className="font-medium text-white">{org.role}</span>. You can
          view projects; creating, editing and changing status require the{" "}
          <span className="font-medium text-white">ADMIN</span> or{" "}
          <span className="font-medium text-white">OPERATOR</span> role.
        </p>
      )}

      {/* Busca por nome (debounce) + filtro de status */}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <label htmlFor="project-search" className="sr-only">
          Search projects by name
        </label>
        <input
          id="project-search"
          type="search"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          placeholder="Search by name…"
          className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        />
        <label htmlFor="project-status-filter" className="sr-only">
          Filter projects by status
        </label>
        <select
          id="project-status-filter"
          value={statusFilter}
          onChange={(event) => {
            setStatusFilter(event.target.value as ProjectStatusFilter);
            setPage(0);
          }}
          className="rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
        >
          <option value="all">All statuses</option>
          <option value="active">Active</option>
          <option value="inactive">Inactive</option>
        </select>
      </div>

      {/* Erro da listagem ≠ lista vazia: caixa própria com Retry */}
      {status === "error" ? (
        <div className="mt-3 rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-3 text-sm">
          <p className="text-(--destructive)">
            {listError ?? "Could not load projects"}
          </p>
          <button
            type="button"
            onClick={() => setReload((count) => count + 1)}
            className="mt-2 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white"
          >
            Retry
          </button>
        </div>
      ) : status === "ready" && projects.length === 0 ? (
        <p className="mt-3 text-sm text-(--muted)">
          {hasFilter
            ? "No projects match your search."
            : "No projects yet. Create a project to organize the services you want Sentinel to monitor."}
        </p>
      ) : status === "ready" ? (
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-(--border) text-left text-(--muted)">
                <th className="py-2 pr-3 font-medium">Name</th>
                <th className="py-2 pr-3 font-medium">Status</th>
                <th className="py-2 pr-3 font-medium">Role</th>
                <th className="py-2 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {projects.map((project) => {
                const isExpanded = expandedId === project.id;
                const canManage =
                  canManageOrg && project.role !== "VIEWER";
                return (
                  <Fragment key={project.id}>
                    <tr className="border-b border-(--border)/30">
                      <td className="py-2 pr-3 font-medium text-white">
                        {project.name}
                      </td>
                      <td className="py-2 pr-3">
                        <span
                          className={
                            project.active
                              ? "rounded bg-(--accent) px-1.5 py-0.5 text-xs font-semibold text-white"
                              : "rounded border border-(--border) px-1.5 py-0.5 text-xs font-semibold text-(--muted)"
                          }
                        >
                          {project.active ? "active" : "inactive"}
                        </span>
                      </td>
                      <td className="py-2 pr-3 text-(--muted)">
                        {project.role}
                      </td>
                      <td className="py-2 text-right">
                        <span className="flex justify-end gap-2">
                          <button
                            type="button"
                            onClick={() => onToggleDetails(project)}
                            aria-expanded={isExpanded}
                            disabled={busy}
                            className="rounded-md border border-(--border) px-3 py-2.5 min-h-[44px] text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                          >
                            {isExpanded ? "Close" : "Details"}
                          </button>
                          <button
                            type="button"
                            onClick={() => void onToggleStatus(project)}
                            disabled={!canManage || busy}
                            className="rounded-md border border-(--border) px-3 py-2.5 min-h-[44px] text-xs text-(--muted) transition hover:border-(--border-hover) hover:text-white disabled:opacity-50"
                          >
                            {togglingId === project.id
                              ? "Saving…"
                              : project.active
                                ? "Deactivate"
                                : "Activate"}
                          </button>
                        </span>
                      </td>
                    </tr>

                    {/* Detalhe inline = edição de name/description */}
                    {isExpanded && (
                      <tr className="border-b border-(--border)/30">
                        <td colSpan={4} className="py-3 pr-3">
                          <div className="flex flex-col gap-2 rounded-md border border-(--border) bg-(--surface) p-3">
                            <p className="text-sm text-(--muted)">
                              {project.description ?? "No description."}
                            </p>
                            <p className="text-xs text-(--muted)">
                              Created {project.createdAt} · Updated{" "}
                              {project.updatedAt}
                            </p>

                            {canManage ? (
                              <form
                                onSubmit={(event) =>
                                  void onSaveEdit(event, project)
                                }
                                className="flex flex-col gap-2"
                              >
                                <label
                                  htmlFor={`project-edit-name-${project.id}`}
                                  className="sr-only"
                                >
                                  Project name
                                </label>
                                <input
                                  id={`project-edit-name-${project.id}`}
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
                                  htmlFor={`project-edit-description-${project.id}`}
                                  className="sr-only"
                                >
                                  Project description
                                </label>
                                <input
                                  id={`project-edit-description-${project.id}`}
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
                                Read-only — your role cannot edit projects.
                              </p>
                            )}
                          </div>

                          {/* Painel operacional do projeto (Prompt 16) —
                              resposta ÚNICA read-only, monta/desmonta com o
                              projeto (`key` próprio) e recarrega só na
                              troca de faixa ou no Refresh manual. */}
                          <DashboardPanel
                            key={`dashboard-${project.id}`}
                            organizationId={orgId}
                            projectId={project.id}
                          />

                          {/* Serviços do projeto expandido (Prompt 07) —
                              monta/desmonta com o projeto (`key={project.id}`). */}
                          <ServicesPanel key={project.id} project={project} />
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
              {totalElements === 1 ? "project" : "projects"} · Page{" "}
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
        <p className="mt-3 text-sm text-(--muted)" role="status">Loading projects…</p>
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

      {/* Criar projeto — oculto para VIEWER (controle mutável sem permissão) */}
      {canManageOrg && (
        <form
          onSubmit={onCreateProject}
          className="mt-4 flex flex-col gap-2 border-t border-(--border) pt-4"
        >
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="project-create-name" className="sr-only">
              Project name
            </label>
            <input
              id="project-create-name"
              value={createForm.name}
              onChange={(event) =>
                setCreateForm((form) => ({
                  ...form,
                  name: event.target.value,
                }))
              }
              placeholder="New project name"
              disabled={busy}
              className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
            />
            <button
              type="submit"
              disabled={busy || createForm.name.trim() === ""}
              className="rounded-md bg-(--accent) px-4 py-3 min-h-[44px] text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
            >
              {creating ? "Creating…" : "Create project"}
            </button>
          </div>
          <label htmlFor="project-create-description" className="sr-only">
            Project description
          </label>
          <input
            id="project-create-description"
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

/**
 * Seção de organizações (Prompt 04).
 *
 * Montada apenas com sessão confirmada pelo `AuthStatus` — se a sessão cair
 * (logout ou 401) ela desmonta e TODO o estado de orgs/membros é descartado
 * junto, sem efeito de reset. A org ativa em si vive no módulo `api.ts`
 * (memória + UUID no localStorage), limpa por `logout()`/401.
 */
function OrganizationSection({
  onStatsChange,
}: {
  onStatsChange?: OnStatsChange;
}) {
  const [orgs, setOrgs] = useState<OrganizationResponse[]>([]);
  const [orgsStatus, setOrgsStatus] = useState<OrgsStatus>("loading");
  const [orgsError, setOrgsError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [activeOrg, setActiveOrg] = useState<OrganizationResponse | null>(null);

  const [orgName, setOrgName] = useState("");
  const [creatingOrg, setCreatingOrg] = useState(false);
  const [orgError, setOrgError] = useState<string | null>(null);

  /** Id da org ativa — dep estável para os efeitos de indicadores. */
  const activeOrgId = activeOrg?.id ?? null;

  /**
   * Indicador "Organizations" do topo — total real de orgs do usuário.
   * `loading`/`error` mantêm o último valor conhecido; o reset é do próprio
   * componente pai (sessão encerrada).
   */
  useEffect(() => {
    if (orgsStatus !== "ready") return;
    onStatsChange?.({ organizations: orgs.length });
  }, [orgsStatus, orgs.length, onStatsChange]);

  /**
   * Sem organização ativa não há o que contar em Projects/Members — zera os
   * indicadores para não deixar números da última org selecionada.
   */
  useEffect(() => {
    if (activeOrgId) return;
    onStatsChange?.({ projects: null, members: null, membersAdmin: null });
  }, [activeOrgId, onStatsChange]);

  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      setOrgsStatus("loading");
      setOrgsError(null);
      try {
        const data = await listOrganizations();
        if (cancelled) return;

        // Org ativa persistida só vale se pertencer à lista DESTE usuário:
        // um UUID de outra conta (ou de uma org da qual saiu) é descartado.
        const storedId = getOrganizationId();
        let nextActive = data.find((org) => org.id === storedId) ?? null;
        if (storedId && !nextActive) {
          clearOrganizationId();
        }
        if (!nextActive) {
          nextActive = data[0] ?? null;
        }
        if (nextActive) {
          setOrganizationId(nextActive.id);
        }

        setOrgs(data);
        setActiveOrg(nextActive);
        setOrgsStatus("ready");
      } catch (err) {
        if (cancelled) return;
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        setOrgsStatus("error");
        setOrgsError(messageOf(err, "Could not load organizations"));
      }
    }

    void load();

    return () => {
      cancelled = true;
    };
  }, [reload]);

  /** Troca a organização ativa: estado de UI + UUID em memória/localStorage. */
  function handleSelectOrg(org: OrganizationResponse): void {
    setActiveOrg(org);
    setOrganizationId(org.id);
  }

  /** POST /organizations — erros 400 (VALIDATION_ERROR) exibidos no formulário. */
  async function onCreateOrg(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    const name = orgName.trim();
    if (!name || creatingOrg) return;

    setCreatingOrg(true);
    setOrgError(null);
    try {
      const created = await createOrganization(name);
      setOrgs((prev) => appendUniqueById(prev, created));
      setOrgName("");
      if (!activeOrg) {
        setActiveOrg(created);
        setOrganizationId(created.id);
      }
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setOrgError(
        err instanceof ApiError
          ? err.message
          : "Could not create the organization. Is the backend running?"
      );
    } finally {
      setCreatingOrg(false);
    }
  }

  return (
    <div className="flex w-full flex-col gap-6">
      {/* Organizações: lista + indicação da org ativa + criação */}
      <section className="rounded-xl border border-(--border) bg-(--card) p-4 md:p-6">
        <SectionHeading>Organizations</SectionHeading>

        {/* Indicador da organização ativa */}
        {activeOrg ? (
          <div className="mt-3 flex flex-wrap items-center gap-2 rounded-md border border-(--accent)/30 bg-(--accent)/10 px-3 py-2 text-sm">
            <span className="text-(--muted)">Active organization</span>
            <span className="font-semibold text-white">{activeOrg.name}</span>
            <span className="rounded bg-(--accent) px-1.5 py-0.5 text-xs font-semibold text-white">
              {activeOrg.role}
            </span>
          </div>
        ) : (
          orgsStatus === "ready" && (
            <p className="mt-3 text-sm text-(--muted)">
              No organization selected.
            </p>
          )
        )}

        {/* Lista / loading / vazio / erro de rede */}
        <div className="mt-3">
          {orgsStatus === "loading" ? (
            <p className="text-sm text-(--muted)" role="status">Loading organizations…</p>
          ) : orgsStatus === "error" ? (
            <div className="rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-3 text-sm">
              <p className="text-(--destructive)">
                {orgsError ?? "Could not load organizations"}
              </p>
              <button
                type="button"
                onClick={() => setReload((count) => count + 1)}
                className="mt-2 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-(--muted) transition hover:border-(--border-hover) hover:text-white"
              >
                Retry
              </button>
            </div>
          ) : orgs.length === 0 ? (
            <p className="text-sm text-(--muted)">
              No organizations yet — create your first one below.
            </p>
          ) : (
            <ul className="flex flex-col gap-2">
              {orgs.map((org) => {
                const isActive = activeOrg?.id === org.id;
                return (
                  <li key={org.id}>
                    <button
                      type="button"
                      onClick={() => handleSelectOrg(org)}
                      aria-pressed={isActive}
                      className={`flex w-full items-center justify-between gap-3 rounded-md border px-4 py-3 min-h-[44px] text-left text-sm transition ${
                        isActive
                          ? "border-(--accent) bg-(--accent)/10 text-white"
                          : "border-(--border) text-(--muted) hover:border-(--border-hover) hover:text-white"
                      }`}
                    >
                      <span className="font-medium">{org.name}</span>
                      <span className="flex items-center gap-2 text-xs">
                        <span
                          className={
                            isActive ? "text-(--accent)" : "text-(--muted)"
                          }
                        >
                          {org.role}
                        </span>
                        {isActive && (
                          <span className="rounded bg-(--accent) px-1.5 py-0.5 font-semibold text-white">
                            active
                          </span>
                        )}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        {/* Criar organização */}
        <form
          onSubmit={onCreateOrg}
          className="mt-4 flex flex-wrap items-center gap-2 border-t border-(--border) pt-4"
        >
          <label htmlFor="org-name" className="sr-only">
            Organization name
          </label>
          <input
            id="org-name"
            value={orgName}
            onChange={(event) => setOrgName(event.target.value)}
            placeholder="New organization name"
            maxLength={128}
            disabled={creatingOrg}
            className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--surface) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
          />
          <button
            type="submit"
            disabled={creatingOrg || orgName.trim().length === 0}
            className="rounded-md bg-(--accent) px-4 py-3 min-h-[44px] text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
          >
            {creatingOrg ? "Creating…" : "Create"}
          </button>
        </form>
        {orgError && (
          <p className="mt-2 text-sm text-(--destructive)">{orgError}</p>
        )}
      </section>

      {/* Membros da organização ativa */}
      {activeOrg ? (
        <MembersPanel
          key={`members-${activeOrg.id}`}
          org={activeOrg}
          onStatsChange={onStatsChange}
        />
      ) : (
        <section
          id="members"
          className="rounded-xl border border-(--border) bg-(--card) p-4 md:p-6"
        >
          <SectionHeading tone="purple">Members</SectionHeading>
          <p className="mt-2 text-sm text-(--muted)">
            Select an organization to see its members.
          </p>
        </section>
      )}

      {/* Projetos da organização ativa (Prompt 06) — só existe com org selecionada */}
      {activeOrg && (
        <ProjectsPanel
          key={`projects-${activeOrg.id}`}
          org={activeOrg}
          onStatsChange={onStatsChange}
        />
      )}
    </div>
  );
}


/**
 * Perfil único da home (`Minha conta`) — canto superior direito do banner.
 *
 * É a ÚNICA apresentação de perfil da aplicação: recebe o `user` que o
 * `AuthStatus` (headless) notifica ao componente pai, então **não** chama
 * `GET /api/v1/auth/me` de novo — essa chamada é responsabilidade só do
 * `AuthStatus`.
 */
function UserAccount({ user }: { user: UserResponse | null }) {
  const [open, setOpen] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const router = useRouter();
  const rootRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  /** Abaixo por padrão; vira `true` quando não cabe na viewport. */
  const [opensUp, setOpensUp] = useState(false);

  /**
   * Abre para CIMA quando não couber abaixo do gatilho (viewport baixa /
   * paisagem): o header não recorta mais o menu, mas ele ainda precisa caber
   * na tela. Roda em `useEffect` com o menu já medido — quando há espaço (o
   * caso normal, inclusive 375×812 e 768×1024) o estado não muda e não há
   * reposicionamento perceptível.
   */
  useEffect(() => {
    if (!open) return;
    const root = rootRef.current;
    const menu = menuRef.current;
    if (!root || !menu) return;

    const rect = root.getBoundingClientRect();
    const gap = 8;
    const spaceBelow = window.innerHeight - rect.bottom - gap;
    const spaceAbove = rect.top - gap;
    setOpensUp(spaceBelow < menu.offsetHeight && spaceAbove > spaceBelow);
  }, [open]);

  // Fecha no Escape e em clique fora do componente. O `aria-expanded` do
  // botão continua sendo a única fonte de verdade do estado aberto.
  useEffect(() => {
    if (!open) return;

    function onKeyDown(event: KeyboardEvent): void {
      if (event.key === "Escape") setOpen(false);
    }

    function onPointerDown(event: PointerEvent): void {
      const root = rootRef.current;
      if (root && event.target instanceof Node && !root.contains(event.target)) {
        setOpen(false);
      }
    }

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open]);

  /**
   * `logout()` limpa o access token em memória e a organização ativa e já
   * engole falha de rede (o backend limpa o cookie refresh do lado dele);
   * o redirect garante a saída mesmo assim. O erro, se houver, não quebra a
   * UI — o dropdown segue aberto e o usuário pode tentar de novo.
   */
  async function onSignOut(): Promise<void> {
    if (signingOut) return;
    setSigningOut(true);
    try {
      await logout();
    } catch {
      // `logout()` não propaga falha de rede; aqui só evitamos rejeição solta.
    }
    setSigningOut(false);
    router.push("/login");
  }

  if (!user) return null;

  return (
    <div
      ref={rootRef}
      className="absolute top-4 right-4 md:top-6 md:right-6 z-20"
    >
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex min-h-[44px] items-center gap-2 rounded-xl bg-[#0a0a0a]/80 border border-white/[0.08] px-3 py-2 text-sm font-medium text-white hover:border-[#F97316]/30 hover:bg-[#F97316]/10 transition-colors backdrop-blur-md shadow-[0_4px_20px_rgba(0,0,0,0.4)] focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background)"
        aria-label="Minha conta"
        aria-expanded={open}
      >
        <span className="inline-flex h-7 w-7 items-center justify-center rounded-full bg-gradient-to-br from-[#F97316] to-[#9333EA] text-[10px] font-bold text-white shadow-[0_0_10px_rgba(249,115,22,0.3)]">
          {user.name?.[0]?.toUpperCase() || "U"}
        </span>
        <span className="hidden md:inline">Minha conta</span>
      </button>
      {open && (
        <div
          ref={menuRef}
          className={`absolute right-0 w-56 rounded-xl border border-white/[0.08] bg-[#0f0f0f]/95 backdrop-blur-xl p-4 shadow-[0_8px_40px_-12px_rgba(249,115,22,0.15)] ${
            opensUp ? "bottom-full mb-2" : "top-full mt-2"
          }`}
        >
          <div className="text-xs text-[#cbd5e1]/50 uppercase tracking-wider font-bold mb-2">Usuário</div>
          <div className="text-sm font-semibold text-white">{user.name}</div>
          <div className="text-xs text-[#cbd5e1]/60 break-all">{user.email}</div>
          <div className="mt-3 border-t border-white/[0.06] pt-2">
            <button
              type="button"
              onClick={() => void onSignOut()}
              disabled={signingOut}
              className="w-full min-h-[44px] rounded-lg px-2 py-1.5 text-left text-xs font-medium text-[#f87171] transition-colors hover:bg-[#f87171]/10 hover:text-[#f87171]/80 focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) disabled:opacity-60"
            >
              {signingOut ? "Signing out…" : "Sign Out"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/** Props dos ícones SVG escritos à mão (o projeto não tem biblioteca de ícones). */
type IconProps = { className?: string };

type IconComponent = ComponentType<IconProps>;

function HomeIcon({ className = "h-5 w-5" }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5.5 9.5V20a1 1 0 0 0 1 1h3.5v-5.5h4V21H17.5a1 1 0 0 0 1-1V9.5" />
    </svg>
  );
}

function ServerIcon({ className = "h-5 w-5" }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <rect x="3" y="4" width="18" height="7" rx="2" />
      <rect x="3" y="13" width="18" height="7" rx="2" />
      <path d="M7 7.5h.01M7 16.5h.01" />
    </svg>
  );
}

function UsersIcon({ className = "h-5 w-5" }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
  );
}

function FolderIcon({ className = "h-5 w-5" }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <path d="M3 7.5A2 2 0 0 1 5 5.5h3.6l2 2H19a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
    </svg>
  );
}

function ChartIcon({ className = "h-5 w-5" }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <path d="M3 21h18" />
      <path d="M6 21v-6M12 21V6M18 21v-9" />
    </svg>
  );
}

function BuildingIcon({ className = "h-5 w-5" }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <rect x="3" y="8" width="18" height="13" rx="2" />
      <path d="M8 8V5.5A1.5 1.5 0 0 1 9.5 4h5A1.5 1.5 0 0 1 16 5.5V8" />
      <path d="M7 12.5h.01M12 12.5h.01M17 12.5h.01M7 16.5h.01M12 16.5h.01M17 16.5h.01" />
    </svg>
  );
}

/** Itens da navegação — todos apontam para seções reais da própria página. */
const NAV_ITEMS: Array<{
  id: NavSectionId;
  label: string;
  Icon: IconComponent;
}> = [
  { id: "dashboard", label: "Dashboard", Icon: HomeIcon },
  { id: "services", label: "Services", Icon: ServerIcon },
  { id: "members", label: "Members", Icon: UsersIcon },
  { id: "projects", label: "Projects", Icon: FolderIcon },
  { id: "metrics", label: "Metrics", Icon: ChartIcon },
];

/**
 * Pílula de navegação central, sobreposta à borda inferior do banner.
 *
 * Fica FORA do `<main>` de propósito: o spec
 * `e2e/projects/expand-project.spec.ts` conta `main button[aria-expanded]` e
 * espera exatamente 1 (o `Details` dos projetos) — por isso aqui são
 * elementos `<a href="#…">`, nunca `<button>`.
 */
function SectionNav({
  active,
  onSelect,
}: {
  active: NavSectionId;
  onSelect: (id: NavSectionId) => void;
}) {
  return (
    <nav
      aria-label="Seções da página"
      className="relative z-10 -mt-6 w-full md:-mt-8"
    >
      <div className="mx-auto w-full max-w-[1100px] px-4 md:px-6">
        <ul className="mx-auto flex w-fit max-w-full items-stretch justify-start gap-1 overflow-x-auto rounded-full border border-white/10 bg-black/70 px-2 py-1.5 backdrop-blur-md sm:justify-center sm:gap-2">
          {NAV_ITEMS.map(({ id, label, Icon }) => {
            const isActive = active === id;
            return (
              <li key={id} className="shrink-0">
                <a
                  href={`#${id}`}
                  aria-current={isActive ? "true" : undefined}
                  onClick={() => onSelect(id)}
                  className={`relative flex min-h-[44px] min-w-[44px] flex-col items-center justify-center gap-1 rounded-full px-3 py-2 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-black ${
                    isActive
                      ? "text-(--accent)"
                      : "text-(--muted) hover:text-white"
                  }`}
                >
                  <Icon className="h-5 w-5" />
                  {/* Ícone sozinho no mobile, ícone + rótulo a partir de sm */}
                  <span className="sr-only text-[11px] font-medium leading-none sm:not-sr-only">
                    {label}
                  </span>
                  {isActive && (
                    <span
                      aria-hidden="true"
                      className="absolute bottom-1 h-1 w-6 rounded-full bg-(--accent)"
                    />
                  )}
                </a>
              </li>
            );
          })}
        </ul>
      </div>
    </nav>
  );
}

/**
 * Scrollspy da navegação — `IntersectionObserver` sobre os alvos de seção.
 *
 * `#metrics` fica dentro de `#services`, que fica dentro de `#projects`: na
 * mesma faixa observada vence o alvo mais PROFUNDO no DOM (o mais
 * específico). Os alvos aparecem e somem com a expansão de linhas, então o
 * observer é remontado (com debounce) quando o conjunto de ids muda.
 */
function useScrollSpy(): {
  active: NavSectionId;
  setActive: (id: NavSectionId) => void;
} {
  const [active, setActive] = useState<NavSectionId>("dashboard");

  useEffect(() => {
    const visible = new Map<string, boolean>();
    let observer: IntersectionObserver | null = null;
    /** Nós observados HOJE — a referência é o que detecta a remontagem. */
    let observed: HTMLElement[] = [];
    let rearm: ReturnType<typeof setTimeout> | null = null;

    /** Profundidade no DOM — desempata alvos aninhados no mesmo scroll. */
    function depthOf(element: Element): number {
      let depth = 0;
      for (
        let node = element.parentElement;
        node && node !== document.body;
        node = node.parentElement
      ) {
        depth += 1;
      }
      return depth;
    }

    function attach(): void {
      const found: HTMLElement[] = [];
      for (const id of NAV_SECTION_IDS) {
        const element = document.getElementById(id);
        if (element) found.push(element);
      }
      // Mesmos ids ≠ mesmos nós: a comparação é por IDENTIDADE do elemento
      // (trocar de organização remonta `MembersPanel`/`ProjectsPanel` com
      // `key={org.id}` → nós NOVOS, mesmos ids — sem reanexar, o observer
      // apontaria para nós removidos e o item ativo congelaria).
      const sameNodes =
        found.length === observed.length &&
        found.every((element, index) => observed[index] === element);
      if (sameNodes) return;

      // Desconecta os observers antigos (nós possivelmente já desmontados)
      // e passa a observar os nós atuais do DOM.
      observer?.disconnect();
      observer = null;
      observed = found;
      visible.clear();
      if (found.length === 0) return;

      observer = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            visible.set(entry.target.id, entry.isIntersecting);
          }
          let winner: NavSectionId | null = null;
          let winnerDepth = -1;
          for (const id of NAV_SECTION_IDS) {
            if (!visible.get(id)) continue;
            const element = document.getElementById(id);
            if (!element) continue;
            const depth = depthOf(element);
            if (depth > winnerDepth) {
              winnerDepth = depth;
              winner = id;
            }
          }
          if (winner) setActive(winner);
        },
        // Faixa no topo da viewport (20% superiores): a seção "ativa" é a que
        // está sob o topo da tela — que é onde o scroll de uma âncora deixa o
        // alvo, então clicar e rolar mantêm o item clicado ativo.
        { rootMargin: "0px 0px -80% 0px", threshold: 0 }
      );

      for (const element of found) observer.observe(element);
    }

    attach();

    const mutation = new MutationObserver(() => {
      if (rearm) clearTimeout(rearm);
      rearm = setTimeout(attach, 120);
    });
    mutation.observe(document.body, { childList: true, subtree: true });

    return () => {
      observer?.disconnect();
      mutation.disconnect();
      if (rearm) clearTimeout(rearm);
    };
  }, []);

  return { active, setActive };
}

/** Formata um contador real — sem dado chegado, `—` (nunca um número inventado). */
function formatCount(value: number | null): string {
  return value === null ? "—" : String(value);
}

/**
 * Cards de indicadores (logo abaixo da nav, dentro de `#dashboard`).
 * Valores REAIS vindos dos painéis via `HomeStats` — nada de sparkline
 * decorativa ou dado fictício.
 */
function StatsCards({ stats }: { stats: HomeStats }) {
  const isMembersForbidden = stats.membersAdmin === false;

  const cards: Array<{
    key: string;
    label: string;
    tone: "accent" | "purple";
    Icon: IconComponent;
    value: string;
    hint?: string;
  }> = [
    {
      key: "organizations",
      label: "Organizations",
      tone: "accent",
      Icon: BuildingIcon,
      value: formatCount(stats.organizations),
    },
    {
      key: "projects",
      label: "Projects",
      tone: "purple",
      Icon: FolderIcon,
      value: formatCount(stats.projects),
    },
    {
      key: "members",
      label: "Members",
      tone: "accent",
      Icon: UsersIcon,
      value: isMembersForbidden ? "—" : formatCount(stats.members),
      ...(isMembersForbidden ? { hint: "requires ADMIN" } : {}),
    },
    {
      key: "backend",
      label: "Backend",
      tone: "purple",
      Icon: ServerIcon,
      value: stats.backend ?? "—",
    },
  ];

  return (
    <section id="dashboard" aria-label="Dashboard">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {cards.map(({ key, label, tone, Icon, value, hint }) => (
          <article
            key={key}
            className={`rounded-xl border p-4 ${
              tone === "accent"
                ? "border-(--accent)/40 bg-[#F97316]/10"
                : "border-(--purple)/40 bg-[#9333EA]/10"
            }`}
          >
            <div className="flex items-center gap-3">
              <span
                className={`inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg ${
                  tone === "accent"
                    ? "bg-[#F97316]/15 text-(--accent)"
                    : "bg-[#9333EA]/15 text-(--purple-soft)"
                }`}
              >
                <Icon className="h-5 w-5" />
              </span>
              <span className="text-xs font-semibold uppercase tracking-wide text-(--muted)">
                {label}
              </span>
            </div>
            <p className="mt-3 text-2xl font-bold text-white">{value}</p>
            {hint && (
              <p className="mt-1 text-xs text-(--muted)">{hint}</p>
            )}
          </article>
        ))}
      </div>
    </section>
  );
}

export default function Home() {
  const router = useRouter();
  /**
   * Sessão espelhada do `AuthStatus` (que é a autoridade): a seção de
   * organizações só existe com sessão confirmada — sem corrida entre o
   * bootstrap do refresh e a listagem de orgs (que cairia em 401).
   */
  const [sessionKind, setSessionKind] = useState<AuthStateKind>("loading");
  /** Mensagem legível do bootstrap quando `sessionKind === "error"`. */
  const [sessionError, setSessionError] = useState<string | null>(null);
  /**
   * Dispara um NOVO bootstrap da sessão: trocar a `key` do `AuthStatus`
   * remonta o componente e seu efeito único de bootstrap roda de novo (o
   * single-flight `sessionBootstrap` já foi liberado no `finally` da
   * tentativa anterior). É a ação de "tentar novamente" do estado de erro.
   */
  const [sessionAttempt, setSessionAttempt] = useState(0);

  // Só o anônimo sai daqui: um 5xx transitório em `GET /auth/me` não expulsa
  // o usuário — vira o estado de erro visível abaixo, com nova tentativa.
  useEffect(() => {
    if (sessionKind === "anon") {
      router.replace("/login");
    }
  }, [sessionKind, router]);

  const [authUser, setAuthUser] = useState<UserResponse | null>(null);

  /**
   * Indicadores do topo — somente valores reais reportados pelos painéis e
   * pelo `HealthStatus` (nenhuma chamada de API duplicada: `GET /auth/me` é
   * só do `AuthStatus` e o `checkHealth()` é só do `HealthStatus`).
   */
  const [stats, setStats] = useState<HomeStats>(EMPTY_HOME_STATS);

  const handleAuthStateChange = useCallback((session: AuthSessionEvent) => {
    setSessionKind(session.kind);
    // `error` preserva a mensagem do bootstrap para o estado de erro da home;
    // as demais fases limpam (volta a sessão anterior encerrada/renovada).
    setSessionError(
      session.kind === "error"
        ? (session.message ?? "Could not verify session")
        : null
    );
    if (session.kind === "authed" && session.user) {
      setAuthUser(session.user);
      return;
    }
    setAuthUser(null);
    // Sessão sem orgs/membros → descarta os indicadores (o backend é
    // reportado pelo HealthStatus, que não depende da sessão).
    if (session.kind === "anon" || session.kind === "error") {
      setStats((previous) => ({
        ...EMPTY_HOME_STATS,
        backend: previous.backend,
      }));
    }
  }, []);

  /** Merge dos pedaços reportados — identidade estável (deps dos efeitos). */
  const handleStatsChange = useCallback((patch: Partial<HomeStats>) => {
    setStats((previous) => ({ ...previous, ...patch }));
  }, []);

  /** `HealthStatus` headless: o status vai para o card "Backend". */
  const handleBackendChange = useCallback((status: HealthStatusValue) => {
    setStats((previous) =>
      previous.backend === status ? previous : { ...previous, backend: status }
    );
  }, []);

  const { active: activeSection, setActive: setActiveSection } = useScrollSpy();

  /**
   * Clique num item de nav: marca o estado ativo na hora e, se o alvo ainda
   * não existe no DOM, primeiro faz o conteúdo aparecer (expandindo o
   * primeiro projeto / o primeiro serviço) e só então rola até ele.
   *
   * A espera é DETERMINÍSTICA: `requestExpandFor` devolve o desfecho que o
   * painel anuncia (`announceExpandDone`) — nenhum timeout escolhe o
   * destino. Sem `ProjectsPanel` montado (sem org ativa) ninguém consumiria
   * o pedido, então o disparo nem acontece.
   */
  function onNavSelect(id: NavSectionId): void {
    setActiveSection(id);
    if (document.getElementById(id)) return; // âncora nativa resolve

    if (id === "services" || id === "metrics") {
      // `#projects` existe somente com uma organização ativa (é onde o
      // `ProjectsPanel` mora) — sem ela não há o que expandir nem para onde
      // cair, e o pedido ficaria pendurado sem ninguém consumi-lo.
      if (!document.getElementById("projects")) return;
      const expansion = requestExpandFor(id);
      void scrollToSection(id, { expansion });
      return;
    }
    void scrollToSection(id);
  }

  return (
    <div className="flex min-h-screen flex-col bg-gradient-to-b from-[#050505] via-[#090909] to-[#050505] text-white">
      {/* Banner oficial no topo — SEM `overflow-hidden`: o dropdown do
          `UserAccount` é absoluto e sai do header em telas baixas (375px:
          banner ~125px, dropdown ~157px); recortá-lo esconderia nome, e-mail
          e `Sign Out`. O `<Image>` é bloco com `object-cover` — não vaza. */}
      <header className="relative w-full">
        <Image
          src="/dashboard-banner.png"
          alt="Sentinel Dashboard Banner"
          width={1920}
          height={640}
          className="w-full h-auto max-h-[200px] md:max-h-[220px] object-cover opacity-90"
        />
        {/* Overlay sutil para contraste — ANTES do logo e da conta, para não
            escurecê-los (a referência `public/menu-central.png` mostra o
            logo limpo; posicionados empatados vencem quem vem depois). */}
        <div className="absolute inset-0 bg-gradient-to-r from-[#050505]/60 via-transparent to-[#050505]/40 pointer-events-none" />
        {/* Logo oficial no canto superior esquerdo do banner */}
        <div className="absolute top-4 left-4 md:top-6 md:left-6 z-10">
          <Image
            src="/logo-login.png"
            alt="Sentinel - Monitoramento Inteligente"
            width={520}
            height={200}
            className="w-28 h-auto md:w-36 drop-shadow-[0_0_25px_rgba(249,115,22,0.45)]"
          />
        </div>
        {/* Perfil único — Minha conta (`z-20` acima do overlay e da nav) */}
        <UserAccount user={authUser} />
      </header>

      {/* Navegação por seções — fora do <main> (ver SectionNav) */}
      <SectionNav active={activeSection} onSelect={onNavSelect} />

      {/* Área de conteúdo centralizada */}
      <main className="flex-1 w-full">
        <div className="mx-auto w-full max-w-[1100px] px-4 md:px-6 py-8">
          {/* Sessão real (headless) + saúde do backend (headless).
              A `key` nova remonta o `AuthStatus` e reexecuta o bootstrap —
              é a "tentar novamente" do estado de erro abaixo. */}
          <AuthStatus
            key={sessionAttempt}
            onAuthStateChange={handleAuthStateChange}
          />
          <HealthStatus onStatusChange={handleBackendChange} />

          {/* Sessão não verificada (5xx/rede): erro VISÍVEL com nova
              tentativa — o redirect ficou só para o anônimo. */}
          {sessionKind === "error" && (
            <div
              role="alert"
              className="mt-4 rounded-xl border border-(--destructive)/40 bg-(--destructive)/10 p-4 md:p-6"
            >
              <p className="text-sm font-semibold text-(--destructive)">
                Session check failed
              </p>
              <p className="mt-1 text-sm text-(--muted)">
                {sessionError ?? "Could not verify your session"}
              </p>
              <p className="mt-1 text-sm text-(--muted)">
                Sentinel could not reach the authentication service. Your data
                is untouched — try again in a moment.
              </p>
              <button
                type="button"
                onClick={() => setSessionAttempt((count) => count + 1)}
                className="mt-3 rounded-md border border-(--border) px-4 py-2.5 min-h-[44px] text-sm font-medium text-(--muted) transition hover:border-(--border-hover) hover:text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background)"
              >
                Try again
              </button>
            </div>
          )}

          {/* Rebootstrap em andamento (só após uma nova tentativa) */}
          {sessionKind === "loading" && sessionAttempt > 0 && (
            <p className="mt-4 text-sm text-(--muted)" role="status">
              Checking your session…
            </p>
          )}

          {sessionKind === "authed" && (
            <>
              {/* Indicadores reais (nada fictício enquanto não há dado) */}
              <StatsCards stats={stats} />

              {/* Painel único de conteúdo — Multi-tenancy (orgs/membros/projetos) */}
              <div className="mt-6 rounded-2xl border border-(--accent)/20 bg-[#0b0b0b] p-4 md:p-6">
                <OrganizationSection onStatsChange={handleStatsChange} />
              </div>
            </>
          )}
        </div>
      </main>
    </div>
  );
}
