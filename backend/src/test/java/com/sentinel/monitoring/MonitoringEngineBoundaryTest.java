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
 * Fronteira do Prompt 09 — o que o motor de monitoramento NÃO pode virar.
 *
 * <p>O prompt entregou execução <b>transitória</b>: resultado em memória,
 * log e pronto. Este teste trava as três formas de isso vazar:</p>
 * <ol>
 *   <li><b>sem entidade/tabela de resultado:</b> nenhuma classe
 *       {@code com.sentinel.monitoring.*} é {@code @Entity} e não existe
 *       tipo {@code MonitorCheck} em lugar nenhum do código;</li>
 *   <li><b>sem migration nova:</b> nenhuma migration cria/menciona
 *       histórico de checagens (o catálogo termina na V7 do Prompt 08);</li>
 *   <li><b>sem dependência nova</b> — verificado no diff do
 *       {@code pom.xml} pelo reviewer (o arquivo não foi tocado por este
 *       prompt).</li>
 * </ol>
 *
 * <p>Quando um prompt futuro (saúde/histórico) criar persistência de
 * resultados, ele atualiza ESTE contrato de propósito — nunca por acidente.</p>
 */
class MonitoringEngineBoundaryTest {

    /** Substrings proibidas em nomes de migration (tabela de resultados). */
    private static final List<String> FORBIDDEN_MIGRATION_TERMS =
            List.of("monitor_check", "monitor_result", "monitor_history", "monitor_sample");

    @Test
    void noMonitorCheckTypeExistsInTheCodebase() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        List<String> classNames = new ArrayList<>();
        scanner.addIncludeFilter((metadataReader, factory) -> {
            classNames.add(metadataReader.getClassMetadata().getClassName());
            return true;
        });
        scanner.findCandidateComponents("com.sentinel");

        assertThat(classNames).as("o scan deve enxergar o código do projeto").isNotEmpty();
        assertThat(classNames)
                .as("não existe tipo de resultado persistido (MonitorCheck e afins)")
                .noneMatch(name -> name.contains("MonitorCheck"));
    }

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
    void noMigrationRecordsCheckHistory() throws IOException {
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
        assertThat(migrations)
                .as("nenhuma migration nova de monitoramento (catálogo termina na V7)")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("monitor_check"));

        for (String name : migrations) {
            String content = Files.readString(migrationDir.resolve(name)).toLowerCase(Locale.ROOT);
            for (String term : FORBIDDEN_MIGRATION_TERMS) {
                assertThat(content)
                        .as("migration %s não pode criar história de checagens".formatted(name))
                        .doesNotContain(term);
            }
        }
    }
}
