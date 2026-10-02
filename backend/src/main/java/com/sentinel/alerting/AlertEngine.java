package com.sentinel.alerting;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertResolutionReason;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertStatus;
import com.sentinel.project.alert.repository.AlertRepository;
import com.sentinel.project.alert.repository.AlertRuleRepository;
import com.sentinel.project.model.Service;
import com.sentinel.project.repository.ServiceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Ciclo de avaliação do Alert Engine (Prompt 13) — para UM serviço: cruza as
 * regras ATIVAS com o estado atual e aplica as transições de alerta.
 *
 * <p><b>Sem transação própria, de propósito:</b> cada escrita acontece no
 * {@link AlertLifecycleService} (método {@code @Transactional(REQUIRES_NEW)} —
 * transação curta e própria, independente de qualquer {@code @Transactional}
 * futuro num caller), então UMA
 * regra com falha derruba só a própria transação e o ciclo CONTINUA para as
 * demais. Um {@code @Transactional} aqui faria toda a avaliação cair junto
 * no primeiro erro (correção de revisão: isolamento por regra).</p>
 *
 * <p><b>Sequência do ciclo:</b></p>
 * <ol>
 *   <li>serviço inexistente → nada a fazer;</li>
 *   <li>serviço ou projeto inativo → resolve tudo com
 *       {@code PARENT_INACTIVE} e NÃO avalia (estado congelado);</li>
 *   <li>alerta aberto cuja regra saiu do catálogo → resolve com
 *       {@code RULE_DEACTIVATED};</li>
 *   <li>regra ativa: avalia → FIRING abre/toca, HEALTHY resolve
 *       {@code AUTO}, NO_DATA/ERROR não fazem nada.</li>
 * </ol>
 *
 * <p><b>Janela de alerta NUNCA resolve:</b> NO_DATA é "sem evidência", não
 * "voltou ao normal" — apagar um alerta por falta de dado esconderia exatamente
 * o problema que ele sinaliza.</p>
 *
 * <p><b>Corrida benigna:</b> dois cycles simultâneos para a MESMA regra podem
 * tentar criar o alerta ao mesmo tempo; o índice parcial UNIQUE da V10 deixa o
 * perdedor receber {@code DataIntegrityViolationException}, reconhecida AQUI
 * como corrida (não é erro: o alerta já existe e o próximo ciclo só toca).</p>
 *
 * <p><b>Escopo Monitor-level inativo:</b> a regra é avaliada mesmo com o
 * monitor desligado — histórico é histórico; o monitor não rodar não muda o
 * que a janela já observou (e sem checks novos a métrica converge para
 * NO_DATA sozinha).</p>
 */
@Component
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final ServiceRepository serviceRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final AlertRepository alertRepository;
    private final AlertLifecycleService lifecycle;
    private final RuleEvaluator evaluator;
    private final com.sentinel.monitoring.Clock clock;

    public AlertEngine(
            ServiceRepository serviceRepository,
            AlertRuleRepository alertRuleRepository,
            AlertRepository alertRepository,
            AlertLifecycleService lifecycle,
            RuleEvaluator evaluator,
            com.sentinel.monitoring.Clock clock) {
        this.serviceRepository = serviceRepository;
        this.alertRuleRepository = alertRuleRepository;
        this.alertRepository = alertRepository;
        this.lifecycle = lifecycle;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    /**
     * Avalia TODAS as regras ativas de um serviço e aplica as transições.
     *
     * <p>Idempotente e sem exceção propagada: qualquer falha vira log e o
     * ciclo seguinte decide (o engine roda a cada check do serviço).</p>
     *
     * @param serviceId serviço dono das regras (chegou do monitor já resolvido)
     */
    public void evaluateService(UUID serviceId) {
        Instant now = clock.instant();

        Service service = serviceRepository.findById(serviceId).orElse(null);
        if (service == null) {
            log.debug("alert evaluation skipped: service gone serviceId={}", serviceId);
            return;
        }

        // (2) estado congelado: desativação de pai já resolveu; aqui é a rede
        // de segurança para a corrida entre desativar e um check em voo.
        if (!service.isActive() || !service.getProject().isActive()) {
            lifecycle.resolveOpenForService(serviceId, AlertResolutionReason.PARENT_INACTIVE);
            return;
        }

        List<AlertRule> activeRules = alertRuleRepository.findByServiceIdAndActiveTrue(serviceId);
        Set<UUID> activeRuleIds = new HashSet<>();
        for (AlertRule rule : activeRules) {
            activeRuleIds.add(rule.getId());
        }

        // (3) regra desligada com alerta ainda aberto.
        for (UUID openRuleId : alertRepository.findOpenRuleIds(serviceId, AlertStatus.FIRING)) {
            if (!activeRuleIds.contains(openRuleId)) {
                lifecycle.resolveOpenForRule(openRuleId, AlertResolutionReason.RULE_DEACTIVATED);
            }
        }

        // (4) avaliação — isolamento POR REGRA: uma falha nunca corta as demais.
        AlertEvaluationContext ctx = new AlertEvaluationContext(
                serviceId, service.getStatus(), service.getStatusChangedAt(), now);
        for (AlertRule rule : activeRules) {
            apply(service, rule, ctx);
        }
    }

    private void apply(Service service, AlertRule rule, AlertEvaluationContext ctx) {
        EvaluationResult result;
        try {
            result = evaluator.evaluate(rule, ctx);
        }
        catch (RuntimeException ex) {
            // o avaliador já devolve ERROR; este catch é defesa em profundidade
            log.error("alert rule evaluation threw serviceId={} ruleId={}",
                    ctx.serviceId(), rule.getId(), ex);
            return;
        }

        try {
            switch (result.verdict()) {
                case FIRING -> lifecycle.fire(service, rule, result);
                case HEALTHY -> lifecycle.resolveOpenForRule(rule.getId(), AlertResolutionReason.AUTO);
                case NO_DATA, ERROR -> {
                    // nada a fazer: sem dado não dispara e não resolve
                }
            }
        }
        catch (DataIntegrityViolationException ex) {
            // corrida benigna: outro ciclo já criou o alerta desta regra
            log.debug("concurrent alert creation deduplicated serviceId={} ruleId={}",
                    ctx.serviceId(), rule.getId());
        }
        catch (RuntimeException ex) {
            log.error("alert lifecycle failed serviceId={} ruleId={}",
                    ctx.serviceId(), rule.getId(), ex);
        }
    }
}
