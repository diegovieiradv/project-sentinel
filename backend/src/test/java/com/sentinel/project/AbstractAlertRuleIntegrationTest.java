package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertRule;
import com.sentinel.project.alert.repository.AlertRuleRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base dos testes do Prompt 12 (Alert Rules) — reaproveita os helpers do
 * {@code AbstractMonitorIntegrationTest} (fixtures de tenant, URLs, criação de
 * projeto/serviço/monitor e CSRF real) e acrescenta o vocabulário de regra:
 * URL de rota, criação via API, ativação/desativação e leitura direta do
 * repositório.
 *
 * <p>Assim como as bases anteriores, esta classe <b>não</b> tem anotações
 * Spring nem ciclo de vida: cada subclasse declara o seu próprio
 * {@code @SpringBootTest}/{@ServiceConnection}/container (uma classe de
 * contexto por container).</p>
 *
 * <p><b>Condition builder:</b> {@link #serviceLevelRule(String, String)} e
 * {@link #monitorLevelRule(String, String)} montam um POST válido mínimo
 * (AVAILABILITY &lt; 99.9% em 15m, SEV3) — os testes só sobrepõem o campo que
 * estão exercitando, em vez de repetir o mapa inteiro em cada caso.</p>
 */
public abstract class AbstractAlertRuleIntegrationTest extends AbstractMonitorIntegrationTest {

    @Autowired
    protected AlertRuleRepository alertRuleRepository;

    // --- URLs ---------------------------------------------------------------

    protected String alertRulesUrl(String organizationId, String projectId, String serviceId) {
        return serviceUrl(organizationId, projectId, serviceId) + "/alert-rules";
    }

    protected String alertRuleUrl(
            String organizationId, String projectId, String serviceId, String ruleId) {
        return alertRulesUrl(organizationId, projectId, serviceId) + "/" + ruleId;
    }

    protected String alertRuleActivationUrl(
            String organizationId, String projectId, String serviceId, String ruleId) {
        return alertRuleUrl(organizationId, projectId, serviceId, ruleId) + "/activation";
    }

    // --- payloads -----------------------------------------------------------

    /** Payload mínimo VÁLIDO Service-level (AVAILABILITY &lt; 99.9, 15m, SEV3). */
    protected Map<String, Object> serviceLevelRule(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", "AVAILABILITY");
        body.put("operator", "LESS_THAN");
        body.put("thresholdNumeric", 99.9);
        body.put("window", "FIFTEEN_MINUTES");
        body.put("severity", "SEV3");
        return body;
    }

    /** Payload mínimo VÁLIDO Monitor-level (mesma condição, com {@code monitorId}). */
    protected Map<String, Object> monitorLevelRule(String name, String monitorId) {
        Map<String, Object> body = serviceLevelRule(name);
        body.put("monitorId", monitorId);
        return body;
    }

    // --- criação via API ----------------------------------------------------

    /** POST → 201, devolve o id, com o payload montado pelo teste. */
    protected String createAlertRule(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            Map<String, Object> body)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        postCsrf(alertRulesUrl(organizationId, projectId, serviceId))
                                .header(HttpHeaders.AUTHORIZATION, bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = (String) responseBody(result).get("id");
        assertThat(id).as("201 must expose the alert rule id").isNotBlank();
        return id;
    }

    /** POST Service-level canônico → 201. */
    protected String createAlertRule(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            String name)
            throws Exception {
        return createAlertRule(
                bearer,
                organizationId,
                projectId,
                serviceId,
                serviceLevelRule(name));
    }

    // --- ativação -----------------------------------------------------------

    /** PATCH .../activation → 200 (controla o estado nos testes de filtro/RBAC). */
    protected void changeAlertRuleActivation(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            String ruleId,
            boolean active)
            throws Exception {
        mockMvc.perform(
                        patchCsrf(
                                        alertRuleActivationUrl(
                                                organizationId, projectId, serviceId, ruleId))
                                .header(HttpHeaders.AUTHORIZATION, bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", active))))
                .andExpect(status().isOk());
    }

    // --- leitura persistida -------------------------------------------------

    /** Regra via repositório (estado final depois de um 400/409/concorrência). */
    protected AlertRule alertRuleOf(String serviceId, String ruleId) {
        return alertRuleRepository
                .findByServiceIdAndId(UUID.fromString(serviceId), UUID.fromString(ruleId))
                .orElseThrow();
    }
}
