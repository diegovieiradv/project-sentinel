package com.sentinel.identity.auth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Emissão proativa do cookie {@code XSRF-TOKEN} (double-submit CSRF, Prompt 03).
 *
 * <p>O {@code CsrfFilter} só materializa o token (gera e grava o cookie) quando a
 * requisição exige proteção CSRF — POST/PUT/DELETE. Em GET ele apenas registra um
 * {@code Supplier} preguiçoso no atributo da request, e o cookie nunca sai do
 * servidor. O frontend precisa do valor ANTES do primeiro POST, então este advice
 * força a materialização em toda requisição MVC que chegar ao controller.</p>
 *
 * <p>{@code CsrfToken.getToken()} delega para o {@code DeferredCsrfToken}: se o
 * request já trouxe o cookie, apenas devolve o mesmo valor (sem reescrever);
 * se não trouxe, gera um novo e o grava na response.</p>
 *
 * <p>Método {@code void} é obrigatório: um valor de retorno seria injetado no
 * model. E o parâmetro é {@link HttpServletRequest} (resolvido pelo
 * {@code ServletRequestMethodArgumentResolver}) porque {@code CsrfToken} como
 * argumento de handler não tem resolver.</p>
 */
@ControllerAdvice
public class CsrfTokenCookieAdvice {

    private static final String CSRF_TOKEN_ATTRIBUTE = CsrfToken.class.getName();

    @ModelAttribute
    public void materializeCsrfCookie(HttpServletRequest request) {
        Object token = request.getAttribute(CSRF_TOKEN_ATTRIBUTE);
        if (token instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        }
    }
}
