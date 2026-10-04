package com.sentinel.project.alert.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.model.ServiceStatus;

import com.fasterxml.jackson.annotation.JsonSetter;

/**
 * Payload de PATCH
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}/alert-rules/{ruleId}
 * — atualização <b>parcial</b>.
 *
 * <p>Campo ausente ({@code null}) = <b>não altera</b> — todos os campos
 * seguem o critério de {@code ServiceUpdateRequest}: sem {@code @NotBlank},
 * a validação de cada campo PRESENTE é do service (que sabe distinguir
 * "ausente" de "em branco"). DTO fechado: sem {@code id}, {@code serviceId},
 * {@code active} nem timestamps (servidor; ativação tem rota própria) — e
 * chaves <b>desconhecidas</b> no JSON são <b>ignoradas</b>, nunca 400
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES} desabilitado pela auto-configuração do
 * Spring Boot; mesma convenção de {@code MonitorUpdateRequest} e
 * {@code ServiceUpdateRequest}, fixada por
 * {@code AlertRulePatchTest.patchIgnoresServerOwnedFields}).</p>
 *
 * <h2>Por que classe (POJO) e não record — decisão documentada</h2>
 *
 * <p>Para {@code monitorId} o contrato exige TRÊS estados (Seção 35 do
 * prompt):</p>
 * <ul>
 *   <li><b>ausente</b> do JSON → não altera o escopo (parcial);</li>
 *   <li><b>{@code null} explícito</b> ({@code "monitorId": null}) → vira
 *       Service-level (limpa o escopo);</li>
 *   <li><b>valor</b> → revalida ownership no service.</li>
 * </ul>
 *
 * <p>Um record não distingue ausente de {@code null}: Jackson injeta
 * {@code null} no canonical constructor nos DOIS casos (verificado
 * empiricamente com Jackson 3.1.5 — {@code Optional<UUID>} também colapsa os
 * dois estados em {@code Optional.empty}), e records não podem declarar
 * campos de instância extras onde uma flag de presença moraria. A solução
 * canônica para o dilema ausente-vs-null é uma classe com setter que registra
 * a PRESENÇA da propriedade: {@link #setMonitorId(UUID)} só é invocado
 * quando a chave existe no JSON.</p>
 *
 * <p>O restante do DTO é um POJO banal (campos privados + setters/getters
 * públicos, mesmo mapeamento do Jackson de sempre); a flag
 * {@code monitorIdSet} é privada SEM setter, então um cliente não consegue
 * forjá-la por payload. Um teste unitário de desserialização fixa este
 * contrato de três estados.</p>
 */
public class AlertRuleUpdateRequest {

    private String name;
    private AlertRuleType type;
    private AlertOperator operator;
    private BigDecimal thresholdNumeric;
    private ServiceStatus thresholdStatus;
    private EvaluationWindow window;
    private AlertSeverity severity;

    /**
     * Opt-in de automação Alert → Incident (Prompt 15) — {@link Boolean}
     * (wrapper) de propósito: no PATCH, "ausente" é "não altera" e só um
     * booleano PRIMITIVO não distingue ausente de {@code false} (igual ao
     * dilema de {@code monitorId}). {@code null} → não mexe na flag; valor →
     * liga/desliga para os PRÓXIMOS disparos (nunca reabre/recria incidente
     * já existente).
     */
    private Boolean autoCreateIncident;

    private UUID monitorId;
    private boolean monitorIdSet;

    // --- presence-aware setter: único método que distingue ausente de null ---

    /**
     * Invocado pelo Jackson SOMENTE quando a chave {@code monitorId} existe
     * no JSON — inclusive quando o valor é {@code null} (aí
     * {@code monitorIdSet = true} e {@code monitorId = null}, significando
     * "limpar escopo"). Ausente da payload → método nunca roda →
     * {@code monitorIdSet = false} → "não altera".
     */
    @JsonSetter
    public void setMonitorId(UUID monitorId) {
        this.monitorId = monitorId;
        this.monitorIdSet = true;
    }

    /** {@code true} quando o JSON trouxe a chave {@code monitorId} (valor ou null). */
    public boolean isMonitorIdSet() {
        return monitorIdSet;
    }

    /** Valor do escopo pedido — só meaningful quando {@link #isMonitorIdSet()}. */
    public UUID getMonitorId() {
        return monitorId;
    }

    // --- demais campos: null/ausente = não altera ---------------------------

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AlertRuleType getType() {
        return type;
    }

    public void setType(AlertRuleType type) {
        this.type = type;
    }

    public AlertOperator getOperator() {
        return operator;
    }

    public void setOperator(AlertOperator operator) {
        this.operator = operator;
    }

    public BigDecimal getThresholdNumeric() {
        return thresholdNumeric;
    }

    public void setThresholdNumeric(BigDecimal thresholdNumeric) {
        this.thresholdNumeric = thresholdNumeric;
    }

    public ServiceStatus getThresholdStatus() {
        return thresholdStatus;
    }

    public void setThresholdStatus(ServiceStatus thresholdStatus) {
        this.thresholdStatus = thresholdStatus;
    }

    public EvaluationWindow getWindow() {
        return window;
    }

    public void setWindow(EvaluationWindow window) {
        this.window = window;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(AlertSeverity severity) {
        this.severity = severity;
    }

    /** {@code null} = ausente da payload → flag não muda; senão liga/desliga. */
    public Boolean getAutoCreateIncident() {
        return autoCreateIncident;
    }

    public void setAutoCreateIncident(Boolean autoCreateIncident) {
        this.autoCreateIncident = autoCreateIncident;
    }
}
