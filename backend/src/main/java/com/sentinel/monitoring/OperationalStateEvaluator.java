package com.sentinel.monitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sentinel.project.model.Service;
import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorCheck;
import com.sentinel.project.monitor.repository.MonitorCheckRepository;
import com.sentinel.project.monitor.repository.MonitorRepository;
import com.sentinel.project.repository.ServiceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Avaliação do estado operacional do SERVIÇO (Prompt 10) — a única escrita
 * de {@code Service.status} do sistema.
 *
 * <p><b>Janela de avaliação:</b> os últimos {@value #EVALUATION_WINDOW}
 * checks RELEVANTES de <b>CADA monitor ativo</b> do serviço (a query exclui os
 * {@code SENTINEL_INTERNAL_FAILURE}: falha do Sentinel não é evidência sobre
 * o alvo — se um monitor só tem checks internos, a dele é janela vazia e
 * não contribui com evidência). Cada monitor é avaliado <b>na sua própria
 * janela</b>; o conjunto de janelas é limitado por
 * (monitores ativos × {@value #EVALUATION_WINDOW}) — nunca é varrida a
 * história inteira.</p>
 *
 * <p><b>Por que olhar TODOS os monitores ativos do serviço:</b> o estado é
 * do SERVIÇO, não de um monitor. Avaliar só o monitor que acabou de rodar
 * faria o status "flapper" no último-escritor: com dois monitores (um bom,
 * um ruim) o status alternaria conforme qual terminasse por último. Recalcular
 * o serviço inteiro a cada check torna o resultado ESTÁVEL e independente da
 * ordem de chegada — e é por isso que este bean precisa da query de monitores
 * ativos por serviço.</p>
 *
 * <p><b>Tabela de agregação (§18 do plano) — a primeira linha aplicável
 * vence:</b></p>
 * <ol>
 *   <li>sem nenhuma evidência em <b>nenhum</b> monitor (janelas vazias, ou
 *       só checks internos) → {@link ServiceStatus#UNKNOWN};</li>
 *   <li>todo mundo saudável → {@link ServiceStatus#OPERATIONAL};</li>
 *   <li><b>TODOS</b> os monitores ativos com evidência (janela não vazia)
 *       <b>E todos</b> em condição de indisponibilidade (ver abaixo) →
 *       {@link ServiceStatus#UNAVAILABLE};</li>
 *   <li>qualquer outro caso com falha ou degradação →
 *       {@link ServiceStatus#DEGRADED}.</li>
 * </ol>
 *
 * <p><b>Indisponibilidade é decidida POR MONITOR, nunca sobre a janela
 * agregada</b> (correção de revisão): um monitor está em condição
 * {@code UNAVAILABLE_LOCAL} somente quando seus <b>próprios</b>
 * {@value #EVALUATION_WINDOW} checks relevantes são todos
 * {@code TARGET_FAILURE} <b>sem resposta</b> consecutivos. Concatenar as
 * janelas de N monitores para aplicar o threshold de {@value #UNAVAILABLE_THRESHOLD}
 * daria falso-positivo (A=[U,U] + B=[U] somaria 3 "U" e declararia o serviço
 * fora mesmo que nenhum monitor tenha 3 falhas seguidas). Assim:</p>
 * <ul>
 *   <li>A=[U,U], B=[U] (nenhum com 3 seguidos) → {@code DEGRADED};</li>
 *   <li>A=[U,U,U], B=[H] (parte do serviço responde) → {@code DEGRADED};</li>
 *   <li>A=[U,U,U], B sem evidência → {@code DEGRADED} (nem todos com
 *       evidência);</li>
 *   <li>A=[U,U,U] + B=[U,U,U] (todos com evidência, todos indisponíveis) →
 *       {@code UNAVAILABLE};</li>
 *   <li>monitor único [U,U,U] → {@code UNAVAILABLE}.</li>
 * </ul>
 *
 * <p><b>Evidência de cada check</b> ({@link Evidence}), derivada do desfecho
 * persistido:</p>
 * <ul>
 *   <li>{@code SUCCESS} (2xx) → saudável;</li>
 *   <li>{@code DEGRADED_SIGNAL} (3xx) → degradado;</li>
 *   <li>{@code TARGET_FAILURE} <b>sem</b> status HTTP (DNS/CONNECT/TIMEOUT/TLS
 *       — ninguém respondeu) → inacessível local;</li>
 *   <li>{@code TARGET_FAILURE} <b>com</b> status HTTP (4xx/5xx — o serviço
 *       respondeu com erro) → falha respondida;</li>
 *   <li>{@code SENTINEL_INTERNAL_FAILURE} → sem evidência (nunca chega da
 *       query: a janela já a exclui).</li>
 * </ul>
 *
 * <p>A distinção central: <b>sem resposta por {@value #UNAVAILABLE_THRESHOLD}
 * checagens no MESMO monitor, com todos os monitores também indisponíveis, é
 * UNAVAILABLE; resposta com erro é DEGRADED</b> (o serviço existe e responde,
 * mas não funciona).</p>
 *
 * <p><b>Transação própria ({@code REQUIRES_NEW}) + lock pessimista:</b> a
 * task chama este método fora de transação; o proxy Spring abre a transação
 * AQUI. A ordem interna importa: (1) descobre o serviço pelo monitor, (2) lock
 * da linha do serviço ({@code SELECT ... FOR UPDATE}), (3) leitura das janelas
 * <b>sob lock</b>, (4) cálculo, (5) escrita <b>só se mudou</b>. Ler depois do
 * lock serializa avaliações concorrentes de monitores do mesmo serviço —
 * nenhuma escreve estado calculado a partir de janela velha — e o "só se
 * mudou" evita gravação (e bump de {@code updatedAt}) quando o estado não
 * mudou.</p>
 *
 * <p><b>Recuperação é natural pela janela:</b> não existe máquina de estados
 * nem trava de "só volta quando melhorar" — UNAVAILABLE → [200, 200, 200]
 * desliza para DEGRADED e depois OPERATIONAL sozinho, porque a janela é
 * rolante e este cálculo é puro (sem memória além do histórico).</p>
 */
@Component
public class OperationalStateEvaluator {

    /** Tamanho da janela por monitor: 3 checks mais recentes relevantes. */
    public static final int EVALUATION_WINDOW = 3;

    /**
     * Evidências mínimas de inacessibilidade <b>no mesmo monitor</b> para
     * que ELE esteja em condição de indisponibilidade (o serviço só vira
     * {@code UNAVAILABLE} quando TODOS os monitores ativos estão assim).
     */
    public static final int UNAVAILABLE_THRESHOLD = 3;

    /** Evidência que um check fornece sobre o SERVIÇO (linhas da tabela §18). */
    enum Evidence {
        /** Respondeu 2xx. */
        HEALTHY,
        /** Respondeu 3xx (redirect não seguido). */
        DEGRADED,
        /** Respondeu 4xx/5xx — existe e responde, mas com erro. */
        FAILING,
        /** Ninguém respondeu (DNS/CONNECT/TIMEOUT/TLS). */
        UNAVAILABLE_LOCAL,
        /** Falha interna do Sentinel — não é evidência do alvo. */
        NO_EVIDENCE
    }

    private static final Logger log = LoggerFactory.getLogger(OperationalStateEvaluator.class);

    private final MonitorCheckRepository monitorCheckRepository;
    private final MonitorRepository monitorRepository;
    private final ServiceRepository serviceRepository;
    private final Clock clock;

    public OperationalStateEvaluator(
            MonitorCheckRepository monitorCheckRepository,
            MonitorRepository monitorRepository,
            ServiceRepository serviceRepository,
            Clock clock) {
        this.monitorCheckRepository = monitorCheckRepository;
        this.monitorRepository = monitorRepository;
        this.serviceRepository = serviceRepository;
        this.clock = clock;
    }

    /**
     * Recalcula o estado do serviço dono de {@code monitorId}.
     *
     * @param monitorId monitor cujo check acabou de ser persistido
     * @return o estado calculado, ou {@code null} se monitor/serviço não
     *         existem mais (nada é escrito nesse caso)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ServiceStatus evaluate(UUID monitorId) {
        Monitor monitor = monitorRepository.findById(monitorId).orElse(null);
        if (monitor == null) {
            log.debug("service status not evaluated: monitor gone monitorId={}", monitorId);
            return null;
        }
        UUID serviceId = monitor.getService().getId();

        // (1) lock primeiro: avaliações concorrentes do mesmo serviço serializam.
        Service service = serviceRepository.findByIdWithLock(serviceId).orElse(null);
        if (service == null) {
            log.debug("service status not evaluated: service gone serviceId={}", serviceId);
            return null;
        }

        // (2)+(3) leitura das janelas já sob lock + cálculo puro.
        List<List<MonitorCheck>> windows = loadWindows(serviceId);
        ServiceStatus computed = classify(windows);

        // (4) escrita só se mudou.
        if (service.getStatus() != computed) {
            ServiceStatus previous = service.getStatus();
            // transição + eixo temporal (status_changed_at, V10): a regra
            // SERVICE_STATUS do Alert Engine compara este instante com a janela
            service.setStatusInternal(computed, clock.instant());
            serviceRepository.saveAndFlush(service);
            log.info(
                    "service status updated serviceId={} from={} to={} evidenceChecks={}",
                    serviceId,
                    previous,
                    computed,
                    windows.stream().mapToInt(List::size).sum());
        }
        return computed;
    }

    /**
     * Uma janela POR monitor ativo (até {@value #EVALUATION_WINDOW} checks
     * relevantes cada, mais recente primeiro) — a agregação por monitor
     * acontece em {@link #classify}, nunca na leitura.
     */
    private List<List<MonitorCheck>> loadWindows(UUID serviceId) {
        List<UUID> activeMonitorIds = monitorRepository.findActiveIdsByServiceId(serviceId);
        Pageable windowPage = PageRequest.of(0, EVALUATION_WINDOW);
        List<List<MonitorCheck>> windows = new ArrayList<>();
        for (UUID activeMonitorId : activeMonitorIds) {
            windows.add(monitorCheckRepository
                    .findRelevantByMonitorIdOrderByStartedAtDesc(activeMonitorId, windowPage)
                    .getContent());
        }
        return windows;
    }

    /**
     * Tabela de agregação pura (sem I/O) — exposta para teste de unidade.
     * A ordem das linhas É a ordem de avaliação (primeira aplicável vence).
     *
     * <p>A indisponibilidade é decidida <b>por monitor</b>: a linha 3 exige
     * que <b>todos</b> os monitores ativos tenham evidência e que
     * <b>cada</b> um esteja com os próprios
     * {@value #UNAVAILABLE_THRESHOLD} checks relevantes inacessíveis — ver a
     * documentação da classe para os casos que ficam em {@code DEGRADED}.</p>
     *
     * @param windows uma janela por monitor ativo (qualquer ordem, qualquer
     *                tamanho até {@value #EVALUATION_WINDOW})
     * @return o estado do serviço
     */
    static ServiceStatus classify(List<List<MonitorCheck>> windows) {
        List<Evidence> evidence = windows.stream()
                .flatMap(List::stream)
                .map(OperationalStateEvaluator::evidenceOf)
                .toList();

        // 1. sem NENHUMA evidência em NENHUM monitor: janelas vazias ou só
        //    checks internos
        if (evidence.isEmpty() || evidence.stream().allMatch(v -> v == Evidence.NO_EVIDENCE)) {
            return ServiceStatus.UNKNOWN;
        }
        // 2. todo mundo saudável
        if (evidence.stream().allMatch(v -> v == Evidence.HEALTHY)) {
            return ServiceStatus.OPERATIONAL;
        }
        // 3. TODOS os monitores com evidência E todos indisponíveis — a
        //    comparação é por janela de monitor, nunca sobre a concatenação
        boolean everyMonitorHasEvidence = windows.stream().allMatch(w -> !w.isEmpty());
        if (everyMonitorHasEvidence
                && windows.stream().allMatch(OperationalStateEvaluator::isUnavailableWindow)) {
            return ServiceStatus.UNAVAILABLE;
        }
        // 4. qualquer outro caso com falha ou degradação
        return ServiceStatus.DEGRADED;
    }

    /**
     * Condição de indisponibilidade de UM monitor: os próprios
     * {@value #EVALUATION_WINDOW} checks relevantes dele são todos
     * {@code TARGET_FAILURE} sem resposta (ninguém respondeu — falha COM
     * status HTTP é "respondeu com erro" e nunca conta aqui).
     */
    private static boolean isUnavailableWindow(List<MonitorCheck> window) {
        if (window.size() < UNAVAILABLE_THRESHOLD) {
            return false;
        }
        return window.stream()
                .allMatch(check -> evidenceOf(check) == Evidence.UNAVAILABLE_LOCAL);
    }

    /** Traduz o desfecho persistido na evidência de saúde correspondente. */
    static Evidence evidenceOf(MonitorCheck check) {
        return switch (check.getOutcome()) {
            case SUCCESS -> Evidence.HEALTHY;
            case DEGRADED_SIGNAL -> Evidence.DEGRADED;
            case TARGET_FAILURE -> check.getHttpStatus() == null
                    ? Evidence.UNAVAILABLE_LOCAL
                    : Evidence.FAILING;
            case SENTINEL_INTERNAL_FAILURE -> Evidence.NO_EVIDENCE;
        };
    }
}
