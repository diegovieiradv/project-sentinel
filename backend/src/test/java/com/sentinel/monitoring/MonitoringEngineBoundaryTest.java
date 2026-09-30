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
}
