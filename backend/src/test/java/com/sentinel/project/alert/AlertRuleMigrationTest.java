package com.sentinel.project.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Migração V9 do Prompt 12 (Seção 55 — critérios 91–98) — catálogo Flyway
 * completo, identidade byte das V1..V8 e estrutura da tabela
 * {@code alert_rules}.
 *
 * <p>Catálogo limitado a V1..V10 (nenhuma migration além das dos Prompts 12 e
 * 13 — a V10 é do Alert Engine e é coberta pelo AlertMigrationTest, que também
 * prova V1..V9 byte-idênticas); V1..V8 precisam estar <b>byte-idênticas</b> ao
 * baseline do repositório (SHA-256
 * fixado aqui — qualquer toque destrói o checksum do Flyway); e a V9 precisa
 * trazer as FKs {@code ON DELETE RESTRICT}, os CHECKs de domínio e os índices
 * do contrato. Os testes de Integração provam que o Flyway VALIDA o catálogo
 * em startup real (com {@code ddl-auto=validate} do Hibernate no mesmo boot)
 * e que o Testcontainers sobe o Postgres.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AlertRuleMigrationTest {

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

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    private static final String V9_FILE = "V9__create_alert_rules_table.sql";

    /** SHA-256 das V1..V8 no baseline do prompt (nada além da V9 é tocado). */
    private static final Map<String, String> BASELINE_V1_TO_V8 = Map.ofEntries(
            Map.entry("V1__init_schema.sql", "e3c5bb5aec31fabbe19755a5aad35c9507f7694f107ced0d8533f8d1c195de5c"),
            Map.entry("V2__create_users_table.sql", "4da274817d499a2a8e3f1c89c725f17635b7e1d01efd8bf32a8075fb0c2fad14"),
            Map.entry("V3__create_refresh_tokens_table.sql", "eb07b6b807e19c5f6045085f3526062c08248b492b423251bd7515ffb2cc53a2"),
            Map.entry("V4__create_organizations_and_memberships.sql", "bbb7d12c594c3d416fd5b99c2912edf591e830fe6fb9e6c06cbee9380b82e1d8"),
            Map.entry("V5__create_projects_table.sql", "ce79608476241038a018e53c6dbc106e4ceb1f5295c41d4867ce59573dd8b6d2"),
            Map.entry("V6__create_services_table.sql", "e3e10b7b86ae61768adeec81514b41b56e4ed9eea12906c02d98537d93848a23"),
            Map.entry("V7__create_monitors_table.sql", "edbb432bf689b0e0531466cbda881fe540fa08c102dfa725e89092c2fd3e9b84"),
            Map.entry("V8__create_monitor_checks_table.sql", "af5276f21879589e492b6503bd0046a8a33a58c8e33c507129332298b2b37e19"));

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    // --- 91: catálogo V1..V10 ------------------------------------------------

    @Test
    void migrationsV1ToV9Exist() throws IOException {
        List<String> migrations = listMigrations();

        assertThat(migrations)
                .as("nomes no padrão Flyway V<n>__*.sql")
                .allMatch(name -> name.matches("V\\d+__[a-z0-9_]+\\.sql"));

        // catálogo EXATO: V1..V9 (baseline) + V10 (Alert Engine, P13) +
        // V11 (Incidents, P14), nada além
        List<Integer> versions = migrations.stream()
                .map(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                .sorted()
                .toList();
        assertThat(versions)
                .as("catálogo termina na V11 (V1..V9 preservadas + V10 do Prompt 13"
                        + " + V11 do Prompt 14)")
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    }

    // --- 92: V1..V8 byte-idênticas ------------------------------------------

    @Test
    void v1ToV8ChecksumsUnchanged() throws IOException {
        Map<String, String> mismatches = new LinkedHashMap<>();
        BASELINE_V1_TO_V8.forEach((file, expectedHash) -> {
            try {
                String actual = sha256(MIGRATION_DIR.resolve(file));
                if (!actual.equals(expectedHash)) {
                    mismatches.put(file, actual);
                }
            }
            catch (IOException | NoSuchAlgorithmException ex) {
                throw new AssertionError("não foi possível hashear " + file, ex);
            }
        });

        assertThat(mismatches)
                .as("V1..V8 precisam estar byte-idênticas ao baseline (checksum do Flyway)")
                .isEmpty();
    }

    // --- 93–94: FKs ----------------------------------------------------------

    @Test
    void migrationHasFkService() throws IOException {
        String sql = bodyOfV9();

        assertThat(sql)
                .as("service_id é obrigatório e amarrado ao serviço com RESTRICT")
                .contains("service_id uuid not null references services (id) on delete restrict");
    }

    @Test
    void migrationHasNullableFkMonitor() throws IOException {
        String sql = bodyOfV9();

        assertThat(sql)
                .as("monitor_id é OPCIONAL (Service-level = null) e amarrado ao monitor com RESTRICT")
                .contains("monitor_id uuid references monitors (id) on delete restrict");
        assertThat(sql)
                .as("monitor_id nunca é NOT NULL — regra Service-level não tem monitor")
                .doesNotContain("monitor_id uuid not null");
    }

    // --- 95: constraints -----------------------------------------------------

    @Test
    void migrationHasCheckConstraints() throws IOException {
        String sql = bodyOfV9();

        // unicidade por serviço (nome normalizado, ativo OU inativo)
        assertThat(sql)
                .contains("unique (service_id, name_normalized)");
        // nome em branco recusado pelo banco
        assertThat(sql).contains("check (trim(name) <> '')");
        // domínios fechados dos enums
        assertThat(sql).contains("check (type in");
        assertThat(sql).contains("'availability'");
        assertThat(sql).contains("'service_status'");
        assertThat(sql).contains("check (operator in");
        assertThat(sql).contains("'less_than'");
        assertThat(sql).contains("'equal'");
        assertThat(sql).contains("check (\"window\" in");
        assertThat(sql).contains("'five_minutes'");
        assertThat(sql).contains("'twenty_four_hours'");
        assertThat(sql).contains("check (severity in");
        assertThat(sql).contains("'sev1'");
        assertThat(sql).contains("'sev4'");
        // thresholds mutuamente exclusivos + faixas por tipo
        assertThat(sql)
                .as("SERVICE_STATUS nunca guarda threshold numérico")
                .contains("type = 'service_status' and threshold_numeric is null");
        assertThat(sql)
                .as("status fora de SERVICE_STATUS é nulo")
                .contains("type <> 'service_status' and threshold_status is null");
        assertThat(sql).contains("between 0 and 100");
        assertThat(sql).contains("threshold_numeric > 0");
        assertThat(sql).contains("threshold_numeric >= 0");
    }

    // --- 96: índices ---------------------------------------------------------

    @Test
    void migrationHasIndexes() throws IOException {
        String sql = bodyOfV9();

        assertThat(sql)
                .as("lookup de regras por monitor")
                .contains("create index idx_alert_rules_monitor_id on alert_rules (monitor_id)");
        assertThat(sql)
                .as("filtro de regras ativas (elegibilidade do futuro Engine)")
                .contains("create index idx_alert_rules_active on alert_rules (active)");
        assertThat(sql)
                .as("service_id já coberto pelo prefixo do UNIQUE composto")
                .contains("unique (service_id, name_normalized)");
    }

    // --- 97: Flyway valida o catálogo em startup real ------------------------

    @Test
    void flywayValidatePasses() {
        // mesmo caminho do boot: valida checksums dos arquivos contra o histórico
        flyway.validate();

        List<Map<String, Object>> rows = new JdbcTemplate(dataSource).queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");

        assertThat(rows).hasSize(11);
        assertThat(rows)
                .extracting(row -> String.valueOf(row.get("version")))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(rows)
                .as("nenhuma migration falhou (o Hibernate ddl-auto=validate subiu no mesmo boot)")
                .extracting(row -> row.get("success"))
                .containsOnly(Boolean.TRUE);
    }

    // --- 98: Testcontainers --------------------------------------------------

    @Test
    void testcontainersStartsPostgres() {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(postgres.getDockerImageName()).contains("postgres");
    }

    // --- apoio ---------------------------------------------------------------

    private static List<String> listMigrations() throws IOException {
        assertThat(Files.isDirectory(MIGRATION_DIR))
                .as("diretório de migrations (execução a partir da raiz do módulo)")
                .isTrue();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Corpo SQL da V9 em minúsculas e sem comentários (estrutura, não prosa). */
    private static String bodyOfV9() throws IOException {
        String full = Files.readString(MIGRATION_DIR.resolve(V9_FILE)).toLowerCase(Locale.ROOT);
        return full.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest);
    }
}
