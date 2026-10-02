package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.alert.model.Alert;
import com.sentinel.project.monitor.model.CheckOutcome;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contrato HTTP da API somente-leitura de alertas (Prompt 13) — critérios
 * 84–96 contra contexto real, mais o critério OFICIAL 84 (401 anônimo na
 * rota — a numeração local 84–96 é a reconstruída, ver
 * {@code docs/alert-engine.md} §1.1).
 *
 * <p>Página no formato Spring Page com ordem fixa
 * {@code startedAt DESC, id DESC}, filtros tipados status/severity (enum fora
 * do contrato → 400 genérico), paginação validada (page≥0, size 1..100),
 * detalhe completo com SNAPSHOT da regra, anti-IDOR com o MESMO 404
 * genérico, leitura liberada para qualquer papel (viewer inclusive), 401 sem
 * token e os três verbos de escrita — POST, PATCH e DELETE — respondendo 405
 * no shape global {@code {code, message, timestamp, path}} (com CSRF válido,
 * para o 405 ser empírico e não uma máscara de 403).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedMetricsClockConfig.class)
class AlertApiTest extends AbstractAlertEngineIntegrationTest {

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

    // --- cenário: serviço com UM alerta disparado ----------------------------

    private Scene sceneWithAlert(String prefix) throws Exception {
        Scene scene = newScene(prefix, "Org AlertApi " + prefix);
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor da API");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra da API");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());
        assertThat(singleAlertOf(scene.serviceId()).getStatus())
                .isEqualTo(com.sentinel.project.alert.model.AlertStatus.FIRING);
        return scene;
    }

    // --- 401 anônimo (critério OFICIAL 84; o local 84 é o shape da página) --

    /**
     * Critério oficial 84 (correção da revisão P2-3): a rota de alertas exige
     * Bearer — GET sem token responde 401 na listagem E no detalhe, nunca
     * 200/404/403 mascarando a ausência de credencial.
     */
    @Test
    void criterio84_oficial_listagem_sem_token_retorna_401() throws Exception {
        Scene scene = sceneWithAlert("alert-api-anon");

        mockMvc.perform(get(alertsUrl(
                        scene.org().organizationId(), scene.projectId(), scene.serviceId())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(alertUrl(
                        scene.org().organizationId(), scene.projectId(), scene.serviceId(),
                        singleAlertOf(scene.serviceId()).getId().toString())))
                .andExpect(status().isUnauthorized());
    }

    // --- #84: shape da página ------------------------------------------------

    @Test
    void criterio84_listagem_retorna_pagina_spring_com_alerta_firing() throws Exception {
        Scene scene = sceneWithAlert("alert-api-page");

        mockMvc.perform(get(alertsUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].status").value("FIRING"))
                .andExpect(jsonPath("$.content[0].ruleName").value("Regra da API"))
                .andExpect(jsonPath("$.content[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.content[0].resolutionReason").doesNotExist());
    }

    // --- #85: ordem fixa startedAt DESC, id DESC -----------------------------

    @Test
    void criterio85_listagem_ordena_por_started_at_desc_e_id_desc() throws Exception {
        Scene scene = newScene("alert-api-order", "Org AlertApi Order");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor da ordem");
        String ruleA = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra A");
        String ruleB = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra B");
        String ruleC = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Regra C");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());

        Map<String, UUID> byRule = new LinkedHashMap<>();
        for (Alert alert : alertsOf(scene.serviceId())) {
            byRule.put(alert.getAlertRule().getId().toString(), alert.getId());
        }
        assertThat(byRule).containsOnlyKeys(ruleA, ruleB, ruleC);

        // relógio congelado ⇒ mesmos startedAt: quebramos o empate 2+1 e o
        // desempate id DESC entre B e C
        setStartedAt(byRule.get(ruleA), NOW.minusSeconds(30));
        setStartedAt(byRule.get(ruleB), NOW.minusSeconds(10));
        setStartedAt(byRule.get(ruleC), NOW.minusSeconds(10));
        UUID newer = compareAsUuidBytes(byRule.get(ruleB), byRule.get(ruleC)) > 0
                ? byRule.get(ruleB)
                : byRule.get(ruleC);
        UUID older = newer.equals(byRule.get(ruleB)) ? byRule.get(ruleC) : byRule.get(ruleB);

        MvcResult result = mockMvc.perform(get(alertsUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andReturn();

        List<String> ids = extractIds(result);
        assertThat(ids)
                .as("startedAt DESC; empate no mesmo instante → id DESC")
                .containsExactly(newer.toString(), older.toString(), byRule.get(ruleA).toString());
    }

    // --- #86: filtro por status ----------------------------------------------

    @Test
    void criterio86_filtro_por_status_separa_firing_de_resolved() throws Exception {
        Scene scene = newScene("alert-api-status", "Org AlertApi Status");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor do filtro");
        String openRule = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Fica aberta");
        String closedRule = createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Fecha na desativação");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());
        changeAlertRuleActivation(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), closedRule, false);

        String url = alertsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url + "?status=FIRING")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].alertRuleId").value(openRule))
                .andExpect(jsonPath("$.content[0].status").value("FIRING"));

        mockMvc.perform(get(url + "?status=RESOLVED")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].alertRuleId").value(closedRule))
                .andExpect(jsonPath("$.content[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.content[0].resolutionReason").value("RULE_DEACTIVATED"));
    }

    // --- #87: filtro por severidade ------------------------------------------

    @Test
    void criterio87_filtro_por_severidade_usa_o_snapshot_do_alerta() throws Exception {
        Scene scene = newScene("alert-api-severity", "Org AlertApi Severity");
        String monitorId = createMonitor(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Monitor da severidade");
        Map<String, Object> critical = serviceLevelRule("Crítica");
        critical.put("severity", "SEV1");
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), critical);
        createAlertRule(
                scene.org().ownerBearer(), scene.org().organizationId(),
                scene.projectId(), scene.serviceId(), "Rotineira (SEV3)");
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.SUCCESS, 1);
        seedWindow(scene.serviceId(), monitorId, CheckOutcome.TARGET_FAILURE, 1);
        evaluate(scene.serviceId());

        String url = alertsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url + "?severity=SEV1")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ruleName").value("Crítica"))
                .andExpect(jsonPath("$.content[0].severity").value("SEV1"));

        mockMvc.perform(get(url + "?severity=SEV4")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // --- #88: enum fora do contrato → 400 genérico ---------------------------

    @Test
    void criterio88_enum_invalido_em_status_ou_severity_retorna_400() throws Exception {
        Scene scene = sceneWithAlert("alert-api-badenum");
        String url = alertsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url + "?status=BOOM")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid request parameter"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").isNotEmpty());

        mockMvc.perform(get(url + "?severity=EXTREME")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").isNotEmpty());
    }

    // --- #89: paginação invalida → 400 ---------------------------------------

    @Test
    void criterio89_paginacao_invalida_retorna_400_com_mensagem_canonica() throws Exception {
        Scene scene = sceneWithAlert("alert-api-paging");
        String url = alertsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url + "?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("page: must not be negative"));

        mockMvc.perform(get(url + "?size=101")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));
    }

    // --- #90: shape do detalhe -----------------------------------------------

    @Test
    void criterio90_detalhe_exposta_snapshot_completo_do_alerta() throws Exception {
        Scene scene = sceneWithAlert("alert-api-detail");
        Alert alert = singleAlertOf(scene.serviceId());

        MvcResult result = mockMvc.perform(get(alertUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), alert.getId().toString()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alert.getId().toString()))
                .andExpect(jsonPath("$.organizationId").value(scene.org().organizationId()))
                .andExpect(jsonPath("$.projectId").value(scene.projectId()))
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()))
                .andExpect(jsonPath("$.status").value("FIRING"))
                .andExpect(jsonPath("$.severity").value("SEV3"))
                .andExpect(jsonPath("$.ruleName").value("Regra da API"))
                .andExpect(jsonPath("$.ruleType").value("AVAILABILITY"))
                .andExpect(jsonPath("$.ruleOperator").value("LESS_THAN"))
                .andExpect(jsonPath("$.ruleThresholdNumeric").value(99.9))
                .andExpect(jsonPath("$.ruleThresholdStatus").doesNotExist())
                .andExpect(jsonPath("$.ruleWindow").value("FIFTEEN_MINUTES"))
                .andExpect(jsonPath("$.monitorId").doesNotExist())
                .andExpect(jsonPath("$.resolutionReason").doesNotExist())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.lastEvaluatedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.startedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        // contrato EXATO do record — nada além dos 23 campos
        assertThat(responseBody(result)).containsOnlyKeys(
                "id", "organizationId", "projectId", "serviceId", "alertRuleId", "monitorId",
                "ruleName", "status", "severity", "resolutionReason", "startedAt", "resolvedAt",
                "lastEvaluatedAt", "triggerValue", "message", "ruleType", "ruleOperator",
                "ruleThresholdNumeric", "ruleThresholdStatus", "ruleWindow", "createdAt",
                "updatedAt", "role");

        Map<String, Object> body = responseBody(result);
        assertThat(body.get("alertRuleId")).isNotNull();
        assertThat(body.get("triggerValue")).isNotNull();
        assertThat(body.get("message").toString())
                .as("mensagem do FIRING = nome da regra + detalhe avaliado")
                .startsWith("Regra da API: ")
                .contains("availability")
                .contains("over 15 minutes");
    }

    // --- #91: detalhe inexistente → 404 --------------------------------------

    @Test
    void criterio91_detalhe_de_alerta_inexistente_retorna_404() throws Exception {
        Scene scene = sceneWithAlert("alert-api-missing");

        mockMvc.perform(get(alertUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- #92: UUID malformado → MESMO 404 anti-enumeração --------------------

    @Test
    void criterio92_id_malformado_no_path_retorna_404_nunca_500() throws Exception {
        Scene scene = sceneWithAlert("alert-api-uuid");
        String url = alertsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url + "/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- #93: anti-IDOR ------------------------------------------------------

    @Test
    void criterio93_alerta_ou_servico_de_outro_tenant_retorna_404() throws Exception {
        Scene alpha = sceneWithAlert("alert-api-idor-alpha");
        String alphaAlertId = singleAlertOf(alpha.serviceId()).getId().toString();

        Scene beta = sceneWithAlert("alert-api-idor-beta");
        String betaAlertId = singleAlertOf(beta.serviceId()).getId().toString();

        // (a) dono de Alpha batendo no SERVIÇO de Beta → 404 (não-membro)
        mockMvc.perform(get(alertsUrl(
                                        beta.org().organizationId(), beta.projectId(), beta.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, alpha.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // (b) alerta de Beta dentro do serviço de Alpha → 404 (fora do serviço)
        mockMvc.perform(get(alertUrl(
                                        alpha.org().organizationId(), alpha.projectId(),
                                        alpha.serviceId(), betaAlertId))
                        .header(HttpHeaders.AUTHORIZATION, alpha.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // controle: o alerta PRÓPRIO continua legível
        mockMvc.perform(get(alertUrl(
                                        alpha.org().organizationId(), alpha.projectId(),
                                        alpha.serviceId(), alphaAlertId))
                        .header(HttpHeaders.AUTHORIZATION, alpha.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alphaAlertId));
    }

    // --- #94: leitura liberada para qualquer papel ---------------------------

    @Test
    void criterio94_viewer_le_lista_e_detalhe_com_200() throws Exception {
        Scene scene = sceneWithAlert("alert-api-rbac");
        String alertId = singleAlertOf(scene.serviceId()).getId().toString();

        String viewerEmail = uniqueEmail("alert-viewer");
        String viewer = registerAndLogin("Viewer Vera", viewerEmail);
        addMember(
                scene.org().ownerBearer(),
                scene.org().organizationId(), viewerEmail, "VIEWER");

        String url = alertsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("VIEWER"));

        mockMvc.perform(get(url + "/" + alertId)
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alertId))
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    // --- #95: POST → 405 -----------------------------------------------------

    @Test
    void criterio95_post_na_colecao_retorna_405() throws Exception {
        Scene scene = newScene("alert-api-post", "Org AlertApi Post");
        String url = alertsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "FIRING"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method POST not allowed"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").isNotEmpty());

        // nada foi criado
        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // --- #96: PATCH e DELETE → 405 -------------------------------------------

    @Test
    void criterio96_patch_e_delete_retornam_405() throws Exception {
        Scene scene = sceneWithAlert("alert-api-verbs");
        String collection = alertsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId());
        String detail = collection + "/" + singleAlertOf(scene.serviceId()).getId();
        String bearer = scene.org().ownerBearer();

        mockMvc.perform(patchCsrf(collection)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "RESOLVED"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method PATCH not allowed"));

        mockMvc.perform(deleteCsrf(collection)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method DELETE not allowed"));

        mockMvc.perform(deleteCsrf(detail)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        // o histórico permanece intacto após os três verbos negados
        assertThat(singleAlertOf(scene.serviceId()).getStatus())
                .isEqualTo(com.sentinel.project.alert.model.AlertStatus.FIRING);
    }

    // --- helpers -------------------------------------------------------------

    /**
     * uuid_cmp do Postgres: 16 bytes SEM sinal (memcmp), diferente do
     * {@link UUID#compareTo} do Java — que trata os 128 bits como COMPLEMENTO
     * DE DOIS e inverte a ordem quando o primeiro byte tem o bit alto ligado
     * (ex.: {@code 0xe6...} vs {@code 0x05...}).
     */
    private static int compareAsUuidBytes(UUID a, UUID b) {
        byte[] left = uuidBytes(a);
        byte[] right = uuidBytes(b);
        for (int i = 0; i < left.length; i++) {
            int cmp = Integer.compare(left[i] & 0xff, right[i] & 0xff);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static byte[] uuidBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (56 - 8 * i));
            bytes[8 + i] = (byte) (lsb >>> (56 - 8 * i));
        }
        return bytes;
    }

    private void setStartedAt(UUID alertId, Instant startedAt) {
        jdbcTemplate.update(
                "UPDATE alerts SET started_at = ? WHERE id = ?",
                Timestamp.from(startedAt), alertId);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractIds(MvcResult result) throws Exception {
        Map<String, Object> body = responseBody(result);
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        return content.stream().map(item -> (String) item.get("id")).toList();
    }
}
