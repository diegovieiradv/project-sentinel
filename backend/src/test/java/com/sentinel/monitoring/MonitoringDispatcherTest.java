package com.sentinel.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.sentinel.project.model.ServiceStatus;
import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.model.MonitorCheck;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Despacho do {@link MonitoringDispatcher} (Prompt 09) — fronteira entre o
 * tick curto e o pool de checagens.
 *
 * <p>Testes de unidade puros (sem Spring, sem banco, sem rede): o transporte
 * é um gravador que <b>nunca conecta</b>, o relógio é fixo e o executor real
 * {@link HttpMonitorExecutor} roda com validador poroso — aqui interessam
 * in-flight, saturação, contadores, isolamento de exceção e isolamento
 * <b>entre monitores</b> (um lento não segura o outro), não a
 * classificação de falha (essa vive no {@code HttpMonitorExecutorTest}).</p>
 */
class MonitoringDispatcherTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    /** Alvo do Monitor A — IP público canônico (sem DNS), caminho "slow". */
    private static final String SLOW_URL = "https://93.184.216.34/slow";

    /** Alvo do Monitor B — mesmo IP canônico, caminho distinto ("fast"). */
    private static final String FAST_URL = "https://93.184.216.34/fast";

    private final RecordingTransport transport = new RecordingTransport();
    private MonitoringDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            transport.unblock();
            // espera as tasks pararem antes de fechar o pool daquele teste
            dispatcher.awaitIdle(WAIT);
            dispatcher.shutdown();
        }
    }

    // --- ACCEPTED -----------------------------------------------------------

    @Test
    void acceptedDispatchRunsTheCheckAndCountsIt() {
        dispatcher = dispatcher(4, 8);
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);

        MonitoringDispatcher.DispatchOutcome outcome = dispatcher.tryDispatch(monitor);

        assertThat(outcome).isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();
        assertThat(transport.requests).hasSize(1);
        assertThat(transport.requests.get(0).uri().toString())
                .isEqualTo(MonitorFixtures.PUBLIC_URL);
        assertThat(dispatcher.dispatchedCount()).isEqualTo(1);
        assertThat(dispatcher.saturatedCount()).isZero();
        assertThat(dispatcher.internalErrorCount()).isZero();
        assertThat(dispatcher.inFlightCount()).isZero();
    }

    // --- IN_FLIGHT ----------------------------------------------------------

    @Test
    void monitorAlreadyRunningIsNeverDispatchedInParallel() {
        dispatcher = dispatcher(4, 8);
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        transport.block();

        assertThat(dispatcher.tryDispatch(monitor))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        transport.awaitEntered();

        // segundo despacho enquanto a primeira checagem ainda roda
        assertThat(dispatcher.tryDispatch(monitor))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.IN_FLIGHT);
        assertThat(transport.requests).hasSize(1);
        assertThat(dispatcher.dispatchedCount()).isEqualTo(1);
        assertThat(dispatcher.inFlightCount()).isEqualTo(1);

        // terminou → o monitor volta a poder ser despachado
        transport.unblock();
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();
        assertThat(dispatcher.tryDispatch(monitor))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();
        assertThat(transport.requests).hasSize(2);
    }

    // --- SATURATED ----------------------------------------------------------

    @Test
    void fullQueueReturnsSaturatedWithoutInternalErrorsAndKeepsMonitorFree() {
        // 1 worker + fila 0 (SynchronousQueue): com a worker ocupada, a
        // segunda tentativa é recusada imediatamente.
        dispatcher = dispatcher(1, 0);
        Monitor first = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        Monitor second = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        transport.block();

        assertThat(dispatcher.tryDispatch(first))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        transport.awaitEntered();

        assertThat(dispatcher.tryDispatch(second))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.SATURATED);
        assertThat(transport.requests).hasSize(1);
        assertThat(dispatcher.saturatedCount()).isEqualTo(1);
        assertThat(dispatcher.dispatchedCount()).isEqualTo(1);
        // saturação NUNCA é erro interno nem falha de alvo
        assertThat(dispatcher.internalErrorCount()).isZero();
        // o monitor saturado não ficou "presa" no in-flight
        assertThat(dispatcher.inFlightCount()).isEqualTo(1);

        // com vaga livre, o mesmo monitor é aceito
        transport.unblock();
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();
        assertThat(dispatcher.tryDispatch(second))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();
        assertThat(transport.requests).hasSize(2);
    }

    // --- isolamento de exceção ---------------------------------------------

    @Test
    void exceptionEscapingTheExecutorIsCountedAndTheMonitorIsReleased() {
        HttpMonitorExecutor exploding = new HttpMonitorExecutor(
                url -> { },
                transport,
                () -> Instant.parse("2026-03-01T00:00:00Z")) {
            @Override
            public MonitorExecutionResult execute(Monitor monitor) {
                throw new IllegalStateException("executor bug");
            }
        };
        MonitoringProperties properties = properties(4, 8);
        dispatcher = new MonitoringDispatcher(properties, exploding, recorder(), evaluator());
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);

        assertThat(dispatcher.tryDispatch(monitor))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        // a task morre no catch, mas o pool NÃO: awaitIdle completa
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();

        assertThat(dispatcher.internalErrorCount()).isEqualTo(1);
        assertThat(dispatcher.saturatedCount()).isZero();
        assertThat(dispatcher.inFlightCount()).isZero();

        // o monitor ficou livre para nova tentativa (release em finally)
        assertThat(dispatcher.tryDispatch(monitor))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();
        assertThat(dispatcher.internalErrorCount()).isEqualTo(2);
    }

    // --- pipeline persistência → avaliação (Prompt 10) ---------------------

    /**
     * Fronteira entre as DUAS etapas do Prompt 10: se a persistência do
     * histórico falhar, a avaliação de estado é <b>PULADA de propósito</b>
     * (nada se calcula sobre histórico que não existe) e a task CONTINUA —
     * in-flight liberado, erro interno contado, monitor despachável de novo.
     */
    @Test
    void recorderFailureSkipsEvaluationAndKeepsTheMonitorUsable() {
        RecordingCheckRecorder recorder = recorder();
        recorder.failing = true;
        RecordingEvaluator evaluator = evaluator();
        dispatcher = new MonitoringDispatcher(
                properties(4, 8),
                new HttpMonitorExecutor(
                        url -> { }, transport, () -> Instant.parse("2026-03-01T00:00:00Z")),
                recorder,
                evaluator);
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);

        assertThat(dispatcher.tryDispatch(monitor))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();

        assertThat(recorder.recorded).isEmpty();
        assertThat(evaluator.evaluated)
                .as("sem histórico não há o que avaliar")
                .isEmpty();
        assertThat(dispatcher.internalErrorCount()).isEqualTo(1);
        assertThat(dispatcher.inFlightCount()).isZero();

        // release em finally: o monitor segue despachável
        assertThat(dispatcher.tryDispatch(monitor))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();
        assertThat(dispatcher.dispatchedCount()).isEqualTo(2);
    }

    /**
     * Recíproco: o histórico é DURÁVEL mesmo quando a avaliação falha — a
     * task não propaga a exceção, o pool não morre e o estado é recalculado
     * no próximo check.
     */
    @Test
    void evaluatorFailureKeepsPersistedHistoryAndReleasesTheMonitor() {
        RecordingCheckRecorder recorder = recorder();
        RecordingEvaluator evaluator = evaluator();
        evaluator.failing = true;
        dispatcher = new MonitoringDispatcher(
                properties(4, 8),
                new HttpMonitorExecutor(
                        url -> { }, transport, () -> Instant.parse("2026-03-01T00:00:00Z")),
                recorder,
                evaluator);
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);

        assertThat(dispatcher.tryDispatch(monitor))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();

        assertThat(recorder.recorded).hasSize(1);
        assertThat(evaluator.evaluated).containsExactly(monitor.getId());
        assertThat(dispatcher.internalErrorCount()).isEqualTo(1);
        assertThat(dispatcher.inFlightCount()).isZero();
    }

    // --- awaitIdle ----------------------------------------------------------

    @Test
    void awaitIdleTimesOutWhileWorkRemainsAndCompletesAfterRelease() {
        dispatcher = dispatcher(4, 8);
        Monitor monitor = MonitorFixtures.monitor(MonitorFixtures.PUBLIC_URL);
        transport.block();

        dispatcher.tryDispatch(monitor);
        transport.awaitEntered();

        assertThat(dispatcher.awaitIdle(Duration.ofMillis(150))).isFalse();

        transport.unblock();
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();
        assertThat(dispatcher.inFlightCount()).isZero();
    }

    // --- isolamento entre monitores (requisito nº 12) ----------------------

    /**
     * Requisito nº 12 do Prompt 09: <b>Monitor A não bloqueia Monitor B</b>.
     *
     * <p>O transporte trava <b>somente</b> a URL do Monitor A (latch
     * seletivo) — A fica preso no meio da checagem enquanto B, despachado
     * em seguida, é executado em outra thread do pool, conclui com sucesso
     * e sai do in-flight. Só então A é liberado e também conclui, zerando o
     * in-flight dos dois. Determinístico: sem rede, sem sleeps longos — as
     * esperas são latches/condições com teto ({@link #WAIT}).</p>
     */
    @Test
    void slowMonitorDoesNotBlockAnotherMonitorFromDispatchAndCompletion() {
        // gravador de resultados: prova que a checagem CONCLUIU (e com qual
        // desfecho), não apenas que o despacho foi aceito.
        Map<UUID, MonitorExecutionResult> results = new ConcurrentHashMap<>();
        HttpMonitorExecutor recording = new HttpMonitorExecutor(
                url -> { }, transport, () -> Instant.parse("2026-03-01T00:00:00Z")) {
            @Override
            public MonitorExecutionResult execute(Monitor monitor) {
                MonitorExecutionResult result = super.execute(monitor);
                results.put(monitor.getId(), result);
                return result;
            }
        };
        dispatcher = new MonitoringDispatcher(properties(4, 8), recording, recorder(), evaluator());

        Monitor slow = MonitorFixtures.monitor(SLOW_URL);
        Monitor fast = MonitorFixtures.monitor(FAST_URL);
        transport.block(SLOW_URL);

        // Monitor A entra na checagem e fica preso no transporte
        assertThat(dispatcher.tryDispatch(slow))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        transport.awaitEntered();
        assertThat(dispatcher.inFlightCount()).isEqualTo(1);

        // Monitor B: aceito (nunca IN_FLIGHT nem SATURATED) e conclui com
        // SUCESSO enquanto A ainda está em andamento
        assertThat(dispatcher.tryDispatch(fast))
                .isEqualTo(MonitoringDispatcher.DispatchOutcome.ACCEPTED);
        awaitCondition(
                () -> results.containsKey(fast.getId()),
                "monitor B should complete while monitor A is still running");
        assertThat(results.get(fast.getId()).isSuccess()).isTrue();
        assertThat(results.get(fast.getId()).httpStatus()).contains(200);

        // B já saiu do in-flight; só A continua em voo (liberação é por monitor)
        awaitCondition(
                () -> dispatcher.inFlightCount() == 1,
                "only monitor A should remain in flight");
        assertThat(results).doesNotContainKey(slow.getId());
        // as duas checagens chegaram ao transporte: B rodou em paralelo a A
        assertThat(transport.requests).hasSize(2);
        assertThat(dispatcher.dispatchedCount()).isEqualTo(2);
        assertThat(dispatcher.saturatedCount()).isZero();
        assertThat(dispatcher.internalErrorCount()).isZero();

        // liberado, A também conclui em sucesso e o in-flight zera para os dois
        transport.unblock();
        assertThat(dispatcher.awaitIdle(WAIT)).isTrue();
        assertThat(results).containsKey(slow.getId());
        assertThat(results.get(slow.getId()).isSuccess()).isTrue();
        assertThat(results.get(slow.getId()).httpStatus()).contains(200);
        assertThat(dispatcher.inFlightCount()).isZero();
        assertThat(transport.requests).hasSize(2);
    }

    // --- apoio --------------------------------------------------------------

    /**
     * Espera limitada por uma condição do teste — mesmo teto ({@link #WAIT})
     * e mesmo passo de 5ms do {@code awaitIdle}, sem sleep longo fixo.
     */
    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + description);
            }
            try {
                Thread.sleep(5);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + description, ex);
            }
        }
    }

    private MonitoringDispatcher dispatcher(int workerCount, int queueCapacity) {
        MonitoringProperties properties = properties(workerCount, queueCapacity);
        HttpMonitorExecutor executor = new HttpMonitorExecutor(
                url -> { }, transport, () -> Instant.parse("2026-03-01T00:00:00Z"));
        return new MonitoringDispatcher(properties, executor, recorder(), evaluator());
    }

    private static MonitoringProperties properties(int workerCount, int queueCapacity) {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setWorkerCount(workerCount);
        properties.setQueueCapacity(queueCapacity);
        return properties;
    }

    private static RecordingCheckRecorder recorder() {
        return new RecordingCheckRecorder();
    }

    private static RecordingEvaluator evaluator() {
        return new RecordingEvaluator();
    }

    /**
     * Double de {@link MonitorCheckRecorder} sem banco: anota o que a task
     * tentaria persistir e pode simular falha de infraestrutura.
     */
    private static final class RecordingCheckRecorder extends MonitorCheckRecorder {
        final List<MonitorExecutionResult> recorded = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        RecordingCheckRecorder() {
            super(null, null);
        }

        @Override
        public MonitorCheck record(
                MonitorExecutionResult result) {
            if (failing) {
                throw new IllegalStateException("record boom");
            }
            recorded.add(result);
            return null;
        }
    }

    /**
     * Double de {@link OperationalStateEvaluator} sem banco: anota a
     * tentativa de avaliação e pode simular falha.
     */
    private static final class RecordingEvaluator extends OperationalStateEvaluator {
        final List<UUID> evaluated = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        RecordingEvaluator() {
            super(null, null, null);
        }

        @Override
        public ServiceStatus evaluate(UUID monitorId) {
            evaluated.add(monitorId);
            if (failing) {
                throw new IllegalStateException("evaluate boom");
            }
            return null;
        }
    }

    /**
     * Transporte gravador: registra cada requisição e nunca conecta. Pode
     * ficar "travado" em uma checagem (latch) para exercitar in-flight,
     * saturação e isolamento entre monitores de forma determinística — o
     * travamento pode ser global ou restrito a UMA URL, de modo que um
     * monitor lento não segure os demais.
     */
    private static final class RecordingTransport implements HttpTransport {

        private final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        /** URL cuja checagem fica presa no latch — {@code null} = trava todas. */
        private volatile String blockedUrl;

        @Override
        public int send(HttpRequest request) throws InterruptedException {
            requests.add(request);
            if (blocks(request)) {
                entered.countDown();
                if (release.getCount() > 0) {
                    release.await(10, TimeUnit.SECONDS);
                }
            }
            return 200;
        }

        /** Só a URL bloqueada trava; qualquer outra checagem conclui na hora. */
        private boolean blocks(HttpRequest request) {
            String url = blockedUrl;
            return url == null || url.equals(request.uri().toString());
        }

        void block() {
            block(null);
        }

        /** Bloqueia apenas a checagem desta URL — as demais continuam livres. */
        void block(String url) {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            blockedUrl = url;
        }

        void unblock() {
            release.countDown();
        }

        void awaitEntered() {
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS))
                        .as("check should have started")
                        .isTrue();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for check", ex);
            }
        }
    }
}
