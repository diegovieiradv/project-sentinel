package com.sentinel.project.incident.repository;

/**
 * Contagem de incidentes ATIVOS por status (Prompt 16) — linha do GROUP BY
 * que alimenta o {@code incidents} do painel (o filtro {@code <> RESOLVED}
 * está na query, então {@code RESOLVED} nunca aparece nesta projeção).
 *
 * <p>{@code status} chega como {@code String} (query nativa; o CHECK da V11
 * garante o vocabulário) e a tradução para
 * {@link com.sentinel.project.incident.model.IncidentStatus} é do service.</p>
 */
public interface IncidentStatusCountProjection {

    String getStatus();

    long getTotal();
}
