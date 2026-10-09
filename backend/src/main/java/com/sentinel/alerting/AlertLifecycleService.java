package com.sentinel.alerting;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
 * <p><b>Resolução em massa em LOTES (critério #73):</b> os dois caminhos de
 * massa ({@link #resolveOpenForService} e {@link #resolveOpenForProject})
 * leem o conjunto aberto em páginas de {@value #RESOLVE_BATCH_SIZE} com ordem
 * determinística — nunca com {@code Pageable.unpaged()}. Cada iteração repete
 * a <b>primeira</b> página, resolve o lote e volta ao começo: como o lote
 * resolvido sai do filtro {@code FIRING}, o que resta sempre ocupa a página 0
 * da iteração seguinte. É isto que garante zero omissão — um offset crescente
 * sobre um conjunto que encolhe pularia linhas.</p>
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
     * Tamanho do lote da resolução em massa (critério #73): cada leitura traz
     * no máximo {@value #RESOLVE_BATCH_SIZE} alertas abertos — nenhum caminho
     * de produção carrega o conjunto inteiro de uma vez. Defensivo de
     * propósito: o volume real é limitado pelo índice parcial
     * {@code uq_alerts_open_rule} (≤1 alerta FIRING por regra), mas o CONTROLE
     * do tamanho da leitura mora na aplicação, não na casualidade do volume.
     */
    private static final int RESOLVE_BATCH_SIZE = 100;

    /**
     * Ordem DETERMINÍSTICA dos lotes: {@code startedAt ASC} com desempate em
     * {@code id ASC}. Sem ordem explícita o banco pode devolver sequências
     * diferentes entre leituras; com ela a progressão do loop é reprodutível
     * (e auditável), inclusive sob empate de {@code startedAt}.
     */
    private static final Sort RESOLVE_SORT =
            Sort.by(Sort.Order.asc("startedAt"), Sort.Order.asc("id"));

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
     * <p><b>Lotes de {@value #RESOLVE_BATCH_SIZE}, sempre pela página 0</b>
     * (critério #73): a leitura é paginada de verdade — nunca
     * {@code Pageable.unpaged()}. Ver {@link #resolveInBatches} para a
     * progressão e para as duas condições de parada.</p>
     *
     * @param serviceId serviço dono dos alertas
     * @param reason    motivo do encerramento
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveOpenForService(UUID serviceId, AlertResolutionReason reason) {
        Instant now = clock.instant();
        resolveInBatches(reason, now, pageable -> alertRepository.findByServiceIdAndStatus(
                serviceId, AlertStatus.FIRING, pageable));
    }

    /**
     * Resolve EM MASSA os alertas abertos de todos os serviços de UM projeto
     * (PARENT_INACTIVE na desativação do projeto). Idempotente.
     *
     * <p><b>Os mesmos lotes de {@value #RESOLVE_BATCH_SIZE} pela página 0</b>
     * (critério #73): mesmo esquema do caminho por serviço — cada leitura é
     * limitada no banco e o conjunto restante recomeça sempre na página 0.</p>
     *
     * @param projectId projeto dono dos serviços
     * @param reason    motivo do encerramento
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveOpenForProject(UUID projectId, AlertResolutionReason reason) {
        Instant now = clock.instant();
        resolveInBatches(reason, now, pageable -> alertRepository.findByProjectIdAndStatus(
                projectId, AlertStatus.FIRING, pageable));
    }

    /**
     * Núcleo compartilhado dos dois caminhos de massa: repete a <b>primeira</b>
     * página ({@code PageRequest.of(0, 100, startedAt ASC, id ASC)}) até ela
     * voltar vazia.
     *
     * <p><b>Por que página 0 e não offset crescente:</b> dentro do loop o
     * status muda de {@code FIRING} para {@code RESOLVED}, ou seja, os alertas
     * resolvidos SAEM do filtro da query a cada iteração. Com offset crescente
     * sobre um conjunto que encolhe, as páginas seguintes deslizam para fora
     * do que sobrou (250 alertas: página 0 resolve os 0–99; restam 150; offset
     * 100 cairia depois dos originais 100–199) e linhas seriam puladas.
     * Repetindo a página 0, o lote processado some do filtro e o que resta
     * desliza naturalmente para o começo — sem lacuna e sem repetição.</p>
     *
     * <p><b>Terminação comprovável, duas saídas:</b></p>
     * <ol>
     *   <li><b>página vazia</b> — cada iteração resolve ao menos UM alerta e
     *       faz {@code flush}; o alerta resolvido deixa de casar com
     *       {@code status = FIRING}, então o conjunto de entrada strictly
     *       decresce e, sendo finito, o loop termina;</li>
     *   <li><b>sem avanço</b> — se a primeira linha da página seguinte for a
     *       MESMA da anterior, o filtro não encolheu (não deveria acontecer:
     *       {@link Alert#resolve} sempre troca o status); em vez de girar para
     *       sempre, o loop registra o erro e encerra com o que já resolveu,
     *       deixando a rede de segurança do engine reagir no ciclo seguinte.</li>
     * </ol>
     *
     * <p>A saída 1 é observável no SQL (uma página vazia extra, dentro do
     * teto {@code ceil(n / 100) + 1} leituras); a saída 2 é um guard contra
     * regressão futura do próprio {@code resolve}.</p>
     *
     * @param reason    motivo do encerramento (compartilhado por todo o lote)
     * @param now       instante único de resolução (relógio congelado nos testes)
     * @param openBatch leitura dos alertas ainda {@code FIRING} para o escopo
     *                  (serviço OU projeto), já com o {@link Pageable} do lote
     */
    private void resolveInBatches(
            AlertResolutionReason reason,
            Instant now,
            Function<Pageable, Page<Alert>> openBatch) {
        UUID previousFirstId = null;
        while (true) {
            Page<Alert> batch =
                    openBatch.apply(PageRequest.of(0, RESOLVE_BATCH_SIZE, RESOLVE_SORT));
            if (batch.isEmpty()) {
                return;
            }
            UUID firstId = batch.getContent().get(0).getId();
            if (firstId.equals(previousFirstId)) {
                log.error("alert batch made no progress: filter did not shrink "
                                + "reason={} firstAlertId={} batch={}",
                        reason, firstId, batch.getContent().size());
                return;
            }
            previousFirstId = firstId;
            for (Alert alert : batch) {
                resolve(alert, reason, now);
            }
            // Descarrega as transições ANTES da próxima leitura: o WHERE
            // status = FIRING precisa enxergar o lote recém-resolvido, senão a
            // página 0 devolveria as mesmas linhas (e o guard acima dispararia).
            alertRepository.flush();
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
