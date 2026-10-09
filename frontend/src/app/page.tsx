/* Sentinel Prompt 03 — Identity & Prompt 04 — Multi-tenancy */
"use client";

import { FormEvent, Fragment, useCallback, useEffect, useState } from "react";
import HealthStatus from "@/components/HealthStatus";
import AuthStatus, { type AuthStateKind } from "@/components/AuthStatus";
import DashboardPanel from "@/components/DashboardPanel";
import ServicesPanel from "@/components/ServicesPanel";
import { handleUnauthorized, isUnauthorized, messageOf } from "@/lib/errors";
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
  ApiError,
  type OrganizationResponse,
  type MemberResponse,
  type OrganizationRole,
  type ProjectListParams,
  type ProjectResponse,
} from "@/lib/api";

/** Opções de papel — espelha o enum `Role` do backend. */
const ROLE_OPTIONS: OrganizationRole[] = ["ADMIN", "OPERATOR", "VIEWER"];

type OrgsStatus = "loading" | "ready" | "error";
type MembersStatus = "idle" | "loading" | "ready" | "error";
type ProjectsStatus = "idle" | "loading" | "ready" | "error";

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
function MembersPanel({ org }: { org: OrganizationResponse }) {
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
      <section className="rounded-md border border-(--border) bg-(--card) p-4">
        <h2 className="font-semibold text-white">
          Members — {org.name}
        </h2>
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
    <section className="rounded-md border border-(--border) bg-(--card) p-4">
      <h2 className="font-semibold text-white">Members — {org.name}</h2>

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
                      className="rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-xs text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
          className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
          className="rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
function ProjectsPanel({ org }: { org: OrganizationResponse }) {
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
    <section className="rounded-md border border-(--border) bg-(--card) p-4">
      <h2 className="font-semibold text-white">Projects — {org.name}</h2>

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
          className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
          className="rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
                          <div className="flex flex-col gap-2 rounded-md border border-(--border) bg-(--background) p-3">
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
                                  className="rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-sm text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
                                  className="rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
              className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
            className="rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
function OrganizationSection() {
  const [orgs, setOrgs] = useState<OrganizationResponse[]>([]);
  const [orgsStatus, setOrgsStatus] = useState<OrgsStatus>("loading");
  const [orgsError, setOrgsError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [activeOrg, setActiveOrg] = useState<OrganizationResponse | null>(null);

  const [orgName, setOrgName] = useState("");
  const [creatingOrg, setCreatingOrg] = useState(false);
  const [orgError, setOrgError] = useState<string | null>(null);

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
      setOrgs((prev) => [...prev, created]);
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
    <div className="mt-8 flex w-full max-w-2xl flex-col gap-6">
      {/* Organizações: lista + indicação da org ativa + criação */}
      <section className="rounded-md border border-(--border) bg-(--card) p-4">
        <h2 className="font-semibold text-white">Organizations</h2>

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
            className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--background) px-3 py-3 min-h-[44px] text-sm text-white placeholder:text-(--muted) focus:outline-none focus-visible:ring-2 focus-visible:ring-(--accent) focus-visible:ring-offset-2 focus-visible:ring-offset-(--background) disabled:opacity-60"
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
        <MembersPanel key={activeOrg.id} org={activeOrg} />
      ) : (
        <section className="rounded-md border border-(--border) bg-(--card) p-4">
          <h2 className="font-semibold text-white">Members</h2>
          <p className="mt-2 text-sm text-(--muted)">
            Select an organization to see its members.
          </p>
        </section>
      )}

      {/* Projetos da organização ativa (Prompt 06) — só existe com org selecionada */}
      {activeOrg && <ProjectsPanel key={activeOrg.id} org={activeOrg} />}
    </div>
  );
}

export default function Home() {
  /**
   * Sessão espelhada do `AuthStatus` (que é a autoridade): a seção de
   * organizações só existe com sessão confirmada — sem corrida entre o
   * bootstrap do refresh e a listagem de orgs (que cairia em 401).
   */
  const [sessionKind, setSessionKind] = useState<AuthStateKind>("loading");
  const handleAuthStateChange = useCallback((kind: AuthStateKind) => {
    setSessionKind(kind);
  }, []);

  return (
    <main className="flex min-h-screen flex-col items-center px-4 py-8">
      <div className="flex flex-col items-center gap-4 text-center">
        <h1 className="font-extrabold text-4xl sm:text-5xl text-white">
          Sentinel
        </h1>
        <p className="text-(--muted) text-lg">
          Observability & Incident Management
        </p>
        <div className="flex items-center gap-2 text-sm text-(--muted)">
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded text-(--muted)/80 bg-(--card)/50 dark:bg-(--card)/80">
            <span>Prompt 03 — Identity</span>
          </span>
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded text-(--muted)/80 bg-(--card)/50 dark:bg-(--card)/80">
            <span>Prompt 04 — Multi-tenancy</span>
          </span>
        </div>
      </div>

      {/* Real session check via GET /api/v1/auth/me */}
      <div className="mt-6">
        <AuthStatus onAuthStateChange={handleAuthStateChange} />
      </div>

      {/* Signed out: as organizações saem de cena junto com a sessão */}
      {sessionKind === "anon" && (
        <div className="mt-8 w-full max-w-2xl rounded-md border border-(--border) bg-(--card) p-4 text-sm text-(--muted)">
          Sign in to create organizations and manage their members.
        </div>
      )}

      {/* Multi-tenancy (Prompt 04) — monta só com sessão confirmada */}
      {sessionKind === "authed" && <OrganizationSection />}

      {/* Discreet backend health status */}
      <div className="mt-8">
        <HealthStatus />
      </div>
    </main>
  );
}
