package com.sentinel.project.alert.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Organization;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.Service;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorMethod;

import org.junit.jupiter.api.Test;

/**
 * Alerta disparado (Prompt 13) — a entidade RESULTADO e suas invariantes,
 * testadas sem Spring, sem banco e sem relógio real.
 *
 * <p>Cobre os critérios 10–18 (um método por critério, nome
 * {@code criterioNN_...} e o número citado abaixo de cada teste): nascimento
 * FIRING, snapshot da
 * regra no instante do disparo, escopo de monitor copiado, sanitização da
 * mensagem (controles ISO, trim, corte em 500 seguro de surrogate),
 * resolução que NUNCA toca a mensagem, obrigatoriedade dos argumentos de
 * transição e o toque de deduplicação.</p>
 *
 * <p><b>Entidades transitórias:</b> id e timestamps nascem nos callbacks JPA
 * ({@code @PrePersist}) — os ids usados nas asserções são injetados por
 * reflexão, mesma técnica do {@code MonitorFixtures}.</p>
 */
class AlertModelTest {

    private static final Instant FIRED_AT = Instant.parse("2026-03-01T12:34:56Z");
    private static final Instant LATER = Instant.parse("2026-03-01T12:39:56Z");

    // --- fixtures transitórias ----------------------------------------------

    private static Service detachedService() {
        Organization organization = new Organization("Alert Model Corp");
        Project project = new Project(organization, "api", "projeto");
        return new Service(project, "checkout", "serviço");
    }

    private static Monitor detachedMonitor() {
        Monitor monitor = new Monitor(
                null,
                "Health",
                "https://93.184.216.34/health",
                MonitorMethod.GET,
                60,
                5000);
        injectId(monitor, UUID.fromString("7d4e2b1a-9c3f-4d6e-8b2a-5f1e0c9d8a7b"));
        return monitor;
    }

    private static AlertRule availabilityRule() {
        return new AlertRule(
                detachedService(),
                "Availability below 99.9",
                null,
                AlertRuleType.AVAILABILITY,
                AlertOperator.LESS_THAN,
                new BigDecimal("99.9"),
                null,
                EvaluationWindow.FIFTEEN_MINUTES,
                AlertSeverity.SEV1);
    }

    private static AlertRule monitorScopedRule() {
        return new AlertRule(
                detachedService(),
                "Latency above 800ms",
                detachedMonitor(),
                AlertRuleType.AVERAGE_LATENCY,
                AlertOperator.GREATER_THAN,
                new BigDecimal("800"),
                null,
                EvaluationWindow.FIVE_MINUTES,
                AlertSeverity.SEV2);
    }

