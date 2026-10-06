package com.sentinel.project.repository;

/**
 * Contagem de serviços por status (Prompt 16) — linha do GROUP BY que
 * alimenta o {@code health} do painel.
 *
 * <p>{@code status} chega como {@code String} porque a query é nativa (o
 * CHECK da V6 já garante o vocabulário fechado) e a tradução para
 * {@link com.sentinel.project.model.ServiceStatus} acontece no service —
 * mesma conservadoria das demais projeções nativas do projeto.</p>
 */
public interface ServiceStatusCountProjection {

    String getStatus();

    long getTotal();
}
