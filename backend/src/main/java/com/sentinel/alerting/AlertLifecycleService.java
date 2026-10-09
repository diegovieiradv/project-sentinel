package com.sentinel.alerting;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.monitoring.Clock;
import com.sentinel.project.alert.automation.event.AlertFiredEvent;
import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertResolutionReason;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.repository.AlertRepository;
import com.sentinel.project.model.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transições de estado do alerta (Prompt 13) — o ÚNICO código que cria,
 * toca ou resolve {@link Alert}. Todo método é
 * {@code @Transactional(REQUIRES_NEW)}: cada chamada é a SUA transação
 * PRÓPRIA, independente do caller — uma regra que falha no meio não derruba o
 * ciclo das demais e o invariante "escrita de alerta é transação curta
 * própria" sobrevive mesmo quando o caller tem (ou venha a ter)
 * {@code @Transactional} externo: a escrita commita sozinha e nunca participa
 * do rollback alheio (correção da revisão P2-2 — mesmo arranjo do
 * {@code OperationalStateEvaluator}).
 *
 * <p><b>Máquina de estados imposta aqui + no banco:</b> a aplicação só permite
 * FIRING → RESOLVED (via {@link Alert#resolve}); o CHECK
 * {@code alerts_lifecycle_check} e o índice parcial
 * {@code UNIQUE (alert_rule_id) WHERE status = 'FIRING'} da migration V10 são
 * a última linha de defesa. Reabrir é UM NOVO alerta — nunca um UPDATE de
 * status de volta para FIRING.</p>
 *
 * <p><b>Deduplicação em dois níveis:</b> o pré-check
 * ({@link AlertRepository#findFirstByAlertRuleIdAndStatus}) evita o insert no caso
 * comum; a corrida entre dois cycles é absorvida pelo índice parcial — o
 * perdedor recebe {@code DataIntegrityViolationException}, que o
 * {@link AlertEngine} reconhece como corrida benigna (o alerta já existe, o
 * próximo ciclo só faz touch).</p>
 *
 * <p><b>Resolução nunca toca a mensagem:</b> {@link Alert#resolve} grava
 * {@code resolvedAt} + {@code resolutionReason} e nada mais — a mensagem do
 * FIRING é a evidência de por que o alerta abriu e permanece intacta em
 * qualquer desfecho (correção da revisão).</p>
 *
 * <p><b>Motivos são explícitos:</b> AUTO (condição normalizou),
 * RULE_DEACTIVATED (regra desligada com alerta aberto), PARENT_INACTIVE
 * (serviço/projeto desligado — estado congelado, sem afirmação sobre saúde).
 * Os dois últimos são acionados NA desativação (rotas de activation) e, como
 * rede de segurança, de novo no ciclo do engine.</p>
 *
 * <p><b>Relógio próprio:</b> cada método lê {@code clock.instant()} — o
 * MESMO bean {@link Clock} do motor de monitoramento (congelado nos testes).
 * O engine e o lifecycle compartilhar o relógio mantém {@code startedAt},
 * {@code resolvedAt} e {@code lastEvaluatedAt} coerentes sem repassar
 * instantes em cada chamada.</p>
 *
 * <p><b>Evento de nascimento neutro (Prompt 15):</b> o ramo que CRIA um
 * alerta publica {@link AlertFiredEvent} (só ids — alerta + serviço) logo
 * após o {@code saveAndFlush}. É o único acoplamento para fora: "nasceu um
 * alerta", sem nomear quem queira reagir. Como a publicação acontece DENTRO
 * desta transação ({@code REQUIRES_NEW}), o consumidor roda no
 * {@code AFTER_COMMIT} — depois do alerta estar de fato commitado. O toque
 * de alerta existente NÃO publica (virou alerta uma vez só), e o
 * consumidor nunca consegue reverter nada daqui: falha dele é logada e o
 * próximo ciclo decide.</p>
 */
// Arranjo do conflito de nomes: entidade Service importada acima, estereótipo
// do Spring com FQN aqui (mesmo padrão de AlertRuleService).
@org.springframework.stereotype.Service
public class AlertLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AlertLifecycleService.class);

    private final AlertRepository alertRepository;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    public AlertLifecycleService(
            AlertRepository alertRepository,
            Clock clock,
            ApplicationEventPublisher applicationEventPublisher) {
        this.alertRepository = alertRepository;
        this.clock = clock;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    // --- FIRING: dedup + criação --------------------------------------------

    /**
     * Aplica um veredito {@code FIRING}: se já existe alerta aberto para a
     * regra, faz <b>touch</b> ({@code lastEvaluatedAt}/{@code triggerValue});
     * senão cria UM NOVO alerta com o snapshot da regra e a mensagem
     * {@code ruleName: detail}.
     *
     * <p>Pode lançar {@code DataIntegrityViolationException} sob corrida —
     * quem trata é o engine (a transação desta chamada só escreve o alerta,
     * então o rollback não afeta nada mais). O ramo de criação publica
     * {@link AlertFiredEvent} dentro desta transação (consumidor roda após
     * o commit); o ramo de toque não publica nada.</p>
     *
     * @param service serviço dono (mesmo da regra, já resolvido no engine)
     * @param rule    regra ATIVA que disparou
     * @param result  veredito FIRING com valor e detalhe do ciclo
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fire(Service service, AlertRule rule, EvaluationResult result) {
        Instant now = clock.instant();
        alertRepository.findFirstByAlertRuleIdAndStatus(rule.getId(), AlertStatus.FIRING)
                .ifPresentOrElse(
                        open -> {
                            open.touch(now, result.value());
                            alertRepository.save(open);
                            log.debug("alert still firing alertId={} ruleId={}",
                                    open.getId(), rule.getId());
                        },
                        () -> {
                            Alert alert = new Alert(
                                    service, rule, message(rule, result.detail()), result.value(), now);
                            alertRepository.saveAndFlush(alert);
                            // nascimento publica; o toque (ramo acima) não —
                            // "virou alerta" acontece uma vez só. O registro
                            // do ouvinte amarra a ESTA transação, então o
                            // consumidor roda após o commit do alerta.
                            applicationEventPublisher.publishEvent(
                                    new AlertFiredEvent(alert.getId(), service.getId()));
                            log.info("alert firing alertId={} ruleId={} serviceId={} severity={} value={}",
                                    alert.getId(), rule.getId(), service.getId(),
                                    alert.getSeverity(), result.value());
                        });
    }

    // --- RESOLVED: os três motivos ------------------------------------------

    /**
     * Resolve o alerta aberto de UMA regra (AUTO ou RULE_DEACTIVATED).
     * Idempotente: sem alerta aberto é no-op.
     *
     * @param ruleId regra dona do alerta
     * @param reason motivo do encerramento
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveOpenForRule(UUID ruleId, AlertResolutionReason reason) {
        Instant now = clock.instant();
        alertRepository.findFirstByAlertRuleIdAndStatus(ruleId, AlertStatus.FIRING)
                .ifPresent(alert -> resolve(alert, reason, now));
    }

    /**
     * Resolve EM MASSA todos os alertas abertos de um serviço
     * (PARENT_INACTIVE — serviço desativado). Idempotente.
     *
     * @param serviceId serviço dono dos alertas
     * @param reason    motivo do encerramento
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveOpenForService(UUID serviceId, AlertResolutionReason reason) {
        Instant now = clock.instant();
        for (Alert alert : alertRepository.findByServiceIdAndStatus(
                serviceId, AlertStatus.FIRING, Pageable.unpaged())) {
            resolve(alert, reason, now);
        }
    }

    /**
     * Resolve EM MASSA os alertas abertos de todos os serviços de UM projeto
     * (PARENT_INACTIVE na desativação do projeto). Idempotente.
     *
     * @param projectId projeto dono dos serviços
     * @param reason    motivo do encerramento
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveOpenForProject(UUID projectId, AlertResolutionReason reason) {
        Instant now = clock.instant();
        for (Alert alert : alertRepository.findByProjectIdAndStatus(
                projectId, AlertStatus.FIRING, Pageable.unpaged())) {
            resolve(alert, reason, now);
        }
    }

    private void resolve(Alert alert, AlertResolutionReason reason, Instant now) {
        alert.resolve(reason, now);
        alertRepository.save(alert);
        log.info("alert resolved alertId={} ruleId={} reason={} resolvedAt={}",
                alert.getId(),
                alert.getAlertRule() == null ? null : alert.getAlertRule().getId(),
                reason,
                now);
    }

    /** Mensagem do disparo: nome da regra + detalhe do ciclo (sanitizado no Alert). */
    private static String message(AlertRule rule, String detail) {
        return rule.getName() + ": " + detail;
    }
}
