package com.sentinel.project.incident.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.organization.model.Organization;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.model.Project;
import com.sentinel.project.model.Service;

import org.junit.jupiter.api.Test;

/**
 * Incidente (Prompt 14) — entidade, máquina de estados e timeline, testadas
 * sem Spring, sem banco e sem relógio real.
 *
 * <p>Cobre o núcleo de domínio que a integração só observa de longe: nascimento
 * {@code OPEN} com {@code resolvedAt} nulo, sanitização de título/descrição
 * (controles ISO, trim, descrição vazia vira {@code null}), as <b>seis
 * transições permitidas</b> e os bloqueios (autotransição, retrocesso,
 * qualquer saída de {@code RESOLVED}), {@code resolvedAt} preenchido exatamente
 * na resolução, e o construtor de eventos com instante fornecido — a folha que
 * mantém a ordem da timeline.</p>
 *
 * <p><b>Entidades transitórias:</b> id e timestamps nascem nos callbacks JPA
 * ({@code @PrePersist}). Aqui os callbacks são chamados DIRETAMENTE porque o
 * teste mora no mesmo pacote da entidade — mesma técnica de "fixture sem
 * persistência" do {@code AlertModelTest}.</p>
 */
class IncidentModelTest {

    private static final Instant OPENED_AT = Instant.parse("2026-03-01T12:34:56Z");
    private static final Instant LATER = Instant.parse("2026-03-01T12:39:56Z");

    private static final UUID ACTOR =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID INCIDENT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    // --- fixtures transitórias ----------------------------------------------

    private static Service detachedService() {
        Organization organization = new Organization("Incident Model Corp");
        Project project = new Project(organization, "api", "projeto");
        return new Service(project, "checkout", "serviço");
    }

    private static Incident incident(String title) {
        return new Incident(
                detachedService(), title, null, AlertSeverity.SEV3, OPENED_AT, ACTOR);
    }

    // --- nascimento ---------------------------------------------------------

