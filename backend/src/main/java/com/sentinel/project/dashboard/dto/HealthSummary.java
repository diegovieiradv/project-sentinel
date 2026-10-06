package com.sentinel.project.dashboard.dto;

/**
 * Carteira de saúde dos serviços do projeto — agregação de
 * {@code Service.status} (Prompt 10), lida uma única vez por contagem em
 * GROUP BY (não por N selects por serviço).
 *
 * <p><b>Invariante:</b>
 * {@code totalServices = operational + degraded + unavailable + unknown}.
 * Os quatro estados cobrem o vocabulário fechado do CHECK da V6, então a soma
 * fecha sempre; os testes de consistência verificam a igualdade.</p>
 *
 * <p>Contagem sobre TODOS os serviços do projeto (ativos e inativos): o
 * painel é o retrato do catálogo, e o histórico/serviço desligado continua
 * legível nos demais endpoints — mesma leitura da métrica por serviço.</p>
 */
public record HealthSummary(
        long totalServices,
        long operational,
        long degraded,
        long unavailable,
        long unknown) {
}
