package com.sentinel.alerting;

import com.sentinel.project.monitor.model.Monitor;

/**
 * Gatilho do Alert Engine chamado pelo pipeline de checagens (Prompt 13) — a
 * costura entre "o check foi persistido e o estado recalculado" e "as regras
 * do serviço são avaliadas".
 *
 * <p><b>Por que uma interface:</b> a fronteira do motor de monitoramento
 * (Prompt 09/10) não pode conhecer o engine de alertas. A task injeta um
 * {@link AlertTrigger} e o chama como qualquer outra etapa do pipeline; em
 * produção o bean é {@link MonitoringAlertTrigger}, nos testes do dispatcher é
 * {@link #noop()} (nenhuma avaliação) ou um lambda que conta chamadas —
 * mesmo arranjo do {@code Clock} injetável.</p>
 *
 * <p><b>Contrato de falha:</b> NUNCA lança. Uma falha do Alert Engine não pode
 * invalidar o histórico já gravado nem segurar o monitor no in-flight.</p>
 */
@FunctionalInterface
public interface AlertTrigger {

    /**
     * Avalia as regras do serviço dono de {@code monitor} (silenciosamente —
     * erro vira log, nunca exceção para o chamador).
     *
     * @param monitor monitor cujo check acabou de ser persistido
     */
    void trigger(Monitor monitor);

    /** Gatilho inerte para os testes do dispatcher — não avalia nada. */
    static AlertTrigger noop() {
        return monitor -> { };
    }
}
