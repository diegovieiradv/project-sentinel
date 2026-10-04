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
 * Migração V10 do Prompt 13 (critérios 97–102) — catálogo Flyway completo,
 * identidade byte das V1..V9 e estrutura da tabela {@code alerts}.
 *
 * <p>Catálogo limitado a V1..V12 (nenhuma migration além das dos Prompts
 * 13, 14 e 15 — a V12 é a aditiva de automação Alert → Incident);
 * V1..V9 precisam estar <b>byte-idênticas</b> ao baseline do repositório
 * (SHA-256 fixado aqui — qualquer toque destrói o checksum do Flyway); e a
 * V10 precisa trazer as FKs {@code ON DELETE RESTRICT}, os CHECKs do ciclo de
 * vida (mensagem do FIRING preservada, motivo da resolução) e o índice parcial
 * Único de deduplicação. Os testes de Integração provam que o Flyway VALIDA o
 * catálogo em startup real (com {@code ddl-auto=validate} do Hibernate no
 * mesmo boot) e que o Testcontainers sobe o Postgres.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AlertMigrationTest {

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

    private static final String V10_FILE = "V10__create_alerts_table.sql";

    /** SHA-256 das V1..V9 no baseline do prompt (só a V10 é adicionada). */
    private static final Map<String, String> BASELINE_V1_TO_V9 = Map.ofEntries(
            Map.entry("V1__init_schema.sql", "e3c5bb5aec31fabbe19755a5aad35c9507f7694f107ced0d8533f8d1c195de5c"),
            Map.entry("V2__create_users_table.sql", "4da274817d499a2a8e3f1c89c725f17635b7e1d01efd8bf32a8075fb0c2fad14"),
            Map.entry("V3__create_refresh_tokens_table.sql", "eb07b6b807e19c5f6045085f3526062c08248b492b423251bd7515ffb2cc53a2"),
            Map.entry("V4__create_organizations_and_memberships.sql", "bbb7d12c594c3d416fd5b99c2912edf591e830fe6fb9e6c06cbee9380b82e1d8"),
            Map.entry("V5__create_projects_table.sql", "ce79608476241038a018e53c6dbc106e4ceb1f5295c41d4867ce59573dd8b6d2"),
            Map.entry("V6__create_services_table.sql", "e3e10b7b86ae61768adeec81514b41b56e4ed9eea12906c02d98537d93848a23"),
            Map.entry("V7__create_monitors_table.sql", "edbb432bf689b0e0531466cbda881fe540fa08c102dfa725e89092c2fd3e9b84"),
            Map.entry("V8__create_monitor_checks_table.sql", "af5276f21879589e492b6503bd0046a8a33a58c8e33c507129332298b2b37e19"),
            Map.entry("V9__create_alert_rules_table.sql", "c813e455b43013965b2fc2ccc1b97c181e3e5a242e55718d025c0ac3575c52fb"));

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    // --- 97: catálogo V1..V12 ------------------------------------------------

    @Test
    void criterio97_catalogo_v1_a_v10_completo_e_nada_alem() throws IOException {
        List<String> migrations = listMigrations();

        assertThat(migrations)
                .as("nomes no padrão Flyway V<n>__*.sql")
                .allMatch(name -> name.matches("V\\d+__[a-z0-9_]+\\.sql"));

        List<Integer> versions = migrations.stream()
                .map(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                .sorted()
                .toList();
        assertThat(versions)
                .as("catálogo termina na V12 (V1..V9 originais + V10 do prompt"
                        + " + V11 do Prompt 14 + V12 do Prompt 15)")
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }

    // --- 98: V1..V9 byte-idênticas ------------------------------------------

    @Test
    void criterio98_v1_a_v9_checksums_inalterados() throws IOException {
        Map<String, String> mismatches = new LinkedHashMap<>();
        BASELINE_V1_TO_V9.forEach((file, expectedHash) -> {
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
                .as("V1..V9 precisam estar byte-idênticas ao baseline (checksum do Flyway)")
                .isEmpty();
    }

    // --- 99: colunas e FKs ---------------------------------------------------

    @Test
    void criterio99_tabela_alerts_colunas_e_fks_restricted() throws IOException {
        String sql = bodyOf(V10_FILE);

        assertThat(sql).contains("create table alerts");
        // FKs — RESTRICT nos três pais (histórico nunca some por cascata)
        assertThat(sql)
                .contains("service_id uuid not null references services (id) on delete restrict");
        assertThat(sql)
                .contains("alert_rule_id uuid not null references alert_rules (id) on delete restrict");
        assertThat(sql)
                .contains("monitor_id uuid references monitors (id) on delete restrict");
        assertThat(sql)
                .as("monitor_id é OPCIONAL (Service-level = null)")
                .doesNotContain("monitor_id uuid not null");
        // ciclo de vida obrigatório
        assertThat(sql).contains("status varchar(10) not null");
        assertThat(sql).contains("severity varchar(10) not null");
        assertThat(sql).contains("started_at timestamptz not null");
        assertThat(sql).contains("last_evaluated_at timestamptz not null");
        assertThat(sql).contains("message varchar(500) not null");
        assertThat(sql).contains("resolution_reason varchar(20)");
        // SNAPSHOT da regra copiado no disparo (eixo único de severidade)
        assertThat(sql)
                .as("NÃO existe rule_severity — severity É o snapshot")
                .doesNotContain("rule_severity");
        assertThat(sql).contains("rule_type varchar(30) not null");
        assertThat(sql).contains("rule_operator varchar(30) not null");
        assertThat(sql).contains("rule_threshold_numeric numeric(19,4)");
        assertThat(sql).contains("rule_threshold_status varchar(20)");
        assertThat(sql).contains("rule_window varchar(30) not null");
        // eixo temporal do SERVICE_STATUS
        assertThat(sql)
                .contains("alter table services add column status_changed_at timestamptz");
    }

    // --- 100: CHECKs do ciclo e do snapshot ----------------------------------

    @Test
    void criterio100_checks_fecham_ciclo_e_motivo_da_resolucao() throws IOException {
        String sql = bodyOf(V10_FILE);

        assertThat(sql).contains("check (status in ('firing','resolved'))");
        assertThat(sql).contains("check (severity in ('sev1','sev2','sev3','sev4'))");
        // motivo anulável fechado: AUTO | RULE_DEACTIVATED | PARENT_INACTIVE
        assertThat(sql)
                .contains("resolution_reason is null or resolution_reason in ('auto','rule_deactivated','parent_inactive')");
        // consistência do ciclo (a metade escrita — o resto é o índice parcial)
        assertThat(sql)
                .as("FIRING nunca tem resolved_at/resolution_reason; RESOLVED sempre tem")
                .contains("(status = 'firing' and resolved_at is null and resolution_reason is null) or");
        assertThat(sql)
                .contains("(status = 'resolved' and resolved_at is not null and resolution_reason is not null)");
        // snapshot
        assertThat(sql).contains("check (trim(message) <> '')");
        assertThat(sql).contains("check (\n    rule_type in");
        assertThat(sql).contains("'service_status'");
        assertThat(sql).contains("check (\n    rule_operator in");
        assertThat(sql).contains("'less_than_or_equal'");
        assertThat(sql).contains("check (\n    rule_window in");
        assertThat(sql).contains("'twenty_four_hours'");
        assertThat(sql)
                .as("SERVICE_STATUS nunca guarda threshold numérico")
                .contains("rule_type = 'service_status' and rule_threshold_numeric is null or");
        assertThat(sql)
                .as("status de domínio só existe na SERVICE_STATUS")
                .contains("rule_type = 'service_status' or rule_threshold_status is null");
    }

    // --- 101: índices (dedup parcial + listagem) -----------------------------

    @Test
    void criterio101_indices_garantem_dedup_e_listagem() throws IOException {
        String sql = bodyOf(V10_FILE);

        assertThat(sql)
                .as("UM alerta aberto por regra — unicidade parcial sob concorrência")
                .contains("create unique index uq_alerts_open_rule on alerts (alert_rule_id) where status = 'firing'");
        assertThat(sql)
                .contains("create index idx_alerts_service_status on alerts (service_id, status)");
        assertThat(sql)
                .contains("create index idx_alerts_rule_status on alerts (alert_rule_id, status)");
        assertThat(sql)
                .as("ordem fixa da listagem: started_at DESC")
                .contains("create index idx_alerts_started_at_desc on alerts (started_at desc)");
        assertThat(sql)
                .as("backfill do eixo temporal com o melhor aproximado disponível")
                .contains("update services set status_changed_at = updated_at");
    }

    // --- 102: Flyway valida o catálogo + Testcontainers ----------------------

    @Test
    void criterio102_flyway_valida_dez_migracoes_e_containers_sobem() {
        // mesmo caminho do boot: valida checksums dos arquivos contra o histórico
        flyway.validate();

        List<Map<String, Object>> rows = new JdbcTemplate(dataSource).queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");

        assertThat(rows).hasSize(12);
        assertThat(rows)
                .extracting(row -> String.valueOf(row.get("version")))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(rows)
                .as("nenhuma migration falhou (Hibernate ddl-auto=validate no mesmo boot)")
                .extracting(row -> row.get("success"))
                .containsOnly(Boolean.TRUE);

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

    /** Corpo SQL da migration em minúsculas e sem comentários (estrutura, não prosa). */
    private static String bodyOf(String file) throws IOException {
        String full = Files.readString(MIGRATION_DIR.resolve(file)).toLowerCase(Locale.ROOT);
        return full.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest);
    }
}
