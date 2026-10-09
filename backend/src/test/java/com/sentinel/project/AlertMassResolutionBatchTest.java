package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.alert.model.AlertOperator;
import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.model.AlertRuleType;
import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.alert.model.EvaluationWindow;
import com.sentinel.project.model.Service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Critério #73 — resolução em massa LIMITADA a lotes (complemento Prompt 17/18).
 *
 * <p>A matriz P17 marcou {@code FAIL}: {@code AlertLifecycleService} passava
 * {@code Pageable.unpaged()} para {@code findByServiceIdAndStatus} /
 * {@code findByProjectIdAndStatus}, ou seja, a leitura do conjunto aberto
 * não tinha {@code LIMIT} nenhum na camada de aplicação. A correção paginou o
 * caminho produtivo; estes testes comprovam o que a correção precisa entregar
 * sobre um banco REAL (Testcontainers/PostgreSQL), não sobre mock:</p>
 *
 * <ol>
 *   <li><b>volume &gt; 1 lote</b> — 250 alertas {@code FIRING} (250 regras
 *       distintas, respeitando o índice parcial {@code uq_alerts_open_rule})
 *       num mesmo serviço, e 270 espalhados por 3 serviços de um projeto;</li>
 *   <li><b>zero omissão / zero duplicata</b> — a contagem exata de resolvidos
 *       é igual à de criados e nenhum alerta sobra {@code FIRING};</li>
 *   <li><b>ordenação determinística</b> — a SQL de cada lote carrega
 *       {@code order by startedAt ASC, id ASC} (com empate de {@code startedAt}
 *       semeados de propósito);</li>
 *   <li><b>terminação</b> — o número de leituras fica em
 *       {@code ceil(n / 100) + 1} (a última é a página vazia que encerra o
 *       loop); um offset crescente sobre um conjunto que encolhe pularia
 *       linhas — ver {@code AlertLifecycleService.resolveInBatches};</li>
 *   <li><b>isolamento</b> — serviço/projeto vizinhos permanecem
 *       {@code FIRING};</li>
 *   <li><b>sem N+1</b> — as leituras auxiliares por lote ficam constantes, não
 *       crescem com o volume.</li>
 * </ol>
 *
 * <p>O gravador de SQL ({@link SqlStatementRecorder}) é o que permite observar
 * {@code LIMIT}/{@code order by} sem instrumentar código de produção: o
 * Hibernate emite o statement pelo inspector registrado só nesta classe.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.sentinel.project.SqlStatementRecorder")
class AlertMassResolutionBatchTest extends AbstractAlertEngineIntegrationTest {

