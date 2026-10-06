package com.sentinel.project.alert.repository;

/**
 * Contagem de alertas FIRING por severidade (Prompt 16) — linha do GROUP BY
 * que alimenta o {@code alerts} do painel.
 *
 * <p>{@code severity} chega como {@code String} (query nativa; o CHECK da
 * V10 garante {@code SEV1..SEV4}) e a tradução para
 * {@link com.sentinel.project.alert.model.AlertSeverity} é do service.</p>
 */
public interface AlertSeverityCountProjection {

    String getSeverity();

    long getTotal();
}
