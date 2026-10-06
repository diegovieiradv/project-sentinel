package com.sentinel.project.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resposta de {@code GET .../projects/{projectId}/dashboard} (Prompt 16) —
 * painel operacional do projeto, leitura pura sobre os domínios existentes.
 *
 * <p><b>Composição, nunca nova verdade:</b> cada seção reutiliza o conceito
 * que já existe no seu domínio — {@code health} é {@code Service.status}
 * (Prompt 10), {@code availability} são as fórmulas do Prompt 11,
 * {@code alerts}/{@code incidents} são os modelos dos Prompts 13–14 e
 * {@code recentActivity} deriva de alertas, incidentes e da timeline do
 * Prompt 14. Nada aqui escreve nem recalcula estado.</p>
 *
 * <p><b>Janela única:</b> {@code windowFrom}/{@code windowTo} é o mesmo
 * recorte meia-aberta {@code [from, to)} alinhado à grade do Prompt 11 —
 * a MESMA janela governa {@code availability}, {@code recentActivity} e o
 * restante do painel, para que os números não se contradigam.</p>
 *
 * <p><b>Estado vazio é 200:</b> projeto sem serviço, sem check e sem
 * atividade responde com contagens zeradas, listas vazias e
 * {@code availabilityPercent = null} — nunca erro por "sem dado".</p>
 */
public record DashboardResponse(
        UUID projectId,
        String rangeKey,
        Instant windowFrom,
        Instant windowTo,
        HealthSummary health,
        AvailabilitySummary availability,
        AlertsSummary alerts,
        IncidentsSummary incidents,
        List<ServiceAttentionItem> servicesNeedingAttention,
        List<ActivityItem> recentActivity) {
}
