package com.sentinel.organization.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um handler que EXIGE contexto de tenant resolvido antes da execução.
 *
 * <p>O {@link TenantInterceptor} detecta a anotação e resolve a organização
 * ativa nesta ordem:</p>
 * <ol>
 *   <li>path variable {@code organizationId} (ex.: {@code /organizations/{organizationId}/members});</li>
 *   <li>header {@code X-Organization-Id} (endpoints tenant-scoped sem org no
 *       path — módulos futuros como projects/services/monitoring).</li>
 * </ol>
 *
 * <p>Resolver = validar que o usuário autenticado tem Membership naquela
 * organização e popular o {@code TenantContext}. Nenhuma fonte → 400
 * {@code TENANT_REQUIRED}; organização inexistente, usuário sem membership ou
 * path/header divergentes → 404 genérico (anti-enumeração).</p>
 *
 * <p>Extensão única para os futuros módulos: anotar o handler e ler
 * {@code TenantContext.require()} no service — nada mais precisa mudar.</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantScoped {
}
