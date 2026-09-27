package com.sentinel.project.model;

/**
 * Estado <b>operacional</b> de um serviço (Prompt 07) — um eixo INDEPENDENTE
 * de {@code active}.
 *
 * <p><b>Active ≠ status:</b> {@code active} diz se o cadastro do serviço
 * participa do catálogo (ativação/desativação idempotente, soft-delete do
 * módulo); {@code status} diz como o serviço está operando HOJE. Desligar um
 * serviço ({@code active=false}) não diz nada sobre a saúde dele — e vice-versa.
 * Um serviço inativo pode continuar {@code UNAVAILABLE} no registro.</p>
 *
 * <p><b>Neste prompt ninguém escreve o status:</b> toda criação nasce
 * {@link #UNKNOWN} e nenhuma rota o altera. O enum existe como contrato de
 * dado (coluna {@code status VARCHAR(20)} + CHECK na migration V6) para que os
 * prompts futuros de monitoramento tenham onde registrar a observação real —
 * expor um status "fake" (tudo operacional) seria falsa observabilidade.</p>
 *
 * <p>Domínio idêntico ao CHECK {@code services_status_check} da migration V6:
 * a enumeração é a validação na aplicação, o banco é a última barreira.</p>
 */
public enum ServiceStatus {

    /** Registrado como saudável — atribuído apenas por quem passar a coletar de fato. */
    OPERATIONAL,

    /** Degradado (funcionando com limitação). */
    DEGRADED,

    /** Indisponível. */
    UNAVAILABLE,

    /** Estado inicial de TODO serviço: ainda não observado (não é "saudável"). */
    UNKNOWN
}