    /** Lote da correção do #73 — o teste espelha o tamanho para fechar as contas. */
    private static final int BATCH_SIZE = 100;

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeAll
    static void startDatabase() {
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    // --- #73 / serviço: 3 lotes ----------------------------------------------

    /**
     * Volume &gt; 1 lote num ÚNICO serviço: 250 alertas {@code FIRING} em 250
     * regras distintas. Ao desativar o serviço, todos precisam sair com
     * {@code PARENT_INACTIVE} — perder os alertas do 2º/3º lote por causa do
     * corte de página seria um alerta preso em {@code FIRING} para sempre
     * (exatamente a falha que um offset crescente causaria).
     *
     * <p>A janela de SQL também prova o formato da correção: leituras com
     * {@code LIMIT}, ordem determinística, teto de leituras (terminação) e
     * nenhum N+1 por lote.</p>
     */
    @Test
    void criterio73_servico_com_tres_lotes_resolve_todos_sem_omissao_nem_duplicata()
            throws Exception {
        Scene scene = newScene("alert-batch-service", "Org AlertBatch Service");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();
        String projectId = scene.projectId();

        // Vizinho no MESMO projeto: não pode ser alcançado pela desativação.
        String neighborServiceId =
                createService(bearer, organizationId, projectId, "Serviço vizinho do lote");

        seedFiringAlerts(scene.serviceId(), 250, "lote");
        seedFiringAlerts(neighborServiceId, 30, "vizinho");
        assertThat(firingCount(scene.serviceId())).isEqualTo(250);
        assertThat(firingCount(neighborServiceId)).isEqualTo(30);

        SqlStatementRecorder.clear();
        changeActivation(bearer, organizationId, projectId, scene.serviceId(), false);
        List<String> sql = SqlStatementRecorder.recorded();

        // --- 1) volume resolvido, 2) zero omissão / zero duplicata ----------
        assertThat(totalCount(scene.serviceId()))
                .as("a semente criou exatamente 250 alertas")
                .isEqualTo(250);
        assertThat(firingCount(scene.serviceId()))
                .as("nenhum órfão: todo alerta saiu do filtro FIRING")
                .isZero();
        assertThat(resolvedParentInactiveCount(scene.serviceId()))
                .as("resolvidos == criados (250), todos com PARENT_INACTIVE")
                .isEqualTo(250);
        assertThat(resolvedElsewhereCount(scene.serviceId()))
                .as("nenhum resolved_at fora do relógio congelado")
                .isZero();

        // --- 5) isolamento por serviço --------------------------------------
        assertThat(firingCount(neighborServiceId))
                .as("serviço vizinho permanece FIRING")
                .isEqualTo(30);

        // --- 3) ordem, 4) terminação, 6) sem N+1 -----------------------------
        int batches = assertBatchShape(sql, 250);
        assertNoFetchPerRow(sql, batches, "from services", "from alert_rules");
    }

    // --- #73 / projeto: 3 serviços, 1 projeto vizinho ------------------------

    /**
     * O caminho {@code resolveOpenForProject} cobre TODOS os serviços do
     * projeto: 90 + 90 + 90 = 270 alertas {@code FIRING} (3 lotes) devem ser
     * resolvidos num único request de desativação, enquanto o projeto vizinho
     * da mesma organização (40 alertas) não é tocado.
     */
    @Test
    void criterio73_projeto_com_tres_lotes_resolve_todos_e_poupa_o_projeto_vizinho()
            throws Exception {
        Scene scene = newScene("alert-batch-project", "Org AlertBatch Project");
        String bearer = scene.org().ownerBearer();
        String organizationId = scene.org().organizationId();

        String serviceTwo =
                createService(bearer, organizationId, scene.projectId(), "Serviço B do lote");
        String serviceThree =
                createService(bearer, organizationId, scene.projectId(), "Serviço C do lote");

        // Projeto vizinho na MESMA organização: isolamento entre projetos.
        String neighborProjectId = createProject(bearer, organizationId, "Projeto vizinho do lote");
        String neighborServiceId =
                createService(bearer, organizationId, neighborProjectId, "Serviço do vizinho");

        seedFiringAlerts(scene.serviceId(), 90, "proj-a");
        seedFiringAlerts(serviceTwo, 90, "proj-b");
        seedFiringAlerts(serviceThree, 90, "proj-c");
        seedFiringAlerts(neighborServiceId, 40, "fora");
        assertThat(firingCountInProject(scene.projectId())).isEqualTo(270);

        SqlStatementRecorder.clear();
        changeStatus(bearer, organizationId, scene.projectId(), false);
        List<String> sql = SqlStatementRecorder.recorded();

        // --- 1) volume, 2) exatidão ----------------------------------------
        assertThat(totalCountInProject(scene.projectId())).isEqualTo(270);
        assertThat(firingCountInProject(scene.projectId()))
                .as("nenhum órfão no projeto inteiro")
                .isZero();
        assertThat(resolvedParentInactiveCountInProject(scene.projectId()))
                .as("resolvidos == criados (270), todos com PARENT_INACTIVE")
                .isEqualTo(270);
        assertThat(firingCount(scene.serviceId())).isZero();
        assertThat(firingCount(serviceTwo)).isZero();
        assertThat(firingCount(serviceThree)).isZero();

        // --- 5) isolamento por projeto --------------------------------------
        assertThat(firingCount(neighborServiceId))
                .as("projeto vizinho permanece FIRING")
                .isEqualTo(40);
        assertThat(resolvedParentInactiveCount(neighborServiceId)).isZero();

        // --- 3) ordem, 4) terminação, 6) sem N+1 -----------------------------
        int batches = assertBatchShape(sql, 270);
        assertNoFetchPerRow(sql, batches, "from services", "from alert_rules");
    }

    // --- #73 / rede de segurança do engine -----------------------------------

    /**
     * O terceiro gatilho ({@code AlertEngine.evaluateService} — corrida entre
     * desativar e um check em voo) chama o MESMO {@code resolveOpenForService}
     * e precisa enxergar a mesma limitação: 130 alertas em 2 lotes, todos
     * resolvidos com {@code PARENT_INACTIVE}, com o serviço desligado direto no
     * repositório (o gancho da rota não pegou a corrida).
     */
    @Test
    void criterio73_rede_de_seguranca_do_engine_resolve_o_lote_inteiro() throws Exception {
        Scene scene = newScene("alert-batch-engine", "Org AlertBatch Engine");
        seedFiringAlerts(scene.serviceId(), 130, "rede");
        assertThat(firingCount(scene.serviceId())).isEqualTo(130);

        // desligar DIRETO no repositório (a corrida que o gancho não pegou)
        Service service = serviceRepository
                .findById(UUID.fromString(scene.serviceId()))
                .orElseThrow();
        service.setActive(false);
        serviceRepository.save(service);

        SqlStatementRecorder.clear();
        evaluate(scene.serviceId());
        List<String> sql = SqlStatementRecorder.recorded();

        assertThat(firingCount(scene.serviceId())).isZero();
        assertThat(resolvedParentInactiveCount(scene.serviceId()))
                .as("resolvidos == criados (130), todos com PARENT_INACTIVE")
                .isEqualTo(130);

        int batches = assertBatchShape(sql, 130);
        assertNoFetchPerRow(sql, batches, "from services", "from alert_rules");
    }

    // --- semeadura (regras distintas: o índice parcial permite 1 FIRING/regra)

    /**
     * {@code count} alertas {@code FIRING} num serviço, cada UM em regra
     * própria — {@code uq_alerts_open_rule} (V10) permite no máximo um alerta
     * aberto por regra, então 250 alertas exigem 250 regras.
     *
     * <p>{@code startedAt} embaralhado e com <b>empates</b> de propósito: a
     * ordem do lote precisa ser total (desempate em {@code id}) e a
     * completude não pode depender da ordem de chegada.</p>
     */
    private void seedFiringAlerts(String serviceId, int count, String tag) {
        Service service = serviceRepository
                .findById(UUID.fromString(serviceId))
                .orElseThrow();
        List<AlertRule> rules = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rules.add(new AlertRule(
                    service,
                    tag + " regra " + i,
                    null,
                    AlertRuleType.AVAILABILITY,
                    AlertOperator.LESS_THAN,
                    new BigDecimal("99.9"),
                    null,
                    EvaluationWindow.FIFTEEN_MINUTES,
                    AlertSeverity.SEV3));
        }
        List<AlertRule> savedRules = alertRuleRepository.saveAll(rules);

        List<Alert> alerts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // startedAt embaralhado com laços (i*7 mod 41): ordem não-monótona
            // e empates reais, para o desempate por id ser exercitado de fato.
            java.time.Instant startedAt = NOW.minusSeconds(600 - (i * 7 % 41));
            alerts.add(new Alert(
                    service,
                    savedRules.get(i),
                    tag + " alerta " + i,
                    new BigDecimal("99.90"),
                    startedAt));
        }
        alertRepository.saveAll(alerts);
    }

    // --- leituras via SQL (volume exato, sem depender do helper de página) ---

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).as("count query devolveu nulo: " + sql).isNotNull();
        return value;
    }

    private long totalCount(String serviceId) {
        return queryLong("SELECT count(*) FROM alerts WHERE service_id = ?",
                UUID.fromString(serviceId));
    }

    private long firingCount(String serviceId) {
        return queryLong(
                "SELECT count(*) FROM alerts WHERE service_id = ? AND status = 'FIRING'",
                UUID.fromString(serviceId));
    }

    private long resolvedParentInactiveCount(String serviceId) {
        return queryLong(
                "SELECT count(*) FROM alerts WHERE service_id = ? AND status = 'RESOLVED' "
                        + "AND resolution_reason = 'PARENT_INACTIVE'",
                UUID.fromString(serviceId));
    }

    /** Resolvidos fora do instante do relógio congelado — tem de ser zero. */
    private long resolvedElsewhereCount(String serviceId) {
        return queryLong(
                "SELECT count(*) FROM alerts WHERE service_id = ? AND status = 'RESOLVED' "
                        + "AND resolved_at IS DISTINCT FROM ?",
                UUID.fromString(serviceId), Timestamp.from(NOW));
    }

    private long totalCountInProject(String projectId) {
        return queryLong(
                "SELECT count(*) FROM alerts a JOIN services s ON s.id = a.service_id "
                        + "WHERE s.project_id = ?",
                UUID.fromString(projectId));
    }

    private long firingCountInProject(String projectId) {
        return queryLong(
                "SELECT count(*) FROM alerts a JOIN services s ON s.id = a.service_id "
                        + "WHERE s.project_id = ? AND a.status = 'FIRING'",
                UUID.fromString(projectId));
    }

    private long resolvedParentInactiveCountInProject(String projectId) {
        return queryLong(
                "SELECT count(*) FROM alerts a JOIN services s ON s.id = a.service_id "
                        + "WHERE s.project_id = ? AND a.status = 'RESOLVED' "
                        + "AND a.resolution_reason = 'PARENT_INACTIVE'",
                UUID.fromString(projectId));
    }

    // --- asserções de forma da SQL (limit / ordem / terminação / N+1) --------

    /**
     * SELECTs de <b>conteúdo</b> sobre {@code alerts} na janela medida (os
     * COUNT derivados do {@code Page} são excluídos: não carregam linha).
     */
    private static List<String> contentSelectsOnAlerts(List<String> sql) {
        return sql.stream()
                .map(String::toLowerCase)
                .filter(statement -> statement.startsWith("select"))
                .filter(statement -> statement.contains("from alerts"))
                .filter(statement -> !statement.contains("count("))
                .toList();
    }

    private static List<String> selectsOn(List<String> sql, String fromClause) {
        String clause = fromClause.toLowerCase();
        return sql.stream()
                .map(String::toLowerCase)
                .filter(statement -> statement.startsWith("select"))
                .filter(statement -> statement.contains(clause))
                .filter(statement -> !statement.contains("count("))
                .toList();
    }

    private static String orderByClause(String statement) {
        int index = statement.lastIndexOf("order by");
        return index < 0 ? "" : statement.substring(index);
    }

    /**
     * Leitura limitada — o Hibernate 7 da correção emite SQL:2008
     * ({@code fetch first ? rows only}); {@code limit ?} é aceito para não amarrar
     * o teste a um dialeto. Uma leitura SEM nenhuma das duas formas é a regressão
     * do #73 (o {@code Pageable.unpaged()} antigo não imponha teto algum).
     */
    private static boolean isLimited(String statement) {
        String lower = statement.toLowerCase();
        return lower.contains("fetch first") || lower.contains("limit");
    }

    /**
     * Ordem total {@code (startedAt, id)} — sem {@code DESC} e nessa ordem de
     * colunas. O Hibernate omite o {@code asc} explícito porque é a direção
     * default, então a asserção casa a forma emitida
     * ({@code order by a1_0.started_at,a1_0.id}); a cláusula de teto que vem em
     * seguida ({@code fetch first ? rows only}) é destacada antes de comparar.
     *
     * <p>Sem esta ordem, dois alertas com o mesmo {@code startedAt} teriam ordem
     * arbitrária entre leituras — e a completude do lote deixaria de ser
     * determinística.</p>
     */
    private static boolean isDeterministicallyOrdered(String statement) {
        String order = orderByClause(statement);
        if (order.isEmpty() || order.contains("desc")) {
            return false;
        }
        int ceiling = order.indexOf("fetch first");
        if (ceiling < 0) {
            ceiling = order.indexOf("limit");
        }
        if (ceiling >= 0) {
            order = order.substring(0, ceiling);
        }
        return order.trim().matches("order by\\s+\\S*started_at\\s*(asc)?\\s*,\\s*\\S+id\\s*(asc)?");
    }

    /**
     * Forma esperada da correção: exatamente {@code ceil(total / 100) + 1}
     * leituras de conteúdo — as {@code ceil(total / 100)} cheias (a última pode
     * ser parcial) + a página vazia que encerra o loop —, TODAS limitadas e com
     * ordem total {@code (startedAt, id)}.
     *
     * <p>Um caminho {@code unpaged} viraria UMA única leitura sem teto (falha do
     * #73) e um offset crescente deixaria alertas de fora da contagem (falha de
     * omissão, exatamente a armadilha documentada em
     * {@code AlertLifecycleService.resolveInBatches}) — ambas as regressões
     * derrubam estas asserções junto com a de volume feita antes.</p>
     */
    private int assertBatchShape(List<String> sql, int total) {
        List<String> reads = contentSelectsOnAlerts(sql);
        int fullBatches = total / BATCH_SIZE;
        int expectedReads = fullBatches + (total % BATCH_SIZE == 0 ? 0 : 1) + 1;
        assertThat(reads)
                .as("leituras de conteúdo sobre `alerts` (esperado %d = lotes + página vazia): %s",
                        expectedReads, reads)
                .hasSize(expectedReads);
        assertThat(reads)
                .as("toda leitura nasce LIMITADA (leituras: %s)", reads)
                .allMatch(AlertMassResolutionBatchTest::isLimited);
        assertThat(reads)
                .as("toda leitura traz ordem total (startedAt, id) (leituras: %s)", reads)
                .allMatch(AlertMassResolutionBatchTest::isDeterministicallyOrdered);
        return reads.size();
    }

    /**
     * Sem N+1 introduzido: as leituras auxiliares das associações EAGER
     * ({@code service}, {@code alertRule}) acontecem no máximo UMA POR LOTE —
     * nunca uma por linha. Um N+1 real cresceria com o volume: num lote de 100,
     * "uma por linha" viraria ~100 leituras.
     *
     * @param batchReads leituras de lote já contadas por {@link #assertBatchShape}
     */
    private void assertNoFetchPerRow(List<String> sql, int batchReads, String... fromClauses) {
        for (String fromClause : fromClauses) {
            List<String> fetches = selectsOn(sql, fromClause);
            assertThat(fetches)
                    .as("leituras de `%s` no máximo uma por lote, nunca uma por linha "
                            + "(leituras de lote: %d; auxiliares: %s)",
                            fromClause, batchReads, fetches)
                    .hasSizeLessThanOrEqualTo(Math.max(batchReads, 1));
        }
    }
}
