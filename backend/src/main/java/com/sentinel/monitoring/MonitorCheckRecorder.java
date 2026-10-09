package com.sentinel.monitoring;

import java.util.UUID;

import com.sentinel.project.monitor.model.CheckFailureCategory;
import com.sentinel.project.monitor.model.CheckOutcome;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorCheck;
import com.sentinel.project.monitor.repository.MonitorCheckRepository;
import com.sentinel.project.monitor.repository.MonitorRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistência de UMA checagem (Prompt 10) — a ponte entre o resultado
 * transitório {@link MonitorExecutionResult} (Prompt 09, in-memory) e a
 * linha imutável {@code monitor_checks} (V8).
 *
 * <p><b>Transação própria e AUTÔNOMA:</b> o método é {@code @Transactional} e
 * é chamado pela {@link MonitorExecutionTask} fora de qualquer outra
 * transação, então nasce e faz COMMIT daqui — ANTES de a avaliação de estado
 * rodar (a task chama {@code record} e só então {@code evaluate}). Um commit
 * aqui é durável mesmo que a avaliação seguinte falhe: o histórico não é
 * refém do estado agregado.</p>
 *
 * <p><b>Tradução 1:1 do desfecho</b> (única refinação da persistência):</p>
 * <ul>
 *   <li>{@code MonitorOutcome.SUCCESS} com status <b>3xx</b> →
 *       {@link CheckOutcome#DEGRADED_SIGNAL} — a política de redirect do
 *       Prompt 09 ({@code Redirect.NEVER}) entrega o 3xx como resposta, e um
 *       alvo que só redireciona não é um sucesso pleno de aplicação;</li>
 *   <li>{@code SUCCESS} 2xx → {@link CheckOutcome#SUCCESS};</li>
 *   <li>{@code TARGET_FAILURE} → {@link CheckOutcome#TARGET_FAILURE} com a
 *       MESMA categoria (espelho 1:1 {@code FailureCategory →
 *       CheckFailureCategory});</li>
 *   <li>{@code SENTINEL_INTERNAL_FAILURE} → o mesmo, com a categoria interna
 *       correspondente.</li>
 * </ul>
 * {@code DEGRADED_SIGNAL} e {@code SUCCESS} persistem com
 * {@code failureCategory = null} (não são falhas); toda linha de falha tem
 * categoria — o CHECK da V8 é a última barreira.
 *
 * <p><b>Mensagem:</b> nunca é corpo, header, cookie, token ou stack trace —
 * o executor (Prompt 09) só produz mensagens de domínio fixas, e este
 * recorder ainda remove controles não imprimíveis e trunca em
 * {@value #MAX_MESSAGE_LENGTH} caracteres. Qualquer string que viole o
 * tamanho é cortada, nunca rejeitada (uma checagem não pode perder histórico
 * por causa de uma mensagem longa).</p>
 *
 * <p><b>Sem escrita de estado de serviço aqui:</b> gravar histórico e
 * recalcular {@code Service.status} são DUAS etapas separadas — ver
 * {@link OperationalStateEvaluator}.</p>
 */
@Component
public class MonitorCheckRecorder {

    static final int MAX_MESSAGE_LENGTH = 500;

    private static final Logger log = LoggerFactory.getLogger(MonitorCheckRecorder.class);

    private final MonitorCheckRepository monitorCheckRepository;
    private final MonitorRepository monitorRepository;

    public MonitorCheckRecorder(
            MonitorCheckRepository monitorCheckRepository, MonitorRepository monitorRepository) {
        this.monitorCheckRepository = monitorCheckRepository;
        this.monitorRepository = monitorRepository;
    }

    /**
     * Persiste o resultado de uma execução como linha imutável de histórico.
     *
     * <p>O monitor é referenciado por {@code getReferenceById} (proxy sem
     * SELECT extra — a task já carrega o monitor); se o monitor não existir
     * mais, a violação de FK estoura no flush e a exceção sobe para a task,
     * que a captura sem derrubar o pipeline.</p>
     *
     * @param resultado resultado transitório da execução
     * @return a linha persistida
     */
    @Transactional
    public MonitorCheck record(MonitorExecutionResult resultado) {
        UUID monitorId = resultado.monitorId();
        if (monitorId == null) {
            // Só acontece com bug no executor (resultado sem monitor) — a task
            // captura e conta como erro interno; nada é gravado.
            throw new IllegalArgumentException("cannot persist a check without a monitor id");
        }

        CheckOutcome outcome = toCheckOutcome(resultado);
        Monitor monitor = monitorRepository.getReferenceById(monitorId);
        MonitorCheck check = new MonitorCheck(
                monitor,
                resultado.startedAt(),
                resultado.finishedAt(),
                resultado.durationMillis(),
                resultado.httpStatus().orElse(null),
                outcome,
                toFailureCategory(resultado, outcome),
                sanitizeMessage(resultado.message()));

        MonitorCheck saved = monitorCheckRepository.saveAndFlush(check);
        log.debug(
                "monitor check persisted checkId={} monitorId={} outcome={} httpStatus={}",
                saved.getId(),
                monitorId,
                saved.getOutcome(),
                saved.getHttpStatus());
        return saved;
    }

    // --- classificação ------------------------------------------------------

    /** 3xx é o ÚNICO refinamento da persistência sobre o desfecho do motor. */
    static CheckOutcome toCheckOutcome(MonitorExecutionResult resultado) {
        return switch (resultado.outcome()) {
            case SUCCESS -> isRedirect(resultado) ? CheckOutcome.DEGRADED_SIGNAL : CheckOutcome.SUCCESS;
            case TARGET_FAILURE -> CheckOutcome.TARGET_FAILURE;
            case SENTINEL_INTERNAL_FAILURE -> CheckOutcome.SENTINEL_INTERNAL_FAILURE;
        };
    }

    /** Sucesso do motor = 2xx/3xx; um 3xx (redirect não seguido) vira degradado. */
    private static boolean isRedirect(MonitorExecutionResult resultado) {
        return resultado.httpStatus()
                .filter(status -> status >= 300 && status < 400)
                .isPresent();
    }

    /**
     * Espelho 1:1 das sete categorias (os nomes são idênticos de propósito —
     * divergência seria bug de tradução). Sucesso e degradado não têm
     * categoria.
     *
     * @param resultado resultado da execução
     * @param outcome   desfecho JÁ traduzido (evita traduzir duas vezes)
     */
    static CheckFailureCategory toFailureCategory(MonitorExecutionResult resultado, CheckOutcome outcome) {
        if (outcome == CheckOutcome.SUCCESS || outcome == CheckOutcome.DEGRADED_SIGNAL) {
            return null;
        }
        FailureCategory category = resultado.failureCategory().orElseThrow(
                () -> new IllegalStateException("failed check without a failure category"));
        return switch (category) {
            case DNS -> CheckFailureCategory.DNS;
            case CONNECT -> CheckFailureCategory.CONNECT;
            case TIMEOUT -> CheckFailureCategory.TIMEOUT;
            case TLS -> CheckFailureCategory.TLS;
            case HTTP_ERROR -> CheckFailureCategory.HTTP_ERROR;
            case SSRF_REVALIDATION -> CheckFailureCategory.SSRF_REVALIDATION;
            case INTERNAL -> CheckFailureCategory.INTERNAL;
        };
    }

    // --- higiene da mensagem ------------------------------------------------

    /**
     * Última barreira da mensagem persistida: sem caracteres de controle
     * (quebraria a leitura do histórico), trim e corte em
     * {@value #MAX_MESSAGE_LENGTH} caracteres. Um {@code null} vira string
     * vazia (a coluna é NOT NULL e o executor sempre envia domínio).
     *
     * <p><b>Conjunto exato, sem ambiguidade:</b> todo
     * {@link Character#isISOControl(char)} vira espaço, menos {@code \t}.
     * Em regex isso é {@code [\p{Cc} && [^\t]]} — C0 ({@code \u0000}–
     * {@code \u001F}), {@code \u007F} (DEL) e C1 ({@code \u0080}–
     * {@code \u009F}) são removidos; tabulação não, porque é a única forma
     * legível de alinhar a mensagem na UI. É também a defesa de
     * <em>log/terminal injection</em>: {@code ESC} ({@code \u001B}) é ISO
     * control, então a sequência ANSI que formata/recolhe terminal nunca
     * chega ao histórico. A substituição é por <b>espaço</b> (não por
     * remoção contígua): a palavra não se funde com a vizinha e o offset
     * caindo no meio da mensagem original permanece auditável.</p>
     *
     * <p>Não é sanitização de HTML/XSS (esta coluna nunca é renderizada
     * como markup) nem colapso de espaços múltiplos: a mensagem é texto de
     * domínio, preservado como escrito.</p>
     */
    static String sanitizeMessage(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            builder.append(Character.isISOControl(ch) && ch != '\t' ? ' ' : ch);
        }
        String cleaned = builder.toString().trim();
        if (cleaned.length() <= MAX_MESSAGE_LENGTH) {
            return cleaned;
        }
        int cut = MAX_MESSAGE_LENGTH;
        // nunca corta no meio de um par substituto (senão o INSERT estoura)
        if (Character.isHighSurrogate(cleaned.charAt(cut - 1))) {
            cut--;
        }
        return cleaned.substring(0, cut);
    }
}
