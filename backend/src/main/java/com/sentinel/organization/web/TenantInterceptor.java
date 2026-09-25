package com.sentinel.organization.web;

import java.util.Map;
import java.util.UUID;

import com.sentinel.error.ApiException;
import com.sentinel.identity.user.User;
import com.sentinel.organization.context.TenantContext;
import com.sentinel.organization.model.Membership;
import com.sentinel.organization.repository.MembershipRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolve o tenant da requisição para handlers anotados com
 * {@link TenantScoped} (Prompt 04, §13/§14).
 *
 * <p><b>Fluxo do preHandle:</b></p>
 * <ol>
 *   <li>ignora handlers sem o marcador (o custo é uma checagem de anotação);</li>
 *   <li>extrai a organização-alvo: path {@code organizationId} se existir,
 *       senão header {@code X-Organization-Id}; com os dois presentes e
 *       divergentes → 404 (possível confusão cross-tenant);</li>
 *   <li>valida que o usuário autenticado tem Membership naquela organização —
 *       organização inexistente E usuário sem membership respondem o MESMO
 *       404 genérico ("Resource not found"), sem revelar a existência do
 *       tenant;</li>
 *   <li>popula o {@link TenantContext} da thread.</li>
 * </ol>
 *
 * <p><b>Limpeza sempre:</b> {@code afterCompletion} limpa incondicionalmente;
 * e se o {@code preHandle} falhar no meio (o DispatcherServlet NÃO chama
 * afterCompletion para quem lançou), o contexto é limpo antes de propagar a
 * exceção — nenhuma thread devolve contexto de uma requisição à seguinte.</p>
 *
 * <p>Registrado pelo {@link TenantWebConfig} — o {@code SecurityConfig} não é
 * tocado; o interceptor roda dentro do DispatcherServlet, depois da cadeia de
 * segurança (o principal já é a entidade {@link User}).</p>
 *
 * <p><b>ObjectProvider</b> em vez do repositório direto: esta classe é um bean
 * {@code HandlerInterceptor}, e o slice {@code @WebMvcTest} (ex.:
 * HealthControllerTest) varre interceptors mas NÃO carrega JPA. A resolução é
 * adiada para o primeiro handler {@code @TenantScoped} — nunca acontece num
 * slice sem repositório.</p>
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    /** Header de organização para endpoints tenant-scoped sem org no path. */
    public static final String ORGANIZATION_ID_HEADER = "X-Organization-Id";

    /** Path variable usada pelos endpoints /organizations/{organizationId}/... */
    static final String ORGANIZATION_ID_PATH_VARIABLE = "organizationId";

    private final ObjectProvider<MembershipRepository> membershipRepositories;

    public TenantInterceptor(ObjectProvider<MembershipRepository> membershipRepositories) {
        this.membershipRepositories = membershipRepositories;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isTenantScoped(handler)) {
            return true;
        }
        try {
            resolveTenant(request);
            return true;
        }
        catch (RuntimeException | Error ex) {
            // O DispatcherServlet não dispara afterCompletion para quem lançou
            // em preHandle — limpar aqui é o que impede vazamento de contexto.
            // Erros (OOM, linkage) têm o mesmo tratamento: a thread não pode
            // devolver o ThreadLocal sujo para a requisição seguinte.
            TenantContext.clear();
            throw ex;
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Incondicional: toda requisição que passou por aqui devolve a thread limpa.
        TenantContext.clear();
    }

    // --- resolução --------------------------------------------------------

    private void resolveTenant(HttpServletRequest request) {
        User user = currentUser();
        UUID organizationId = resolveOrganizationId(
                pathOrganizationId(request),
                request.getHeader(ORGANIZATION_ID_HEADER));

        Membership membership = membershipRepository()
                .findByUserIdAndOrganizationId(user.getId(), organizationId)
                .orElseThrow(TenantInterceptor::notFound);

        TenantContext.set(new TenantContext.Context(
                user, membership.getOrganization(), membership, membership.getRole()));
    }

    /**
     * Combina path e header. Ausência total → 400 TENANT_REQUIRED; valor
     * malformado → tratado como organização inexistente (404 genérico);
     * divergência path × header → 404 genérico.
     */
    private static UUID resolveOrganizationId(String pathValue, String headerValue) {
        boolean fromPath = pathValue != null && !pathValue.isBlank();
        boolean fromHeader = headerValue != null && !headerValue.isBlank();

        if (!fromPath && !fromHeader) {
            throw tenantRequired();
        }

        UUID pathId = fromPath ? parseOrganizationId(pathValue) : null;
        UUID headerId = fromHeader ? parseOrganizationId(headerValue) : null;

        if (pathId != null && headerId != null && !pathId.equals(headerId)) {
            // Path e header apontando para tenants diferentes: 404 genérico,
            // sem dizer qual dos dois "existe".
            throw notFound();
        }
        return pathId != null ? pathId : headerId;
    }

    private static UUID parseOrganizationId(String raw) {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException ex) {
            throw notFound();
        }
    }

    private static String pathOrganizationId(HttpServletRequest request) {
        Object attributes = request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attributes instanceof Map<?, ?> variables)) {
            return null;
        }
        Object value = variables.get(ORGANIZATION_ID_PATH_VARIABLE);
        return value == null ? null : value.toString();
    }

    /** O principal já é a entidade User (converter JWT do Prompt 03). */
    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ApiException("UNAUTHORIZED", "Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    private MembershipRepository membershipRepository() {
        MembershipRepository repository = membershipRepositories.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException(
                    "MembershipRepository indisponível — TenantScoped exige contexto JPA");
        }
        return repository;
    }

    private static boolean isTenantScoped(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        return handlerMethod.hasMethodAnnotation(TenantScoped.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), TenantScoped.class);
    }

    // --- erros (anti-enumeração: o mesmo 404 para "não existe" e "sem acesso")

    private static ApiException notFound() {
        return new ApiException("NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND);
    }

    private static ApiException tenantRequired() {
        return new ApiException(
                "TENANT_REQUIRED",
                "Organization context required",
                HttpStatus.BAD_REQUEST);
    }
}
