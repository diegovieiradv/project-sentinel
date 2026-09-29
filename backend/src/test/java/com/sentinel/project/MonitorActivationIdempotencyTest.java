package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Endpoint de ativação ({@code PATCH .../monitors/{id}/activation}) —
 * soft-delete e idempotência (Prompt 08).
 *
 * <p>Contratos travados aqui: (1) ativar/desativar é <b>idempotente</b> —
 * repetir o estado atual devolve 200 SEM escrita, o {@code updatedAt} não
 * avança; (2) a rota mexe <b>somente</b> em {@code active} — nome, alvo,
 * método, intervalo, timeout e o {@code type=HTTP} invariante do cadastro
 * ficam intactos, e o {@code PATCH} comum também não consegue forçar
 * {@code active} nem {@code type} por payload; (3) entrada malformada,
 * ausente ou nula é 400 com o shape padrão — nunca um {@code false} implícito
 * vindo de body ausente; (4) id desconhecido/malformado é 404 genérico, nunca
 * 500. A revalidação SSRF da reativação e o no-op sem revalidação ficam no
 * {@code MonitorSsrfTest}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitorActivationIdempotencyTest extends AbstractMonitorIntegrationTest {

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

    @Test
    void deactivateAndReactivateRoundTripPersistsInDatabase() throws Exception {
        Scene scene = newScene("mon-act-roundtrip", "Mon Roundtrip Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Ciclo");
        String url = monitorActivationUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(monitorId))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("Ciclo"));

        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isFalse();

        // soft-delete: o recurso continua legível e NÃO some da listagem
        mockMvc.perform(get(monitorUrl(
                                scene.org().organizationId(),
                                scene.projectId(),
                                scene.serviceId(),
                                monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get(monitorsUrl(
                                scene.org().organizationId(),
                                scene.projectId(),
                                scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isTrue();
    }

    @Test
    void repeatingCurrentStateReturns200WithoutTouchingUpdatedAt() throws Exception {
        Scene scene = newScene("mon-act-idempotent", "Mon Idempotent Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Estável");
        String url = monitorActivationUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        String updatedAtBefore = updatedAtOf(scene, monitorId);

        // repetir o estado atual é um no-op: 200, mas SEM escrita — o
        // @PreUpdate não dispara e o timestamp não avança
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.updatedAt").value(updatedAtBefore));

        assertThat(updatedAtOf(scene, monitorId)).isEqualTo(updatedAtBefore);

        // idem para o estado oposto repetido
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        String deactivationStamp = updatedAtOf(scene, monitorId);
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(updatedAtOf(scene, monitorId)).isEqualTo(deactivationStamp);
    }

    @Test
    void activationNeverTouchesConfigurationOrServerOwnedFields() throws Exception {
        Scene scene = newScene("mon-act-config-parity", "Mon Config Parity Corp");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Intocável");
        body.put("url", PUBLIC_URL);
        body.put("method", "HEAD");
        body.put("intervalSeconds", 120);
        body.put("timeoutMillis", 1500);
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                body);
        String url = monitorActivationUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        String createdAtBefore = String.valueOf(
                monitorOf(scene.serviceId(), monitorId).getCreatedAt());

        // desligar o cadastro NÃO reconfigura a checagem: alvo, método,
        // intervalo, timeout, tipo e nome ficam exatamente como foram criados
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("Intocável"))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL))
                .andExpect(jsonPath("$.method").value("HEAD"))
                .andExpect(jsonPath("$.intervalSeconds").value(120))
                .andExpect(jsonPath("$.timeoutMillis").value(1500))
                .andExpect(jsonPath("$.type").value("HTTP"));

        Monitor persisted = monitorOf(scene.serviceId(), monitorId);
        assertThat(persisted.isActive()).isFalse();
        assertThat(persisted.getType().name()).isEqualTo("HTTP");
        assertThat(persisted.getNameNormalized()).isEqualTo("intocável");
        assertThat(String.valueOf(persisted.getCreatedAt())).isEqualTo(createdAtBefore);

        // e a rota de atualização também não consegue forçar active nem type
        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Intocável Renomeado",
                                "active", true,
                                "type", "PING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Intocável Renomeado"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.type").value("HTTP"));
    }

    @Test
    void activationWithMissingOrNullActiveReturns400NotNull() throws Exception {
        Scene scene = newScene("mon-act-null", "Mon Activation Null Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Alvo");
        String url = monitorActivationUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        // body vazio de objeto: Boolean null + @NotNull → o servidor NÃO
        // adivinha um "false" implícito
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("active: must not be null"));

        // {"active": null} explícito → o MESMO 400
        Map<String, Object> nullBody = new LinkedHashMap<>();
        nullBody.put("active", null);
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(nullBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("active: must not be null"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", ""))))
                .andExpect(status().isBadRequest());

        // e nada foi alterado
        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isTrue();
    }

    @Test
    void activationWithMalformedBodyReturns400Never500() throws Exception {
        Scene scene = newScene("mon-act-malformed", "Mon Activation Malformed Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Intocável");
        String url = monitorActivationUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        // corpo inexistente: JSON ilegível → 400 genérico de entrada
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        // tipo incompatível (objeto onde se espera boolean) → o mesmo 400 de
        // corpo ilegível, nunca 500 nem coerção silenciosa
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", Map.of("nested", true)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isTrue();
    }

    @Test
    void activationOfUnknownMonitorReturns404Generic() throws Exception {
        Scene scene = newScene("mon-act-404", "Mon Activation 404 Corp");

        mockMvc.perform(patchCsrf(monitorActivationUrl(
                                scene.org().organizationId(),
                                scene.projectId(),
                                scene.serviceId(),
                                UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void activationOfMalformedMonitorIdReturns404Not500() throws Exception {
        Scene scene = newScene("mon-act-malformed-id", "Mon Activation Malformed Id Corp");

        mockMvc.perform(patchCsrf(monitorActivationUrl(
                                scene.org().organizationId(),
                                scene.projectId(),
                                scene.serviceId(),
                                "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- apoio ----------------------------------------------------------------

    private String updatedAtOf(Scene scene, String monitorId) throws Exception {
        MvcResult result = mockMvc.perform(
                        get(monitorUrl(
                                scene.org().organizationId(),
                                scene.projectId(),
                                scene.serviceId(),
                                monitorId))
                                .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andReturn();
        Object updatedAt = responseBody(result).get("updatedAt");
        assertThat(updatedAt).isNotNull();
        return String.valueOf(updatedAt);
    }
}
