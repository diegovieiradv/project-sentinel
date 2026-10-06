package com.sentinel.project.dashboard.dto;

import java.util.List;

/**
 * Carteira de incidentes ATIVOS do projeto — contagem por status do
 * vocabulário fechado (menos {@code RESOLVED}) + as primeiras linhas.
 *
 * <p><b>Invariante:</b>
 * {@code activeTotal = open + investigating + identified} —
 * {@code RESOLVED} não é ativo e por isso não entra na soma; o GROUP BY
 * filtra {@code status <> RESOLVED} antes de contar.</p>
 *
 * <p>{@code activeIncidents} é o recorte de exibição (máximo 10) na mesma
 * ordem de severidade/tempo do restante da API de incidentes.</p>
 */
public record IncidentsSummary(
        long activeTotal,
        long open,
        long investigating,
        long identified,
        List<ActiveIncidentItem> activeIncidents) {
}
