package com.sentinel.project.monitor.model;

/**
 * Método HTTP usado pela checagem de fronteira — apenas leitura
 * {@link #GET} e {@link #HEAD}.
 *
 * <p>A restrição é deliberada: um monitor é uma SONDA de disponibilidade, não
 * um cliente genérico. Métodos com semântica de escrita ({@code POST},
 * {@code PUT}, {@code PATCH}, {@code DELETE}) ou de baixo nível
 * ({@code CONNECT}, {@code TRACE}) nunca chegam ao alvo configurado pelo
 * usuário. A aplicação valida o valor vindo do payload
 * ({@code method: must be GET or HEAD}) e o CHECK
 * {@code monitors_method_check} da migration V7 é a última linha de defesa.</p>
 */
public enum MonitorMethod {

    /** Traz o corpo da resposta (default da API). */
    GET,

    /** Traz apenas os headers — mais barato quando só interessa status/tamanho. */
    HEAD
}
