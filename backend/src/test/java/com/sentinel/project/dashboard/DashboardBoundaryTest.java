package com.sentinel.project.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Fronteira do painel do projeto (Prompt 16) — o que a composição de leitura
 * PODE e NÃO PODE virar.
 *
 * <p>O painel é read model: ele COMPÕE dados já existentes em uma única
 * resposta e não abre um novo domínio. Este contrato trava a forma dessa
 * exceção:</p>
 * <ol>
 *   <li><b>nenhuma classe do pacote é entidade JPA</b> — não existe schema do
 *       painel, portanto também não existe migration dele;</li>
 *   <li><b>nada no pacote escreve</b>: sem {@code @Modifying}, sem
 *       {@code save/persist}, sem SQL de escrita, sem {@code new Incident}
 *       ou {@code new Alert}, sem {@code @Scheduled}/{@code @Async}/
 *       {@code @EventListener} e sem tocar em {@code Service.status};</li>
 *   <li><b>o repositório da UNIÃO é só marcador</b> —
 *       {@code org.springframework.data.repository.Repository}, nunca
 *       {@code JpaRepository}/{@code CrudRepository} (nada de CRUD herdado
 *       vira API do painel);</li>
 *   <li><b>o controller só declara GET</b> — nenhuma rota de escrita;</li>
 *   <li><b>as consultas novas nos repositórios de domínio são de leitura</b>
 *       — o trecho "Prompt 16" de cada um não tem {@code @Modifying} nem SQL
 *       de escrita;</li>
 *   <li><b>sem nova migration</b> — o catálogo termina na V12.</li>
 * </ol>
 *
 * <p>Quando um prompt futuro criar escrita de verdade, ele atualiza ESTE
 * contrato de propósito — nunca por acidente.</p>
 */
class DashboardBoundaryTest {

    /** Diretórios de origem (execução a partir da raiz do módulo). */
    private static final Path DASHBOARD_DIR = Path.of("src/main/java/com/sentinel/project/dashboard");
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path CONTROLLER =
            DASHBOARD_DIR.resolve("web/DashboardController.java");
    private static final Path QUERY_SERVICE =
            DASHBOARD_DIR.resolve("service/DashboardQueryService.java");
    private static final Path ACTIVITY_REPOSITORY =
            DASHBOARD_DIR.resolve("repository/DashboardActivityRepository.java");

    // --- composição: sem domínio novo ----------------------------------------

    @Test
    void dashboardPackageHoldsNoJpaEntities() {
        assertThat(scanEntities(name -> name.startsWith("com.sentinel.project.dashboard.")))
                .as("o painel não tem schema próprio: nada no pacote é entidade JPA")
                .isEmpty();
    }