    @Test
    void nasce_open_sem_resolucao_com_autor_e_inicio() {
        Incident incident = incident("Paginação fora do ar");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getResolvedAt()).isNull();
        assertThat(incident.getStartedAt()).isEqualTo(OPENED_AT);
        assertThat(incident.getCreatedByUserId()).isEqualTo(ACTOR);
        assertThat(incident.getSeverity()).isEqualTo(AlertSeverity.SEV3);
        assertThat(incident.getDescription()).isNull();
        assertThat(incident.getService())
                .as("nasce pendurado no serviço dono")
                .isNotNull();
    }

    @Test
    void nasce_com_id_e_timestamps_apos_pre_persist() {
        Incident incident = incident("Sem id");

        assertThat(incident.getId()).isNull();
        assertThat(incident.getCreatedAt()).isNull();

        incident.onCreate();

        assertThat(incident.getId()).isNotNull();
        assertThat(incident.getCreatedAt()).isNotNull();
        assertThat(incident.getUpdatedAt()).isEqualTo(incident.getCreatedAt());
    }

    @Test
    void argumentos_obrigatorios_sao_rejeitados() {
        assertThatThrownBy(() -> new Incident(
                        null, "t", null, AlertSeverity.SEV3, OPENED_AT, ACTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Incident(
                        detachedService(), null, null, AlertSeverity.SEV3, OPENED_AT, ACTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Incident(
                        detachedService(), "t", null, null, OPENED_AT, ACTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Incident(
                        detachedService(), "t", null, AlertSeverity.SEV3, null, ACTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Incident(
                        detachedService(), "t", null, AlertSeverity.SEV3, OPENED_AT, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(incident("intacto").getStatus())
                .as("falha de asserção não deixa o incidente meio-criado")
                .isEqualTo(IncidentStatus.OPEN);
    }

    // --- origem (Prompt 15) -------------------------------------------------

    @Test
    void construtor_de_seis_argumentos_delega_para_origem_manual() {
        assertThat(incident("delegado").getSource())
                .as("o caminho antigo (Prompt 14) continua MANUAL, com ator")
                .isEqualTo(IncidentSource.MANUAL);
        assertThat(incident("delegado").getCreatedByUserId()).isEqualTo(ACTOR);
    }

    @Test
    void origem_manual_exige_autor_em_qualquer_construtor() {
        assertThatThrownBy(() -> new Incident(
                        detachedService(), "Manual sem autor", null,
                        AlertSeverity.SEV2, OPENED_AT, IncidentSource.MANUAL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdByUserId");
    }

    @Test
    void origem_de_automacao_aceita_autor_nulo() {
        Incident incident = new Incident(
                detachedService(),
                "[AUTO] Regra de latência fora",
                null,
                AlertSeverity.SEV1,
                OPENED_AT,
                IncidentSource.ALERT_AUTOMATION,
                null);

        assertThat(incident.getSource()).isEqualTo(IncidentSource.ALERT_AUTOMATION);
        assertThat(incident.getCreatedByUserId())
                .as("automação não tem ator humano — só a origem diz quem abriu")
                .isNull();
        assertThat(incident.getStatus())
                .as("nasce OPEN como qualquer outro, mesmo sem ator")
                .isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getStartedAt()).isEqualTo(OPENED_AT);
    }

    @Test
    void origem_nula_e_rejeitada_antes_de_mutar() {
        assertThatThrownBy(() -> new Incident(
                        detachedService(), "t", null, AlertSeverity.SEV3, OPENED_AT,
                        null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
        assertThat(incident("intacto").getSource())
                .as("objeto inválido não é meio-criado nem altera os válidos")
                .isEqualTo(IncidentSource.MANUAL);
    }

    // --- sanitização --------------------------------------------------------

    @Test
    void sanitizeText_remove_controles_iso_e_trima() {
        assertThat(Incident.sanitizeText("  Paginação\u0000 fora\u001F do ar  ", 200))
                .isEqualTo("Paginação fora do ar");
        assertThat(Incident.sanitizeText(null, 200)).isEmpty();
    }

    @Test
    void sanitizeText_trunca_no_limite_sem_partir_surrogate() {
        String emoji = "🔥"; // U+1F525 = 2 chars UTF-16
        String straddling = "a".repeat(199) + emoji;

        assertThat(Incident.sanitizeText(straddling, 200)).hasSize(199);
        assertThat(Incident.sanitizeText("a".repeat(300) + emoji, 200)).hasSize(200);
    }

    @Test
    void descricao_vazia_vira_null_e_nao_string_vazia() {
        Incident incident = incident("Título");

        incident.setDescription("");
        assertThat(incident.getDescription())
                .as("não existe 'descrição vazia', existe 'sem descrição'")
                .isNull();

        incident.setDescription("agora sim");
        assertThat(incident.getDescription()).isEqualTo("agora sim");
    }

    @Test
    void descricao_nula_permanece_nula() {
        Incident incident = incident("Título");
        incident.setDescription(null);
        assertThat(incident.getDescription()).isNull();
    }

    /**
     * O trim/controle de espaço é papel do {@code IncidentService}
     * ({@code sanitizeText} antes de chegar aqui) — a entidade guarda o que
     * recebe. O contrato dela é só o "vazio vira ausente".
     */
    @Test
    void descricao_em_branco_e_guardada_como_veio() {
        Incident incident = incident("Título");
        incident.setDescription("   ");
        assertThat(incident.getDescription()).isEqualTo("   ");
    }

    // --- máquina de estados -------------------------------------------------

    @Test
    void transicoes_permitidas_do_grafo() {
        assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.INVESTIGATING)).isTrue();
        assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.IDENTIFIED)).isTrue();
        assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.RESOLVED)).isTrue();
        assertThat(IncidentStatus.INVESTIGATING.canTransitionTo(IncidentStatus.IDENTIFIED))
                .isTrue();
        assertThat(IncidentStatus.INVESTIGATING.canTransitionTo(IncidentStatus.RESOLVED))
                .isTrue();
        assertThat(IncidentStatus.IDENTIFIED.canTransitionTo(IncidentStatus.RESOLVED)).isTrue();
    }

    @Test
    void retrocesso_e_autotransicao_nao_existem() {
        assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.OPEN)).isFalse();
        assertThat(IncidentStatus.INVESTIGATING.canTransitionTo(IncidentStatus.OPEN)).isFalse();
        assertThat(IncidentStatus.INVESTIGATING.canTransitionTo(IncidentStatus.INVESTIGATING))
                .isFalse();
        assertThat(IncidentStatus.IDENTIFIED.canTransitionTo(IncidentStatus.INVESTIGATING))
                .isFalse();
        assertThat(IncidentStatus.IDENTIFIED.canTransitionTo(IncidentStatus.OPEN)).isFalse();
        assertThat(IncidentStatus.OPEN.canTransitionTo(null)).isFalse();
    }

    @Test
    void resolved_e_terminal_para_tudo() {
        for (IncidentStatus target : IncidentStatus.values()) {
            assertThat(IncidentStatus.RESOLVED.canTransitionTo(target))
                    .as("RESOLVED não sai para %s (nem para si mesmo)", target)
                    .isFalse();
        }
        assertThat(IncidentStatus.RESOLVED.isResolved()).isTrue();
        assertThat(IncidentStatus.OPEN.isResolved()).isFalse();
        assertThat(IncidentStatus.INVESTIGATING.isResolved()).isFalse();
        assertThat(IncidentStatus.IDENTIFIED.isResolved()).isFalse();
    }

    @Test
    void resolvedAt_preenchido_exatamente_na_resolucao() {
        Incident incident = incident("Investigando");

        incident.transitionTo(IncidentStatus.INVESTIGATING, LATER);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(incident.getResolvedAt())
                .as("ainda não resolvido: a coluna continua nula")
                .isNull();

        incident.transitionTo(IncidentStatus.RESOLVED, LATER);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolvedAt()).isEqualTo(LATER);
    }

    @Test
    void transicao_invalida_nao_deixa_o_incidente_meio_mudado() {
        Incident incident = incident("Bloqueado");

        // argumentos obrigatórios chegam antes de qualquer mutação
        assertThatThrownBy(() -> incident.transitionTo(null, LATER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> incident.transitionTo(IncidentStatus.IDENTIFIED, null))
                .isInstanceOf(NullPointerException.class);
        // autotransição é erro, não no-op silencioso (critério 17)
        assertThatThrownBy(() -> incident.transitionTo(IncidentStatus.OPEN, LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(incident.getStatus())
                .as("a validação acontece ANTES de mutar")
                .isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getResolvedAt()).isNull();

        incident.transitionTo(IncidentStatus.INVESTIGATING, LATER);

        assertThatThrownBy(() -> incident.transitionTo(IncidentStatus.INVESTIGATING, LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> incident.transitionTo(IncidentStatus.OPEN, LATER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(incident.getStatus())
                .as("autotransição e retrocesso não mutam o estado")
                .isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(incident.getResolvedAt()).isNull();
    }

    @Test
    void reabertura_a_partir_de_resolved_e_impossivel() {
        Incident incident = incident("Encerrado");
        incident.transitionTo(IncidentStatus.RESOLVED, LATER);

        for (IncidentStatus target : IncidentStatus.values()) {
            assertThatThrownBy(() -> incident.transitionTo(target, LATER))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolvedAt())
                .as("reabrir é um NOVO incidente — resolvedAt não é desfeito")
                .isEqualTo(LATER);
    }

    // --- timeline -----------------------------------------------------------

    @Test
    void evento_com_instante_fornecido_guarda_o_instante() {
        IncidentEvent event = new IncidentEvent(
                INCIDENT_ID,
                IncidentEventType.SEVERITY_CHANGED,
                "Severity changed from SEV3 to SEV1",
                ACTOR,
                LATER);

        assertThat(event.getIncidentId()).isEqualTo(INCIDENT_ID);
        assertThat(event.getType()).isEqualTo(IncidentEventType.SEVERITY_CHANGED);
        assertThat(event.getMessage())
                .isEqualTo("Severity changed from SEV3 to SEV1");
        assertThat(event.getActorUserId()).isEqualTo(ACTOR);
        assertThat(event.getCreatedAt())
                .as("a ordem da timeline depende do instante recebido")
                .isEqualTo(LATER);
    }

    @Test
    void evento_sem_instante_deixa_o_pre_persist_preencher() {
        IncidentEvent event = new IncidentEvent(
                INCIDENT_ID, IncidentEventType.CREATED, "Incident created", null);

        assertThat(event.getCreatedAt()).isNull();
        assertThat(event.getActorUserId()).isNull();

        Instant before = Instant.now();
        event.onCreate();

        assertThat(event.getId()).isNotNull();
        assertThat(event.getCreatedAt())
                .as("o @PrePersist carimba o relógio real")
                .isBetween(before, Instant.now());
    }

    @Test
    void evento_de_dominio_exige_incidente_tipo_e_mensagem() {
        assertThatThrownBy(() -> new IncidentEvent(
                        null, IncidentEventType.CREATED, "Incident created", ACTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IncidentEvent(
                        INCIDENT_ID, null, "Incident created", ACTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IncidentEvent(
                        INCIDENT_ID, IncidentEventType.CREATED, null, ACTOR))
                .isInstanceOf(NullPointerException.class);
    }
}
