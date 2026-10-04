package com.sentinel.project.alert.automation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.repository.AlertRepository;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentSource;
import com.sentinel.project.incident.service.IncidentService;
import com.sentinel.project.model.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Automação Alert → Incident (Prompt 15) — o orquestrador: decide, numa
 * ÚNICA transação atômica, se um alerta elegível vira (ou se junta a) um
 * incidente automático do serviço.
 *
 * <p><b>Fluxo, nesta ordem (cada passo rechecado DENTRO da transação — a
 * elegância nunca é decidida só por quem chamou):</b></p>
 * <ol>
 *   <li>carrega o alerta escopado ({@code findByServiceIdAndId} — o par
 *       (serviceId, alertId) do evento/hook prova a cadeia do dono, anti-IDOR);</li>
 *   <li>recheca elegibilidade: ainda {@code FIRING}, ainda SEM vínculo,
 *       regra ainda com {@code autoCreateIncident}, serviço e projeto ainda
 *       ativos — qualquer "não" é no-op silencioso (idempotência);</li>
 *   <li>correlação: existe um incidente automático ABERTO do serviço?
 *       REUTILIZA (nunca cria o segundo); senão CRIA nas
 *       {@link IncidentSource#ALERT_AUTOMATION} com ator nulo;</li>
 *   <li>vínculo: UPDATE condicional em {@code alerts.incident_id}
 *       ({@code WHERE incident IS NULL}) — a barreira de corrida que o
 *       Prompt 14 já construiu, reusada sem tocar em mais nada do alerta;</li>
 *   <li>timeline: linhas {@code CREATED} (se criou) e {@code ALERT_LINKED}
 *       (se vinculou), ator nulo, na MESMA transação — nunca existe
 *       incidente nascer sem o evento que explica por quê.</li>
 * </ol>
 *
 * <p><b>Por que transação PRÓPRIA e não REQUIRED:</b> o caminho comum chama
 * este método a partir do listener {@code AFTER_COMMIT} — naquele instante a
 * transação do nascimento do alerta JÁ comitou e, pelo contrato de
 * {@code TransactionSynchronization.afterCommit}, uma {@code REQUIRED}
 * nova "participaria" de uma transação morta (escrita perdida). A transação
 * própria (propagação {@code REQUIRES_NEW}, aplicada por um
 * {@link TransactionTemplate} deste componente) suspende o que estiver no
 * caminho e abre transação FRESCA com commit próprio — e, quando não há
 * transação nenhuma (a reconciliação roda sem), o resultado é o mesmo. É o
 * mesmo arranjo do {@code AlertLifecycleService}: escrita curta e própria,
 * nunca no rollback alheio.</p>
 *
 * <p><b>Por que {@code TransactionTemplate} e não {@code @Transactional}
 * neste método (revisão P1-2):</b> o método precisa tentar DE NOVO quando a
 * correlação perde a corrida pelo índice único da V12, e a segunda tentativa
 * precisa de uma transação FRESCA — em Postgres, um {@code INSERT} que
 * viola {@code uq_incidents_auto_open_per_service} aborta a transação
 * corrente ({@code current transaction is aborted}), então nada mais pode
 * rodar nela. Auto-invocação não passa pelo proxy do Spring (o
 * {@code @Transactional} do chamador não valeria para um {@code this.}),
 * logo o orquestrador fica sem anotação de transação e abre/fecha a sua
 * transação explicitamente: <b>UMA tentativa por transação</b>, rollback
 * completo antes de qualquer releitura.</p>
 *
 * <p><b>As duas corridas que este método absorve:</b></p>
 * <ul>
 *   <li><b>correlação (P1-2)</b> — dois ciclos simultâneos leem "nenhum
 *       incidente aberto" e os dois tentam criar: o índice parcial derruba o
 *       perdedor com {@link DataIntegrityViolationException}. Aqui isso vira
 *       UM retry numa transação nova — a releitura enxerga o incidente já
 *       comitado pelo vencedor e o perdedor só vincula (caminho
 *       "reutiliza"). Uma tentativa extra basta; se a violação repetir, a
 *       exceção sobe para o caller logar (nunca um loop);</li>
 *   <li><b>vínculo (P1-1)</b> — um vínculo humano vence entre a rechecagem e
 *       o {@code UPDATE}: {@code created && !linked} lança
 *       {@link LinkRaceLostException}, a transação inteira (criação +timeline)
 *       faz rollback e nenhum incidente órfão sobrevive ao commit.</li>
 * </ul>
 *
 * <p><b>Sem RBAC de usuário, sem TenantContext:</b> não existe humano
 * autorizando aqui — a permissão é a FLAG da regra (dono ligou no
 * create/update) mais os parents ativos, e o ownership vem das entidades
 * persistidas. Nada de token, papel ou contexto de requisição é lido.</p>
 *
 * <p><b>Nunca reabre nem resolve:</b> incidente {@code RESOLVED} é
 * terminal (só um NOVO nasce no próximo disparo) e alerta que já está
 * vinculado é no-op — a automação só associa/cria, jamais mexe no ciclo de
 * vida manual.</p>
 */
@Component
public class AlertIncidentAutomation {

    private static final Logger log = LoggerFactory.getLogger(AlertIncidentAutomation.class);

    private final AlertRepository alertRepository;
    private final IncidentService incidentService;
    private final AlertIncidentCorrelator correlator;
    private final AlertIncidentLinker linker;
    private final AlertIncidentEventPublisher timeline;

    /** Fronteira de transação da escrita: uma NOVA por tentativa, commit próprio. */
    private final TransactionTemplate writeTransaction;

    public AlertIncidentAutomation(
            AlertRepository alertRepository,
            IncidentService incidentService,
            AlertIncidentCorrelator correlator,
            AlertIncidentLinker linker,
            AlertIncidentEventPublisher timeline,
            PlatformTransactionManager transactionManager) {
        this.alertRepository = alertRepository;
        this.incidentService = incidentService;
        this.correlator = correlator;
        this.linker = linker;
        this.timeline = timeline;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Automatiza UM alerta — idempotente, atômico e isolado.
     *
     * <p>Falha aqui NUNCA derruba o nascimento do alerta nem o ciclo de
     * avaliação: o listener e o reconciler capturam e logam (o alerta segue
     * disparado; a reconciliação do ciclo seguinte tenta de novo). As duas
     * exceções que este método pode lançar são deliberadas:</p>
     *
     * <ul>
     *   <li>{@link DataIntegrityViolationException} da correlação — engolida
     *       AQUI dentro por UM retry em transação nova (ver Javadoc da
     *       classe); só sobe se a segunda tentativa repetir a violação;</li>
     *   <li>{@link LinkRaceLostException} do vínculo perdido — sobe de
     *       propósito para que o rollback aconteça, e é capturada pelo
     *       caller.</li>
     * </ul>
     *
     * @param serviceId serviço dono (proveniente do evento ou do hook)
     * @param alertId   alerta elegível a reconsiderar
     */
    public void automate(UUID serviceId, UUID alertId) {
        try {
            writeTransaction.executeWithoutResult(status -> attempt(serviceId, alertId));
        }
        catch (DataIntegrityViolationException raced) {
            // P1-2: perdemos a corrida pelo índice único. O template acima já
            // executou o ROLLBACK da tentativa inteira (a exceção só sobe
            // depois do rollback), então esta segunda chamada é uma
            // transação FRESCA: a releitura enxerga o incidente que o
            // vencedor já comitou e o caminho vira "reutiliza + vincula".
            log.debug("automation correlation raced serviceId={} alertId={} — retrying once",
                    serviceId, alertId);
            writeTransaction.executeWithoutResult(status -> attempt(serviceId, alertId));
        }
    }

    /**
     * UMA tentativa, inteira dentro de UMA transação (chamado pelo
     * {@link #writeTransaction} — nunca por auto-invocação, que escaparia do
     * proxy de transação).
     */
    private void attempt(UUID serviceId, UUID alertId) {
        Alert alert = alertRepository.findByServiceIdAndId(serviceId, alertId).orElse(null);
        if (alert == null) {
            log.debug("automation skipped: alert gone serviceId={} alertId={}", serviceId, alertId);
            return;
        }

        // rechecagem de elegibilidade (1–2): o chamador pode estar velho —
        // o estado VERDADEIRO é o desta transação
        if (alert.getStatus() != AlertStatus.FIRING) {
            return;
        }
        if (alert.getIncident() != null) {
            return; // já vinculado — idempotente, sem evento novo
        }
        AlertRule rule = alert.getAlertRule();
        if (rule == null || !rule.isAutoCreateIncident()) {
            return; // flag desligada depois do disparo (ou regra sumida)
        }
        Service service = alert.getService();
        if (!service.isActive() || !service.getProject().isActive()) {
            return; // pais inativos: nada nasce em estado congelado
        }

        Optional<Incident> open = correlator.findOpen(serviceId);
        boolean created = false;
        Incident incident = open.orElse(null);
        if (incident == null) {
            incident = incidentService.createAutomated(
                    service,
                    title(rule, alert),
                    description(alert),
                    alert.getSeverity(),
                    alert.getStartedAt());
            created = true;
            log.info("incident created by automation serviceId={} incidentId={} alertId={} ruleId={}",
                    serviceId, incident.getId(), alertId, rule.getId());
        }

        boolean linked = linker.link(alertId, incident);
        if (created && !linked) {
            // P1-1: um vínculo (humano ou de outra automação) venceu entre a
            // rechecagem e o UPDATE. A criação desta transação ficaria ORFÃ —
            // nasceu para este alerta, que aponta para outro incidente — e
            // a linha sobreviveria ao commit sem nenhum alerta associado.
            // Lançar dá ROLLBACK da criação E da timeline numa única
            // operação atômica (apagar seria a mesma escrita em dois passos,
            // com os eventos da timeline para desfazer à mão). O caller
            // captura e loga: o alerta segue vinculado ao incidente certo.
            throw new LinkRaceLostException(serviceId, alertId, incident.getId());
        }

        timeline.publish(incident, alertId, created, linked);
    }

    /**
     * Título canônico do incidente automático:
     * {@code [AUTO] {nome da regra} — SEVx} — o prefixo diz "nascido de
     * máquina" na própria listagem (o {@code source} confirma no detalhe),
     * o nome da regra dá o "porquê" sem consulta extra e a severidade é o
     * snapshot do alerta. Sanitizado e truncado no limite da coluna: o nome
     * da regra veio validado (≤120) e mesmo o pior caso cabe em 200.
     */
    private static String title(AlertRule rule, Alert alert) {
        return Incident.sanitizeText(
                "[AUTO] " + rule.getName() + " \u2014 " + alert.getSeverity(),
                Incident.MAX_TITLE_LENGTH);
    }

    /**
     * Descrição canônica: a MESMA mensagem do alerta
     * ({@code regra: detalhe}), sanitizada e no limite da coluna — é a
     * evidência de por que o incidente abriu, sem URL, token, query string
     * ou stack trace (o que o engine já sanitizou ao criar o alerta é o
     * que chega aqui). Vazia vira {@code null}, o "sem descrição" do resto
     * da API.
     */
    private static String description(Alert alert) {
        String description =
                Incident.sanitizeText(alert.getMessage(), Incident.MAX_DESCRIPTION_LENGTH);
        return description.isEmpty() ? null : description;
    }
}
