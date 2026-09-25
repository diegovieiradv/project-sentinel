/* Sentinel Prompt 03 — Identity & Prompt 04 — Multi-tenancy */
"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import HealthStatus from "@/components/HealthStatus";
import AuthStatus, { type AuthStateKind } from "@/components/AuthStatus";
import {
  listOrganizations,
  createOrganization,
  listMembers,
  addMember,
  updateMemberRole,
  removeMember,
  setOrganizationId,
  getOrganizationId,
  clearOrganizationId,
  clearAccessToken,
  ApiError,
  type OrganizationResponse,
  type MemberResponse,
  type OrganizationRole,
} from "@/lib/api";

/** Opções de papel — espelha o enum `Role` do backend. */
const ROLE_OPTIONS: OrganizationRole[] = ["ADMIN", "OPERATOR", "VIEWER"];

type OrgsStatus = "loading" | "ready" | "error";
type MembersStatus = "idle" | "loading" | "ready" | "error";

/** Mensagem legível de qualquer erro capturado. */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError || err instanceof Error) {
    return err.message;
  }
  return fallback;
}

/**
 * 401 em qualquer chamada → limpa a sessão local.
 * O próprio `apiFetch` já dispara `SESSION_EXPIRED_EVENT`, que leva o `AuthStatus`
 * a assumir estado anônimo (e a home desmonta a seção de organizações).
 */
function handleUnauthorized(): void {
  clearAccessToken();
  clearOrganizationId();
}

function isUnauthorized(err: unknown): boolean {
  return err instanceof ApiError && err.status === 401;
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
            className="mt-2 rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white"
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
                      className="rounded-md border border-(--border) bg-(--background) px-2 py-1 text-xs text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
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
                      className="rounded-md border border-(--destructive)/40 px-2 py-1 text-xs font-semibold text-(--destructive) transition hover:bg-(--destructive) hover:text-white disabled:opacity-50"
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
        <p className="mt-2 text-sm text-(--muted)">Loading members…</p>
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
          className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white placeholder:text-(--muted) focus:border-(--accent) focus:outline-none disabled:opacity-60"
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
          className="rounded-md border border-(--border) bg-(--background) px-2 py-2 text-sm text-white focus:border-(--accent) focus:outline-none disabled:opacity-60"
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
          className="rounded-md bg-(--accent) px-3 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Working…" : "Add member"}
        </button>
      </form>
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
            <p className="text-sm text-(--muted)">Loading organizations…</p>
          ) : orgsStatus === "error" ? (
            <div className="rounded-md border border-(--destructive)/40 bg-(--destructive)/10 p-3 text-sm">
              <p className="text-(--destructive)">
                {orgsError ?? "Could not load organizations"}
              </p>
              <button
                type="button"
                onClick={() => setReload((count) => count + 1)}
                className="mt-2 rounded-md border border-(--border) px-3 py-1.5 text-(--muted) transition hover:border-(--border-hover) hover:text-white"
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
                      className={`flex w-full items-center justify-between gap-3 rounded-md border px-3 py-2 text-left text-sm transition ${
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
            className="min-w-0 flex-1 rounded-md border border-(--border) bg-(--background) px-3 py-2 text-sm text-white placeholder:text-(--muted) focus:border-(--accent) focus:outline-none disabled:opacity-60"
          />
          <button
            type="submit"
            disabled={creatingOrg || orgName.trim().length === 0}
            className="rounded-md bg-(--accent) px-3 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
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
