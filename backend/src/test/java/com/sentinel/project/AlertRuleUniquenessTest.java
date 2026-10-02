package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sentinel.project.alert.service.AlertRuleService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Unicidade de nome POR SERVIÇO (Prompt 12, Seção 53 — critérios 77–80) contra
 * contexto real.
 *
 * <p>Comparação case-insensitive por {@code name_normalized} (trim +
 * lowercase ROOT); pré-check devolve o 409 imediato e a corrida é absorvida
 * pelo {@code UNIQUE (service_id, name_normalized)} da migration V9 → o MESMO
 * erro em qualquer caminho. O nome continua ocupado mesmo com a regra
 * desativada (desativar não libera) e livre entre serviços distintos.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRuleUniquenessTest extends AbstractAlertRuleIntegrationTest {

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

    // --- 77: duplicata case-insensitive -------------------------------------

    @Test
    void createDuplicateNameCaseInsensitive_returns409() throws Exception {
        Scene scene = newScene("alert-uniq-case", "Org AlertRule UniqCase");
        createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra Vitória");

        // caixa diferente + volta ao redor: mesmo nome normalizado
        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("  REGRA VITÓRIA  "))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_RULE_NAME_DUPLICATE"));

        // nada foi gravado: só a regra original existe
        assertThat(rulesOf(scene.serviceId())).hasSize(1);
    }

    // --- 78: mesmo nome em outro serviço ------------------------------------

    @Test
    void sameNameInOtherService_allowed() throws Exception {
        Scene scene = newScene("alert-uniq-siblings", "Org AlertRule UniqSiblings");
        String sibling = createService(
                scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), "Serviço B");

        String first = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Latência do checkout");
        String second = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                sibling,
                "Latência do checkout");

        assertThat(first).isNotEqualTo(second);
        assertThat(rulesOf(scene.serviceId())).hasSize(1);
        assertThat(rulesOf(sibling)).hasSize(1);
    }

    // --- 79: concorrência ----------------------------------------------------

    @Test
    void concurrentCreateSameName_oneSucceedsOne409() throws Exception {
        Scene scene = newScene("alert-uniq-race", "Org AlertRule UniqRace");
        String url = alertRulesUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());
        String payload = json(serviceLevelRule("Nome Na Corrida"));

        List<Integer> statuses = concurrentPosts(
                new Post(scene.org().ownerBearer(), url, payload),
                new Post(scene.org().ownerBearer(), url, payload));

        // o banco é a última linha de defesa: exatamente UM 201 na corrida
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        // invariante final: o nome existe UMA única vez no serviço
        assertThat(rulesOf(scene.serviceId()))
                .filteredOn(rule -> "nome na corrida".equals(rule.getNameNormalized()))
                .hasSize(1);
    }

    // --- 80: código e mensagem exatos ---------------------------------------

    @Test
    void duplicateErrorCodeIsALERT_RULE_NAME_DUPLICATE() throws Exception {
        Scene scene = newScene("alert-uniq-code", "Org AlertRule UniqCode");
        createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Regra Original");

        MvcResult result = mockMvc.perform(postCsrf(alertRulesUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("regra original"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_RULE_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.message")
                        .value(AlertRuleService.DUPLICATE_NAME_MESSAGE))
                .andReturn();

        assertThat((String) responseBody(result).get("path"))
                .contains("/alert-rules");
    }

    // --- extras (ordem do plano: unicidade também no rename e com inativa) ---

    @Test
    void renameToExistingName_returns409() throws Exception {
        Scene scene = newScene("alert-uniq-rename", "Org AlertRule UniqRename");
        createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Nome Ocupado");
        String other = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Outro Nome");

        mockMvc.perform(patchCsrf(alertRuleUrl(
                                        scene.org().organizationId(), scene.projectId(), scene.serviceId(), other))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "NOME OCUPADO"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_RULE_NAME_DUPLICATE"));

        // o perdedor continua com o nome original (nada foi renomeado às cegas)
        assertThat(alertRuleOf(scene.serviceId(), other).getNameNormalized())
                .isEqualTo("outro nome");
    }

    @Test
    void inactiveRuleStillOccupiesItsName_returns409() throws Exception {
        Scene scene = newScene("alert-uniq-inactive", "Org AlertRule UniqInactive");
        String ruleId = createAlertRule(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Nome Reservado");
        changeAlertRuleActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                ruleId,
                false);

        // desativar NÃO libera o nome — a configuração continua existindo
        mockMvc.perform(postCsrf(alertRulesUrl(
                                scene.org().organizationId(), scene.projectId(), scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(serviceLevelRule("nome reservado"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_RULE_NAME_DUPLICATE"));
    }

    // --- apoio ---------------------------------------------------------------

    /** Regras de um serviço via repositório (estado final após a corrida). */
    private List<com.sentinel.project.alert.model.AlertRule> rulesOf(String serviceId) {
        return alertRuleRepository
                .search(
                        java.util.UUID.fromString(serviceId),
                        null,
                        null,
                        null,
                        null,
                        null,
                        PageRequest.of(0, 100))
                .getContent();
    }

    private record Post(String bearer, String url, String payload) {
    }

    /** Dois POSTs simultâneos — devolve os status (padrão MonitorConcurrencyTest). */
    private List<Integer> concurrentPosts(Post first, Post second) throws Exception {
        // CSRF materializado ANTES das threads (happens-before da leitura cacheada)
        csrf();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>(2);
            for (Post request : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(postCsrf(request.url())
                                    .header(HttpHeaders.AUTHORIZATION, request.bearer())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(request.payload()))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>(2);
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        }
        finally {
            pool.shutdownNow();
        }
    }
}
