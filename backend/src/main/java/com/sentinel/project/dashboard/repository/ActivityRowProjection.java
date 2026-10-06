package com.sentinel.project.dashboard.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Linha da atividade recente (Prompt 16) — o produto da UNIÃO das três
 * fontes (alertas, incidentes e timeline) já na janela e ordenação finais.
 *
 * <p>{@code type}/{@code resourceType}/{@code severity} chegam como
 * {@code String} (query nativa) e viram enums no service; a tradução é
 * feita uma vez por linha, nunca por serviço.</p>
 */
public interface ActivityRowProjection {

    String getType();

    Instant getTimestamp();

    UUID getServiceId();

    String getServiceName();

    String getSeverity();

    String getTitle();

    UUID getResourceId();

    String getResourceType();
}
