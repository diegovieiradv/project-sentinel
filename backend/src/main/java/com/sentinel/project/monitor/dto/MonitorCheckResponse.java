package com.sentinel.project.monitor.dto;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.project.monitor.model.CheckFailureCategory;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.MonitorCheck;

/**
 * Contrato de saída do histórico de checagens (Prompt 10) — entidade JPA
 * nunca exposta cru.
 *
 * <p><b>Só resultado, nunca configuração:</b> identidade da checagem, o
 * monitor a que pertence, instantes/duração e a CLASSIFICAÇÃO persistida
 * ({@code outcome} + {@code failureCategory} + mensagem de domínio curta).
 * É o inverso exato de {@code MonitorResponse}, que só expõe configuração.</p>
 *
 * <p><b>O que NUNCA aparece aqui:</b> corpo da resposta, headers, cookies,
 * tokens, URL com query string ou stack trace — nada disso é persistido (V8)
 * e o {@code message} já nasce sanitizado/truncado pelo recorder. O
 * {@code httpStatus} é anulável: só existe quando a resposta foi recebida
 * (uma falha DNS/timeout não tem status).</p>
 *
 * <p>{@code monitorId} vem do path já provado pela cadeia do tenant (a query
 * é escopada por ele), então o DTO não precisa tocar na associação LAZY do
 * check — nem um SELECT extra por linha.</p>
 */
public record MonitorCheckResponse(
        UUID id,
        UUID monitorId,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        Integer httpStatus,
        CheckOutcome outcome,
        CheckFailureCategory failureCategory,
        String message,
        Instant createdAt) {

    public static MonitorCheckResponse from(UUID monitorId, MonitorCheck check) {
        return new MonitorCheckResponse(
                check.getId(),
                monitorId,
                check.getStartedAt(),
                check.getFinishedAt(),
                check.getDurationMillis(),
                check.getHttpStatus(),
                check.getOutcome(),
                check.getFailureCategory(),
                check.getMessage(),
                check.getCreatedAt());
    }
}
