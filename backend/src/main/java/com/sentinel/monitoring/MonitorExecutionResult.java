package com.sentinel.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * <p>Resultado de UMA execução de checagem (Prompt 09) — <b>transitório,
 * in-memory</b>: nasce no {@link HttpMonitorExecutor}, é logado (sem corpo)
 * pelo {@link MonitorExecutionTask} e descartado. Nada aqui é persistido:
 * não existe tabela de resultados, entidade {@code MonitorCheck} nem
 * migration de histórico neste prompt.</p>
 *
 * <p><b>O que NUNCA tem aqui:</b> corpo da resposta (o transporte usa
 * {@code BodyHandlers.discarding()}), URL com query string, headers,
 * cookies, credenciais ou mensagens de exceção vindo da rede. O
 * {@link #message()} é uma string de domínio, curta e sem segredo.</p>
 *
 * <p><b>Invariantes do construtor compacto:</b> {@code SUCCESS} sem categoria
 * de falha; qualquer outro desfecho COM categoria. {@code httpStatus} só está
 * presente quando houve resposta recebida (o request completou em rede).</p>
 *
 * @param monitorId      monitor checado (nulo apenas quando o executor
 *                       recebeu um monitor inválido — bug interno, nunca no
 *                       caminho produtivo)
 * @param startedAt      instante em que a execução começou (relógio do Sentinel)
 * @param finishedAt     instante em que terminou
 * @param durationMillis duração calculada de {@code startedAt} a {@code finishedAt}
 * @param httpStatus     status HTTP recebido, se houve resposta
 * @param outcome        desfecho ({@link MonitorOutcome})
 * @param failureCategory motivo da falha, vazio em sucesso
 * @param message        mensagem sanitizada (sem corpo/URL/secrets)
 */
public record MonitorExecutionResult(
        UUID monitorId,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        Optional<Integer> httpStatus,
        MonitorOutcome outcome,
        Optional<FailureCategory> failureCategory,
        String message) {

    public MonitorExecutionResult {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(failureCategory, "failureCategory");
        Objects.requireNonNull(message, "message");

        if (outcome == MonitorOutcome.SUCCESS && failureCategory.isPresent()) {
            throw new IllegalArgumentException("success result cannot carry a failure category");
        }
        if (outcome != MonitorOutcome.SUCCESS && failureCategory.isEmpty()) {
            throw new IllegalArgumentException("failed result must carry a failure category");
        }
    }

    /**
     * Fábrica única — calcula a duração a partir dos instantes e converte
     * argumentos anuláveis ({@code status}/{@code category}) em
     * {@link Optional}, para que nenhum call site monte o record na mão com
     * a ordem de campos trocada.
     *
     * @param status  status HTTP recebido ou {@code null} se não houve resposta
     * @param category categoria de falha ou {@code null} em sucesso
     */
    public static MonitorExecutionResult of(
            UUID monitorId,
            Instant startedAt,
            Instant finishedAt,
            Integer status,
            MonitorOutcome outcome,
            FailureCategory category,
            String message) {
        return new MonitorExecutionResult(
                monitorId,
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(),
                Optional.ofNullable(status),
                outcome,
                Optional.ofNullable(category),
                message);
    }

    /** @return {@code true} quando a checagem terminou em sucesso. */
    public boolean isSuccess() {
        return outcome == MonitorOutcome.SUCCESS;
    }
}
