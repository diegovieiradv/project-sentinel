package com.sentinel.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Fronteira de persistência do motor de monitoramento — o que o histórico de
 * checagens PODE e NÃO PODE virar.
 *
 * <p>O Prompt 09 entregou execução <b>transitória</b> e o Prompt 10 abriu a
 * exceção CONTROLADA de persistir resultados. Este teste trava a forma nova
 * do contrato (e mantém as travas de integridade que continuam válidas):</p>
 * <ol>
 *   <li><b>a persistência fica FORA do pacote do engine:</b> nenhuma classe
 *       {@code com.sentinel.monitoring.*} é {@code @Entity} — o engine
 *       orquestra, o domínio
 *       {@code com.sentinel.project.monitor.model.MonitorCheck} persiste;</li>
 *   <li><b>existe UMA tabela de histórico</b> ({@code monitor_checks}, V8)
 *       e ela é append-only, sem {@code UNIQUE (monitor_id, started_at)},
 *       sem {@code execution_id} e sem {@code service_id} redundante;</li>
 *   <li><b>a única escrita de {@code Service.status}</b> é o avaliador
 *       operacional;</li>
 *   <li><b>sem dependência nova</b> — verificado no diff do
 *       {@code pom.xml} pelo reviewer (o arquivo não foi tocado por este
 *       prompt).</li>
 * </ol>
 *
 * <p>Quando um prompt futuro criar persistência adicional, ele atualiza ESTE
 * contrato de propósito — nunca por acidente.</p>
 */
class MonitoringEngineBoundaryTest {

    /** Migration que o Prompt 10 deve ter criado (e a única de histórico). */
    private static final String CHECKS_MIGRATION = "V8__create_monitor_checks_table.sql";

    @Test
    void monitoringPackageHoldsNoJpaEntities() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        List<String> entities = new ArrayList<>();
        scanner.addIncludeFilter((metadataReader, factory) -> {
            String className = metadataReader.getClassMetadata().getClassName();
            if (className.startsWith("com.sentinel.monitoring.")
                    && metadataReader.getAnnotationMetadata()
                            .hasAnnotation("jakarta.persistence.Entity")) {
                entities.add(className);
            }
            return true;
        });
        scanner.findCandidateComponents("com.sentinel");

