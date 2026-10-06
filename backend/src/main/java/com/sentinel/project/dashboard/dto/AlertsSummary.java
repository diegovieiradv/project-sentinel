package com.sentinel.project.dashboard.dto;

import java.util.List;

/**
 * Carteira de alertas em FIRING do projeto — contagem por severidade do
 * vocabulário fechado {@code SEV1..SEV4} + as primeiras linhas para a lista
 * da UI.
 *
 * <p><b>Invariante:</b>
 * {@code firingTotal = sev1 + sev2 + sev3 + sev4} — as quatro colunas cobrem
 * o CHECK da V10, então a soma fecha sempre.</p>
 *
 * <p>{@code activeAlerts} é o recorte de exibição (máximo 10, severidade
 * SEV1→SEV4 e depois o mais recente), enquanto as contagens vêm do GROUP BY
 * sobre o CONJUNTO inteiro — a lista nunca infla o total.</p>
 */
public record AlertsSummary(
        long firingTotal,
        long sev1,
        long sev2,
        long sev3,
        long sev4,
        List<ActiveAlertItem> activeAlerts) {
}