    private static void injectId(Monitor monitor, UUID id) {
        try {
            Field field = Monitor.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(monitor, id);
        }
        catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot inject monitor id for tests", ex);
        }
    }

    // --- #10–#12 nascimento e snapshot --------------------------------------

    @Test
    void criterio10_nasce_firing_sem_resolucao() {
        Alert alert = new Alert(
                detachedService(), availabilityRule(), "msg", new BigDecimal("97.5"), FIRED_AT);

        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);
        assertThat(alert.getResolutionReason()).isNull();
        assertThat(alert.getResolvedAt()).isNull();
        assertThat(alert.getStartedAt()).isEqualTo(FIRED_AT);
        assertThat(alert.getLastEvaluatedAt()).isEqualTo(FIRED_AT);
    }

    @Test
    void criterio11_snapshot_da_regra_no_instante_do_disparo() {
        AlertRule rule = monitorScopedRule();
        Alert alert = new Alert(detachedService(), rule, "msg", new BigDecimal("1240"), FIRED_AT);

        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.SEV2);
        assertThat(alert.getRuleType()).isEqualTo(AlertRuleType.AVERAGE_LATENCY);
        assertThat(alert.getRuleOperator()).isEqualTo(AlertOperator.GREATER_THAN);
        assertThat(alert.getRuleThresholdNumeric()).isEqualByComparingTo("800");
        assertThat(alert.getRuleThresholdStatus()).isNull();
        assertThat(alert.getRuleWindow()).isEqualTo(EvaluationWindow.FIVE_MINUTES);
    }

    @Test
    void criterio12_escopo_de_monitor_copiado_e_nulo_em_service_level() {
        Alert monitorLevel = new Alert(
                detachedService(), monitorScopedRule(), "msg", null, FIRED_AT);
        assertThat(monitorLevel.getMonitorId())
                .as("monitorId é cópia do escopo da regra")
                .isEqualTo(UUID.fromString("7d4e2b1a-9c3f-4d6e-8b2a-5f1e0c9d8a7b"));

        Alert serviceLevel = new Alert(
                detachedService(), availabilityRule(), "msg", null, FIRED_AT);
        assertThat(serviceLevel.getMonitorId())
                .as("Service-level não tem monitor")
                .isNull();
    }

    // --- #13–#14 sanitização da mensagem ------------------------------------

    @Test
    void criterio13_mensagem_remove_controles_iso_e_trima() {
        Alert alert = new Alert(
                detachedService(),
                availabilityRule(),
                "  Availability\u0000 below\u001F 99.9  ",
                null,
                FIRED_AT);

        assertThat(alert.getMessage()).isEqualTo("Availability below 99.9");
    }

    @Test
    void criterio14_mensagem_truncada_em_500_sem_partir_surrogate() {
        // 500 'a' seguidos de um emoji (surrogate pair) + lixo
        String emoji = "🔥"; // U+1F525 = 2 chars UTF-16
        String raw = "a".repeat(500) + emoji + "overflow";

        Alert alert = new Alert(detachedService(), availabilityRule(), raw, null, FIRED_AT);

        assertThat(Alert.sanitizeMessage(raw)).hasSize(500);
        assertThat(alert.getMessage()).hasSize(500);
        // o corte nunca deixa surrogate solto (500 chars de 'a' já é limpo aqui)
        assertThat(Character.isHighSurrogate(alert.getMessage().charAt(499))).isFalse();

        // corte exato NUM surrogate: 499 'a' + emoji ocupa 501 → corta para 499
        String straddling = "a".repeat(499) + emoji;
        assertThat(Alert.sanitizeMessage(straddling)).hasSize(499);
        assertThat(Alert.sanitizeMessage(straddling)).endsWith("a");
    }

    // --- #15–#16 resolução ---------------------------------------------------

    @Test
    void criterio15_resolucao_grava_motivo_e_nunca_toca_na_mensagem() {
        String message = "Availability below 99.9: availability 97.50%";
        Alert alert = new Alert(
                detachedService(), availabilityRule(), message, new BigDecimal("97.5"), FIRED_AT);

        alert.resolve(AlertResolutionReason.AUTO, LATER);

        assertThat(alert.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(alert.getResolutionReason()).isEqualTo(AlertResolutionReason.AUTO);
        assertThat(alert.getResolvedAt()).isEqualTo(LATER);
        assertThat(alert.getMessage())
                .as("a evidência do FIRING permanece intacta (correção da revisão)")
                .isEqualTo(message);
        assertThat(alert.getStartedAt())
                .as("startedAt nunca é reescrito pela resolução")
                .isEqualTo(FIRED_AT);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.SEV1);
        assertThat(alert.getRuleThresholdNumeric()).isEqualByComparingTo("99.9");
    }

    @Test
    void criterio16_resolucao_exige_motivo_e_instante() {
        Alert alert = new Alert(
                detachedService(), availabilityRule(), "msg", null, FIRED_AT);

        assertThatThrownBy(() -> alert.resolve(null, LATER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> alert.resolve(AlertResolutionReason.AUTO, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(alert.getStatus())
                .as("falha de asserção não deixa o alerta meio-resolvido")
                .isEqualTo(AlertStatus.FIRING);
    }

    // --- #17–#18 toque de deduplicação ---------------------------------------

    @Test
    void criterio17_toque_atualiza_avaliacao_e_valor_sem_mudar_inicio() {
        String message = "Availability below 99.9: availability 97.50%";
        Alert alert = new Alert(
                detachedService(), availabilityRule(), message, new BigDecimal("97.5"), FIRED_AT);

        alert.touch(LATER, new BigDecimal("96.0"));

        assertThat(alert.getLastEvaluatedAt()).isEqualTo(LATER);
        assertThat(alert.getTriggerValue()).isEqualByComparingTo("96.0");
        assertThat(alert.getStartedAt())
                .as("dedup não reescreve o início do alerta")
                .isEqualTo(FIRED_AT);
        assertThat(alert.getMessage()).isEqualTo(message);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FIRING);
        assertThat(alert.getResolutionReason()).isNull();
    }

    @Test
    void criterio18_toque_exige_instante() {
        Alert alert = new Alert(
                detachedService(), availabilityRule(), "msg", null, FIRED_AT);

        assertThatThrownBy(() -> alert.touch(null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
