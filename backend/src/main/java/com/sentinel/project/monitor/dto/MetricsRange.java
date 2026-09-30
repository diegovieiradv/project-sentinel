package com.sentinel.project.monitor.dto;

import com.sentinel.error.ApiException;

import org.springframework.http.HttpStatus;

/**
 * Faixas temporais de métrica (Prompt 11) — o único parâmetro de janela da
 * API de histórico agregado.
 *
 * <p><b>Grades fixas, aprovadas:</b> cada faixa fixa o tamanho do bucket
 * ({@code bucketSeconds}) e a quantidade de buckets ({@code bucketCount}).
 * Nada no cliente escolhe granularidade: o servidor conhece a grade, o
 * custo é previsível (no máximo 30 buckets por resposta) e o formato do
 * eixo é determinístico.</p>
 *
 * <p><b>Janela sempre em buckets COMPLETOS:</b> o fim da janela é o instante
 * de agora truncado no tamanho do bucket e o início é {@code fim −
 * rangeSeconds}. O bucket em curso (parcial) fica de fora — assim nenhum
 * bucket da resposta muda de forma no meio do carregamento.</p>
 *
 * <p><b>Semântica:</b> a janela é meia-aberta {@code [from, to)} — um check
 * cujo {@code startedAt} cai exatamente no fim pertence ao bucket seguinte e
 * portanto está fora da resposta.</p>
 */
public enum MetricsRange {

    /** 1 hora em buckets de 5 minutos (12 buckets). */
    H1("1h", 300, 12),

    /** 6 horas em buckets de 15 minutos (24 buckets). */
    H6("6h", 900, 24),

    /** 24 horas em buckets de 1 hora (24 buckets) — default do servidor. */
    H24("24h", 3600, 24),

    /** 7 dias em buckets de 6 horas (28 buckets). */
    D7("7d", 21600, 28),

    /** 30 dias em buckets de 1 dia (30 buckets). */
    D30("30d", 86400, 30);

    private final String key;
    private final long bucketSeconds;
    private final int bucketCount;

    MetricsRange(String key, long bucketSeconds, int bucketCount) {
        this.key = key;
        this.bucketSeconds = bucketSeconds;
        this.bucketCount = bucketCount;
    }

    /** Literal do contrato da API ({@code 1h | 6h | 24h | 7d | 30d}). */
    public String key() {
        return key;
    }

    /** Tamanho de cada bucket, em segundos. */
    public long bucketSeconds() {
        return bucketSeconds;
    }

    /** Quantidade de buckets devolvidos (tamanho máximo da série). */
    public int bucketCount() {
        return bucketCount;
    }

    /** Duração total da janela, em segundos ({@code bucketSeconds × bucketCount}). */
    public long rangeSeconds() {
        return bucketSeconds * bucketCount;
    }

    /** Faixa usada quando o parâmetro {@code range} está ausente. */
    public static MetricsRange defaultRange() {
        return H24;
    }

    /**
     * Lê o query param {@code range}: ausente ou em branco → default do
     * servidor; qualquer outro valor → o MESMO 400 da API de monitores
     * (mensagem no formato {@code "campo: must be one of ..."}).
     *
     * @param raw valor cru do parâmetro (pode ser {@code null})
     * @return a faixa escolhida
     * @throws ApiException 400 {@code VALIDATION_ERROR} para valor desconhecido
     */
    public static MetricsRange fromKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultRange();
        }
        String value = raw.trim();
        for (MetricsRange range : values()) {
            if (range.key.equals(value)) {
                return range;
            }
        }
        throw new ApiException(
                "VALIDATION_ERROR",
                "range: must be one of 1h, 6h, 24h, 7d, 30d",
                HttpStatus.BAD_REQUEST);
    }
}
