package com.sentinel.project.monitor.repository;

import java.math.BigDecimal;

/**
 * Linha crua de um bucket agregado (Prompt 11) — a SERIE temporal lida do
 * banco, ainda sem arredondamento nem domínio.
 *
 * <p><b>Por que projeção e não entidade:</b> a leitura de métricas NUNCA
 * materializa {@code MonitorCheck}: o banco conta, soma e agrupa dentro da
 * janela e devolve uma linha por bucket (no máximo 30 por resposta). Não
 * existe {@code findAll} nem carga de histórico em memória.</p>
 *
 * <p>{@code bucketEpoch} vem como epoch em segundos (BIGINT) justamente
 * porque a grade ({@code generate_series}) e a agregação
 * ({@code floor(extract(epoch …)))} produzem o MESMO tipo: o JOIN é sobre um
 * inteiro exato, sem depender de timezone de sessão nem de aritmética de
 * {@code interval}. A conversão para {@link java.time.Instant} acontece no
 * service, em um único lugar.</p>
 *
 * <p>Os contadores já saem COALESCADOS a 0 (bucket vazio existe na grade) e
 * a latência (avg/min/max) permanece anulável: bucket sem nenhum check
 * SUCCESS/DEGRADED devolve {@code null}, não 0.</p>
 *
 * <p>Os nomes dos aliases entre aspas são o CONTRATO com a projeção: o
 * proxy da Spring Data lê a coluna pelo nome exato do getter.</p>
 */
public interface MetricsBucketProjection {

    /** Início do bucket, epoch UTC em segundos (BIGINT). */
    Long getBucketEpoch();

    long getSuccessCount();

    long getDegradedCount();

    long getTargetFailureCount();

    long getSentinelInternalFailureCount();

    /** Média de latência dos checks SUCCESS/DEGRADED do bucket ({@code null} sem nenhum). */
    BigDecimal getAvgDurationMillis();

    /** Menor latência (ms) entre SUCCESS/DEGRADED do bucket ({@code null} sem nenhum). */
    Long getMinDurationMillis();

    /** Maior latência (ms) entre SUCCESS/DEGRADED do bucket ({@code null} sem nenhum). */
    Long getMaxDurationMillis();
}