        assertThat(entities)
                .as("o motor não persiste: nada no pacote do engine é entidade JPA")
                .isEmpty();
    }

    @Test
    void monitorCheckEntityIsMappedToChecksTable() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        List<String> checks = new ArrayList<>();
        scanner.addIncludeFilter((metadataReader, factory) -> {
            String className = metadataReader.getClassMetadata().getClassName();
            if (className.endsWith(".MonitorCheck")
                    && metadataReader.getAnnotationMetadata()
                            .hasAnnotation("jakarta.persistence.Entity")) {
                checks.add(className);
            }
            return true;
        });
        scanner.findCandidateComponents("com.sentinel");

        assertThat(checks)
                .as("existe UMA entidade MonitorCheck, anotada com @Entity")
                .containsExactly("com.sentinel.project.monitor.model.MonitorCheck");
    }

    @Test
    void checksMigrationIsAppendOnlyAndBounded() throws IOException {
        Path migrationDir = Path.of("src/main/resources/db/migration");
        assertThat(Files.isDirectory(migrationDir))
                .as("o diretório de migrations deve existir (execução a partir da raiz do módulo)")
                .isTrue();

        List<String> migrations;
        try (Stream<Path> files = Files.list(migrationDir)) {
            migrations = files.map(p -> p.getFileName().toString()).sorted().toList();
        }

        assertThat(migrations)
                .as("nomes no padrão Flyway V<n>__*.sql")
                .allMatch(name -> name.matches("V\\d+__[a-z0-9_]+\\.sql"));

        List<String> checkMigrations = migrations.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains("monitor_check"))
                .toList();
        assertThat(checkMigrations)
                .as("histórico nasce em UMA migration só (catálogo V1..V7 intacto)")
                .containsExactly(CHECKS_MIGRATION);

        String full = Files.readString(migrationDir.resolve(CHECKS_MIGRATION))
                .toLowerCase(Locale.ROOT);
        // corpo SQL sem comentários — as travas são de ESTRUTURA, não de prosa
        String sql = full.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));

        // histórico existe e é amarrado ao monitor (nunca ao serviço)
        assertThat(sql).contains("create table monitor_checks");
        assertThat(sql).contains("references monitors (id) on delete restrict");
        assertThat(sql).contains("monitor_id uuid not null");
        assertThat(sql).doesNotContain("service_id");

        // correção do orquestrador: SEM unique de timestamp (Clock congelado
        // em teste produziria startedAt idêntico e descartaria check legítimo)
        assertThat(sql).doesNotContain("unique");
        // nem execution_id (YAGNI — contrato do Prompt 09 não muda)
        assertThat(sql).doesNotContain("execution_id");
        // log append-only: sem coluna de atualização
        assertThat(sql).doesNotContain("updated_at");

        // domínio persistido espelha os enums (barreira final)
        assertThat(sql).contains("'success'");
        assertThat(sql).contains("'degraded_signal'");
        assertThat(sql).contains("'target_failure'");
        assertThat(sql).contains("'sentinel_internal_failure'");
        assertThat(sql).contains("'timeout'");
        assertThat(sql).contains("'ssrf_revalidation'");

        // leitura da janela usa o índice (monitor_id, started_at desc)
        assertThat(sql).contains("idx_monitor_checks_monitor_started_desc");
    }

    @Test
    void onlyTheOperationalEvaluatorWritesServiceStatus() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).as("código fonte principal legível").isNotEmpty();

        List<String> callers = new ArrayList<>();
        for (Path source : sources) {
            String content = Files.readString(source);
            if (content.contains("setStatusInternal(")
                    && !source.getFileName().toString().equals("Service.java")) {
                callers.add(source.getFileName().toString());
            }
        }

        assertThat(callers)
                .as("status do serviço só muda pelo OperationalStateEvaluator")
                .containsExactly("OperationalStateEvaluator.java");
    }

    // ------------------------------------------------------------------
    // Prompt 12/13 — Alert Rules (config, V9) e Alert Engine (resultado, V10).
    //
    // O Prompt 13 ENTREGOU a avaliação: a fronteira agora tem DUAS entidades
    // (AlertRule config + Alert resultado) e o engine vive FORA do domínio,
    // em com.sentinel.alerting. O que continua proibido aqui é o que é do
    // Prompt 14: Incident/IncidentEvent, notificações e qualquer Scheduler
    // de avaliação — o tick continua sendo o do MonitoringScheduler.
    // ------------------------------------------------------------------

    /** Migration aditiva do Prompt 12 (a única fora de V1..V8). */
    private static final String ALERT_RULES_MIGRATION = "V9__create_alert_rules_table.sql";

    @Test
    void alertRuleEntityIsMappedToAlertRulesTable() throws IOException {
        assertThat(scanEntities(name -> name.endsWith(".AlertRule")))
                .as("existe UMA entidade AlertRule, no pacote alert.model")
                .containsExactly("com.sentinel.project.alert.model.AlertRule");

        String source = Files.readString(
                Path.of("src/main/java/com/sentinel/project/alert/model/AlertRule.java"));
        assertThat(source)
                .as("a entidade é mapeada na tabela alert_rules")
                .contains("@Table(name = \"alert_rules\")");
    }

    @Test
    void alertRulesMigrationIsConfigBoundedAndWithoutFiringColumns() throws IOException {
        Path migrationDir = Path.of("src/main/resources/db/migration");
        String full = Files.readString(migrationDir.resolve(ALERT_RULES_MIGRATION))
                .toLowerCase(Locale.ROOT);
        String sql = full.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));

        // uma tabela só, de CONFIGURAÇÃO (updated_at existe porque a regra
        // é editável via PATCH — ao contrário do append-only do V8)
        assertThat(sql).contains("create table alert_rules");
        assertThat(sql.split("create table").length - 1).as("uma tabela por migration").isEqualTo(1);
        assertThat(sql).contains("updated_at timestamptz not null");

        // FKs: serviço obrigatório, monitor opcional, ambas com RESTRICT
        assertThat(sql)
                .contains("service_id uuid not null references services (id) on delete restrict");
        assertThat(sql)
                .contains("monitor_id uuid references monitors (id) on delete restrict");

        // NENHUMA coluna de resultado avaliado — quem dispara fica no Prompt 13
        assertThat(sql)
                .as("V9 não cria colunas de firing/avaliação")
                .doesNotContain("fired_at")
                .doesNotContain("resolved_at")
                .doesNotContain("evaluated_at")
                .doesNotContain("current_value")
                .doesNotContain("alert_state")
                .doesNotContain("notified_at");
    }

    /** Migration aditiva do Prompt 13 (o resultado avaliado). */
    private static final String ALERTS_MIGRATION = "V10__create_alerts_table.sql";

    @Test
    void alertEntityIsMappedToAlertsTable() throws IOException {
        assertThat(scanEntities(name -> name.endsWith(".Alert")))
                .as("existe UMA entidade Alert, no pacote alert.model")
                .containsExactly("com.sentinel.project.alert.model.Alert");

        String source = Files.readString(
                Path.of("src/main/java/com/sentinel/project/alert/model/Alert.java"));
        assertThat(source)
                .as("a entidade é mapeada na tabela alerts")
                .contains("@Table(name = \"alerts\")");

        // a tabela nasce na migration aditiva (V1..V9 intactos)
        Path migrationDir = Path.of("src/main/resources/db/migration");
        assertThat(Files.exists(migrationDir.resolve(ALERTS_MIGRATION)))
                .as("o resultado avaliado nasce em %s", ALERTS_MIGRATION)
                .isTrue();
    }

    @Test
    void alertEngineLivesInTheAlertingPackage() {
        List<String> engineClasses = scanClassNames(name -> {
            String simple = name.substring(name.lastIndexOf('.') + 1);
            return name.startsWith("com.sentinel.alerting.")
                    && (simple.equals("AlertEngine")
                            || simple.equals("RuleEvaluator")
                            || simple.equals("AlertLifecycleService"));
        });

        assertThat(engineClasses)
                .as("o engine de avaliação é único e vive em com.sentinel.alerting "
                        + "— fora do pacote de domínio")
                .containsExactlyInAnyOrder(
                        "com.sentinel.alerting.AlertEngine",
                        "com.sentinel.alerting.RuleEvaluator",
                        "com.sentinel.alerting.AlertLifecycleService");
    }

    @Test
    void configPackageStaysConfigurationOnly() throws IOException {
        // NENHUMA classe de engine no pacote de configuração do domínio:
        // quem avalia/dispara é com.sentinel.alerting, quem modela é
        // com.sentinel.project.alert.
        List<String> engineLike = scanClassNames(name -> {
            if (!name.startsWith("com.sentinel.project.alert.")) {
                return false;
            }
            String simple = name.substring(name.lastIndexOf('.') + 1);
            return simple.contains("Engine")
                    || simple.contains("Scheduler")
                    || simple.contains("Firing");
        });
        assertThat(engineLike)
                .as("o pacote alert não tem Engine/Scheduler/Firing — só modelo e DTOs")
                .isEmpty();

        // Fortalecimento (Reviewer): tokens proibidos em QUALQUER classe do
        // pacote de configuração alert — case-insensitive.
        List<String> alertTokenClasses = scanClassNames(name -> {
            if (!name.startsWith("com.sentinel.project.alert.")) {
                return false;
            }
            String simple = name.substring(name.lastIndexOf('.') + 1)
                    .toLowerCase(Locale.ROOT);
            // único nome legítimo que contém o token: enum de CONFIGURAÇÃO
            // das janelas de avaliação (não avalia nada)
            if (simple.equals("evaluationwindow")) {
                return false;
            }
            return simple.contains("scheduler")
                    || simple.contains("firing")
                    || simple.contains("evaluat");
        });

        assertThat(alertTokenClasses)
                .as("o pacote alert é só configuração — sem Scheduler/Firing/Evaluator*")
                .isEmpty();
    }

    /**
     * Varredura de FONTE: o pacote de configuração não agenda nem dispara
     * trabalho em background — {@code @Scheduled}/{@code @Async} são do
     * engine (Prompt 13). Varredura limitada ao pacote alert porque o
     * módulo de monitoramento tem um {@code @Scheduled} legítimo
     * ({@code MonitoringScheduler}).
     */
    @Test
    void alertPackageHasNoScheduledOrAsyncAnnotations() throws IOException {
        Path alertDir = Path.of("src/main/java/com/sentinel/project/alert");
        assertThat(Files.isDirectory(alertDir))
                .as("pacote alert existe (execução a partir da raiz do módulo)")
                .isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(alertDir)) {
            List<Path> sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(sources).as("fontes do pacote alert legíveis").isNotEmpty();
            for (Path source : sources) {
                String content = Files.readString(source);
                if (content.contains("@Scheduled") || content.contains("@Async")) {
                    offenders.add(source.getFileName().toString());
                }
            }
        }

        assertThat(offenders)
                .as("nada no pacote alert agenda (@Scheduled) ou assíncrono (@Async)")
                .isEmpty();
    }

    @Test
    void noNotificationEntitiesExist() {
        assertThat(scanEntities(name -> name.contains("Notification")))
                .as("notificações (email/Slack/webhook) ficam num prompt futuro")
                .isEmpty();
    }

    @Test
    void noIncidentEntitiesExist() {
        assertThat(scanEntities(name -> name.contains("Incident")))
                .as("Incident/IncidentEvent ficam num prompt futuro")
                .isEmpty();
    }

    /** Todas as entidades JPA cujo nome de classe casa com o predicado. */
    private static List<String> scanEntities(java.util.function.Predicate<String> match) {
        return scan(match, true);
    }

    /** Todas as classes do classpath (com ou sem @Entity) que casam. */
    private static List<String> scanClassNames(java.util.function.Predicate<String> match) {
        return scan(match, false);
    }

    private static List<String> scan(java.util.function.Predicate<String> match, boolean entitiesOnly) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        List<String> hits = new ArrayList<>();
        scanner.addIncludeFilter((metadataReader, factory) -> {
            String className = metadataReader.getClassMetadata().getClassName();
            boolean isEntity = metadataReader.getAnnotationMetadata()
                    .hasAnnotation("jakarta.persistence.Entity");
            if ((!entitiesOnly || isEntity) && match.test(className)) {
                hits.add(className);
            }
            return true;
        });
        scanner.findCandidateComponents("com.sentinel");
        return hits;
    }
}
