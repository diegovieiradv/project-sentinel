package com.sentinel.organization.context;

import java.util.Objects;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.identity.user.User;
import com.sentinel.organization.model.Membership;
import com.sentinel.organization.model.Organization;
import com.sentinel.organization.model.Role;

import org.springframework.http.HttpStatus;

/**
 * Contexto do tenant ativo na requisição CORRENTE (Prompt 04, §13/§14).
 *
 * <p><b>Por que ThreadLocal e não um singleton Spring:</b> o contexto varia por
 * requisição (e por thread), enquanto beans são singletons. Um campo mutável em
 * bean singleton seria compartilhado entre requisições concorrentes — vazamento
 * cross-tenant clássico. Aqui cada thread tem o seu próprio holder, populado
 * pelo {@code TenantInterceptor} após validar a membership e SEMPRE limpo no
 * {@code afterCompletion} (e também quando o {@code preHandle} falha no meio).</p>
 *
 * <p><b>Quem escreve:</b> apenas o interceptor, e apenas em handlers
 * anotados com {@code @TenantScoped}. <b>Quem lê:</b> services, para
 * autorização (papel lido AQUI, no servidor — nunca de claim de JWT).</p>
 *
 * <p>Reutilizável por futuros módulos: basta anotar o handler com
 * {@code @TenantScoped} (via path {@code organizationId} ou header
 * {@code X-Organization-Id}) e ler {@link #require()}.</p>
 */
public final class TenantContext {

    /** Imutável: trocar o contexto significa {@link #set} de uma nova instância. */
    public record Context(User user, Organization organization, Membership membership, Role role) {

        public Context {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(organization, "organization");
            Objects.requireNonNull(membership, "membership");
            Objects.requireNonNull(role, "role");
        }
    }

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private TenantContext() {
        // holder estático — nunca instanciado
    }

    /** Popula o contexto da thread atual. Chamado só pelo TenantInterceptor. */
    public static void set(Context context) {
        HOLDER.set(Objects.requireNonNull(context, "context"));
    }

    /** Contexto atual ou {@code null} fora de um handler @TenantScoped. */
    public static Context get() {
        return HOLDER.get();
    }

    /**
     * Contexto obrigatório: handlers @TenantScoped sempre passam pelo
     * interceptor, então {@code null} aqui é bug de uso (ex.: chamada de
     * service fora de requisição tenant-scoped) — vira 400 TENANT_REQUIRED em
     * vez de NPE.
     */
    public static Context require() {
        Context context = HOLDER.get();
        if (context == null) {
            throw new ApiException(
                    "TENANT_REQUIRED",
                    "Organization context required",
                    HttpStatus.BAD_REQUEST);
        }
        return context;
    }

    /** Remove o contexto da thread — idempotente, chamado sempre no fim da request. */
    public static void clear() {
        HOLDER.remove();
    }

    public static boolean isActive() {
        return HOLDER.get() != null;
    }

    public static User user() {
        return require().user();
    }

    public static UUID userId() {
        return require().user().getId();
    }

    public static Organization organization() {
        return require().organization();
    }

    public static UUID organizationId() {
        return require().organization().getId();
    }

    /** Papel do usuário ATUAL nesta organização — base de toda autorização. */
    public static Role role() {
        return require().role();
    }

    public static Membership membership() {
        return require().membership();
    }
}
