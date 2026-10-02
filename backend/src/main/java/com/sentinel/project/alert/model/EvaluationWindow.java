package com.sentinel.project.alert.model;

/**
 * Janela de avaliação de uma regra de alerta (Prompt 12) — enum type-safe, sem
 * string livre e sem duração arbitrária.
 *
 * <p><b>Cinco opções, alinhadas aos buckets do Prompt 11:</b> cada valor tem
 * em segundos o tamanho de uma janela que os agregados históricos já cobrem,
 * para que o Alert Engine futuro (Prompt 13) leia as MESMAS métricas que a UI
 * de métricas exibe — sem recalcular nada nem abrir janela que o histórico não
 * sustenta.</p>
 *
 * <p><b>Sem 30 dias:</b> janela longa demais adia o alerta operacional e não
 * tem lastro no retenção atual — é decisão explícita de escopo (Seção 18 do
 * prompt), não uma limitação acidental. Persistido como {@code VARCHAR(30)} +
 * CHECK {@code alert_rules_window_check} da migration V9.</p>
 */
public enum EvaluationWindow {

    /** 5 minutos (300 s) — alta frequência. */
    FIVE_MINUTES(300),

    /** 15 minutos (900 s) — padrão operacional. */
    FIFTEEN_MINUTES(900),

    /** 1 hora (3600 s) — padrão de SLA. */
    ONE_HOUR(3600),

    /** 6 horas (21600 s) — tendência. */
    SIX_HOURS(21600),

    /** 24 horas (86400 s) — SLA diário. */
    TWENTY_FOUR_HOURS(86400);

    private final long seconds;

    EvaluationWindow(long seconds) {
        this.seconds = seconds;
    }

    /** Tamanho da janela em segundos — o Alert Engine futuro usa isto, não o nome. */
    public long seconds() {
        return seconds;
    }
}
