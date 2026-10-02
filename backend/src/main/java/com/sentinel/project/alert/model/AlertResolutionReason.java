package com.sentinel.project.alert.model;

/**
 * Motivo pelo qual UM alerta deixou de disparar (Prompt 13) — três variantes,
 * todas de domínio.
 *
 * <p><b>Por que existe uma coluna própria (correção da revisão):</b> a
 * resolução <b>não pode</b> sobrescrever a mensagem original do FIRING — ela
 * é a única evidência de <em>por que</em> o alerta abriu. Guardar o motivo
 * numa coluna separada preserva o snapshot íntegro: a mensagem continua sendo
 * a do disparo em qualquer desfecho, e a UI/história ainda sabe se o alerta
 * fechou sozinho, pela desativação da regra ou pela desativação do pai.</p>
 *
 * <ul>
 *   <li>{@link #AUTO} — a condição voltou ao normal (recuperação observada na
 *       janela);</li>
 *   <li>{@link #RULE_DEACTIVATED} — a regra foi desativada com o alerta ainda
 *       aberto (soft-delete via {@code .../activation});</li>
 *   <li>{@link #PARENT_INACTIVE} — o serviço ou o projeto foi desativado
 *       (estado congelado, alerta fechado sem afirmação sobre saúde).</li>
 * </ul>
 *
 * <p>Persistido como {@code VARCHAR(20)} + CHECK anulável
 * {@code alerts_resolution_reason_check} (nulo enquanto FIRING).</p>
 */
public enum AlertResolutionReason {

    /** Recuperação: a condição deixou de valer. */
    AUTO,

    /** Regra desativada com o alerta aberto. */
    RULE_DEACTIVATED,

    /** Serviço ou projeto desativado com o alerta aberto. */
    PARENT_INACTIVE
}
