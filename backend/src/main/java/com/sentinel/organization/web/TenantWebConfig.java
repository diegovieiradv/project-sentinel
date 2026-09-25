package com.sentinel.organization.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra o {@link TenantInterceptor} na pilha MVC.
 *
 * <p>Classe {@code @Configuration} NOVA e separada de propósito: o
 * {@code SecurityConfig} (Prompt 03) não é modificado — o interceptor pertence
 * à camada MVC (resolução de contexto), não à de segurança (autenticação).
 * Ordem correta: a cadeia Spring Security autentica primeiro; o DispatcherServlet
 * só então executa {@code preHandle} com o principal já resolvido.</p>
 *
 * <p>Futuros módulos não precisam mexer aqui: qualquer handler anotado com
 * {@link TenantScoped} passa a ser resolvido automaticamente.</p>
 */
@Configuration
public class TenantWebConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    public TenantWebConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor);
    }
}