    @Test
    void dashboardPackageIsPureReadComposition() throws IOException {
        List<String> offenders = new ArrayList<>();
        String[] forbidden = {
            "@Modifying",
            ".save(",
            "saveAndFlush(",
            ".persist(",
            ".remove(",
            "insert into",
            "delete from",
            "new Incident(",
            "new Alert(",
            "setStatus(",
            "setStatusChangedAt(",
            "setActive(",
            "@Scheduled",
            "@Async",
            "@EventListener",
            "AlertLifecycleService",
            "AlertEngine",
            "MonitoringScheduler",
        };

        assertThat(Files.isDirectory(DASHBOARD_DIR))
                .as("pacote do painel existe (execução a partir da raiz do módulo)")
                .isTrue();

        try (Stream<Path> walk = Files.walk(DASHBOARD_DIR)) {
            List<Path> sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(sources).as("fontes do pacote do painel legíveis").isNotEmpty();
            for (Path source : sources) {
                String code = withoutComments(Files.readString(source));
                for (String token : forbidden) {
                    if (code.toLowerCase(java.util.Locale.ROOT)
                            .contains(token.toLowerCase(java.util.Locale.ROOT))) {
                        offenders.add(source.getFileName() + " → " + token);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("o painel só lê: nenhuma escrita, tarefa ou orquestrador no pacote")
                .isEmpty();
    }

    @Test
    void queryServiceIsMarkedReadOnly() throws IOException {
        String code = withoutComments(Files.readString(QUERY_SERVICE));

        assertThat(code)
                .as("a composição inteira roda em transação somente-leitura")
                .contains("@Transactional(readOnly = true)");
        assertThat(code)
                .as("a cadeia Organization → Project continua sendo a autoridade de 404")
                .contains("findByOrganizationIdAndId");
    }

    // --- repositório da união -------------------------------------------------

    @Test
    void activityRepositoryIsAReadOnlyMarkerNotACrudRepository() throws IOException {
        String code = withoutComments(Files.readString(ACTIVITY_REPOSITORY));

        assertThat(code)
                .as("só marcador de leitura: org.springframework.data.repository.Repository")
                .contains("extends Repository<");
        assertThat(code)
                .as("nada de CRUD herdado vira superfície do painel")
                .doesNotContain("JpaRepository")
                .doesNotContain("CrudRepository")
                .doesNotContain("PagingAndSortingRepository")
                .doesNotContain("deleteById")
                .doesNotContain("saveAll");
        assertThat(code)
                .as("a atividade é UMA união nativa ordenada e limitada no banco")
                .contains("UNION ALL")
                .contains("nativeQuery = true")
                .contains("LIMIT :limit");
    }

    // --- web -----------------------------------------------------------------

    @Test
    void controllerDeclaresOnlyGet() throws IOException {
        String code = withoutComments(Files.readString(CONTROLLER));

        assertThat(code)
                .as("o painel é uma leitura: só GET")
                .contains("@GetMapping")
                .doesNotContain("@PostMapping")
                .doesNotContain("@PutMapping")
                .doesNotContain("@PatchMapping")
                .doesNotContain("@DeleteMapping");
        assertThat(code)
                .as("a leitura continua no filtro de tenant das demais rotas")
                .contains("@TenantScoped");
        assertThat(code)
                .as("id malformado vira 404 genérico antes de chegar ao repositório")
                .contains("Resource not found");
    }

    // --- consultas novas nos repositórios de domínio --------------------------

    @Test
    void dashboardQueriesInDomainRepositoriesAreReadOnly() throws IOException {
        assertReadOnlySection(
                Path.of("src/main/java/com/sentinel/project/repository/ServiceRepository.java"),
                "countStatusByProject",
                "findServicesNeedingAttention");
        assertReadOnlySection(
                Path.of("src/main/java/com/sentinel/project/monitor/repository/MonitorCheckRepository.java"),
                "summaryByProject");
        assertReadOnlySection(
                Path.of("src/main/java/com/sentinel/project/alert/repository/AlertRepository.java"),
                "countFiringBySeverityForProject",
                "findTopFiringForProject");
        assertReadOnlySection(
                Path.of("src/main/java/com/sentinel/project/incident/repository/IncidentRepository.java"),
                "countActiveByStatusForProject",
                "findTopActiveForProject");
    }

    // --- migrations -------------------------------------------------------------

    @Test
    void panelRequiresNoNewMigration() throws IOException {
        assertThat(Files.isDirectory(MIGRATION_DIR))
                .as("o diretório de migrations deve existir (execução a partir da raiz do módulo)")
                .isTrue();

        List<String> names;
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            names = files.map(p -> p.getFileName().toString()).sorted().toList();
        }

        assertThat(names)
                .as("nomes no padrão Flyway V<n>__*.sql")
                .allMatch(name -> name.matches("V\\d+__[a-z0-9_]+\\.sql"));

        // ordem NUMÉRICA: a ordenação lexicográfica colocaria V10 antes de V2
        List<Integer> versions = names.stream()
                .map(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                .sorted()
                .toList();

        assertThat(versions)
                .as("o Prompt 16 é leitura pura: o catálogo continua em V1..V12, "
                        + "sem V13 nem migration de painel")
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }

    // --- helpers ---------------------------------------------------------------

    /**
     * O trecho "Prompt 16" de um repositório de domínio declara só consulta:
     * {@code @Query} presente, nenhum {@code @Modifying} e nenhum SQL de
     * escrita depois do marcador.
     */
    private static void assertReadOnlySection(Path repository, String... methods)
            throws IOException {
        assertThat(Files.exists(repository))
                .as("repositório %s existe", repository)
                .isTrue();

        String content = Files.readString(repository);
        int marker = content.indexOf("Prompt 16");
        assertThat(marker)
                .as("%s tem a seção do painel marcada com \"Prompt 16\"", repository.getFileName())
                .isNotNegative();

        String tail = withoutComments(content.substring(marker));
        assertThat(tail)
                .as("%s: o trecho do painel é consulta (@Query), nunca @Modifying",
                        repository.getFileName())
                .contains("@Query")
                .doesNotContain("@Modifying")
                .doesNotContain("insert into")
                .doesNotContain("delete from")
                .doesNotContain("update ");

        for (String method : methods) {
            assertThat(tail)
                    .as("%s declara %s dentro da seção do painel",
                            repository.getFileName(), method)
                    .contains(method + "(");
        }
    }

    /** Remove comentários de bloco e de linha antes de procurar tokens. */
    private static String withoutComments(String content) {
        String noBlocks = content.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlocks.replaceAll("//[^\n]*", " ");
    }

    /** Todas as entidades JPA cujo nome de classe casa com o predicado. */
    private static List<String> scanEntities(java.util.function.Predicate<String> match) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        List<String> hits = new ArrayList<>();
        scanner.addIncludeFilter((metadataReader, factory) -> {
            String className = metadataReader.getClassMetadata().getClassName();
            boolean isEntity = metadataReader.getAnnotationMetadata()
                    .hasAnnotation("jakarta.persistence.Entity");
            if (isEntity && match.test(className)) {
                hits.add(className);
            }
            return true;
        });
        scanner.findCandidateComponents("com.sentinel");
        return hits;
    }
}
