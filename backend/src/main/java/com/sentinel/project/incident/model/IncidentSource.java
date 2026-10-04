package com.sentinel.project.incident.model;

/**
 * Origem do incidente (Prompt 15) — vocabulário fechado amarrado pelo CHECK
 * {@code incidents_source_check} da migration V12.
 *
 * <p><b>Dois rótulos, duas histórias diferentes:</b></p>
 * <ul>
 *   <li>{@link #MANUAL} — aberto por um humano via
 *       {@code POST .../incidents} (o único caminho até o Prompt 14, e ainda
 *       o único caminho quando a regra não pede automação);</li>
 *   <li>{@link #ALERT_AUTOMATION} — nasceu da automação Alert → Incident
 *       (Prompt 15): a regra tinha {@code autoCreateIncident=true}, o alerta
 *       disparou e a correlação criou/reutilizou o incidente do serviço.</li>
 * </ul>
 *
 * <p><b>{@code createdByUserId} acompanha a origem</b>: MANUAL sempre tem
 * ator (validado no service antes de qualquer escrita); ALERT_AUTOMATION
 * nasce com {@code null} — não existe pessoa por trás de uma abertura
 * automática, e inventar um "autor" seria mentira de auditoria.</p>
 */
public enum IncidentSource {

    /** Aberto por um humano via API (estado pré-Prompt 15 e o default da coluna). */
    MANUAL,

    /** Criado/reutilizado pela automação Alert → Incident quando a regra pede. */
    ALERT_AUTOMATION
}
