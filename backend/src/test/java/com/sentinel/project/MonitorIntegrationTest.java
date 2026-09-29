package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;

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
 * Módulo Monitors (Prompt 08) contra contexto real — quarto nível da
 * hierarquia Organization → Project → Service → Monitor.
 *
 * <p>Cobertura: create (contrato do {@code 201}, trim, validações de bean e de
 * domínio, unicidade POR SERVIÇO em duas camadas), list (paginação, ordenação,
 * filtros {@code active}/{@code method}/{@code name}, escopo do serviço), get
 * (200/404 genérico), PATCH (parcial, rename com unicidade), ativação
 * (idempotente, fail-safe), massa de atribuição, pais inativos
 * ({@code 409 PROJECT_INACTIVE} × {@code 409 SERVICE_INACTIVE}), matriz RBAC e
 * os bloqueios 401/403/404/405. IDOR, idempotência de ativação, concorrência e
 * a proteção SSRF ficam nas classes próprias
 * ({@code MonitorIdorAdversarialTest}, {@code MonitorActivationIdempotencyTest},
 * {@code MonitorConcurrencyTest} e {@code MonitorSsrfTest}).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitorIntegrationTest extends AbstractMonitorIntegrationTest {

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

    // --- POST: caminho feliz ------------------------------------------------

    @Test
    void createReturns201WithServerOwnedFieldsAndTenantScopedContract() throws Exception {
        Fixture org = newOrganization(uniqueEmail("mon-owner-create"), "Mon Create Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Backend");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "API");

        MvcResult result = mockMvc.perform(postCsrf(monitorsUrl(
                                        org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Health Check", "url", PUBLIC_URL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").value(org.organizationId()))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.serviceId").value(serviceId))
                .andExpect(jsonPath("$.name").value("Health Check"))
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.intervalSeconds").value(60))
                .andExpect(jsonPath("$.timeoutMillis").value(5000))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        Map<String, Object> body = responseBody(result);
        assertThat(body).doesNotContainKey("nameNormalized");

        Monitor persisted = monitorOf(serviceId, (String) body.get("id"));
        assertThat(persisted.getNameNormalized()).isEqualTo("health check");
        assertThat(persisted.getType().name()).isEqualTo("HTTP");
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getService().getId().toString()).isEqualTo(serviceId);
        assertThat(persisted.getService().getProject().getId().toString()).isEqualTo(projectId);
    }

    @Test
    void createTrimsNameAndPersistsTrimmedValue() throws Exception {
        Scene scene = newScene("mon-owner-trim", "Mon Trim Corp");

        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   Padded   ", "url", PUBLIC_URL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Padded"));
    }

    // --- POST: validação de entrada (nunca 500) -----------------------------

    @Test
    void createWithBlankOrMissingNameReturns400NotBlank() throws Exception {
        Scene scene = newScene("mon-owner-blank", "Mon Blank Corp");
        String url = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        // o @Valid do controller roda ANTES de tudo no create: um name em
        // branco responde 400 mesmo com o guard SSRF e o domínio por perto
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   ", "url", PUBLIC_URL))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("name: must not be blank"));

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("url", PUBLIC_URL))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name: must not be blank"));
    }

    @Test
    void createWithNameOver120CharsReturns400And120CharsSucceeds() throws Exception {
        Scene scene = newScene("mon-owner-len", "Mon Len Corp");
        String url = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "x".repeat(121), "url", PUBLIC_URL))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name: must be at most 120 characters"));

        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "y".repeat(120), "url", PUBLIC_URL))))
                .andExpect(status().isCreated());
    }

    @Test
    void createWithMalformedJsonReturns400Never500() throws Exception {
        Scene scene = newScene("mon-owner-malformed", "Mon Malformed Corp");

        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void createWithoutAuthenticationReturns401() throws Exception {
        Scene scene = newScene("mon-owner-noauth", "Mon NoAuth Corp");

        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Auth", "url", PUBLIC_URL))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void createInUnknownProjectReturns404Generic() throws Exception {
        Fixture org = newOrganization(uniqueEmail("mon-owner-404"), "Mon 404 Corp");

        mockMvc.perform(postCsrf(monitorsUrl(
                                        org.organizationId(),
                                        UUID.randomUUID().toString(),
                                        UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Órfão", "url", PUBLIC_URL))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- POST: unicidade por serviço (duas camadas) -------------------------

    @Test
    void createDuplicateNameInSameServiceReturns409WithStableContract() throws Exception {
        Scene scene = newScene("mon-owner-dup", "Mon Dup Corp");
        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Payments API");

        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  PAYMENTS API  ", "url", PUBLIC_URL))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITOR_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.message").value(
                        "Monitor with this name already exists in this service"));
    }

    @Test
    void createDuplicateOfDeactivatedMonitorStillReturns409() throws Exception {
        Scene scene = newScene("mon-owner-dup-off", "Mon Dup Off Corp");
        String ghost = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Ghost");
        changeMonitorActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                ghost,
                false);

        // desativar NÃO libera o nome: o soft-delete mantém a unicidade
        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ghost", "url", PUBLIC_URL))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITOR_NAME_DUPLICATE"));
    }

    @Test
    void createSameNameInDifferentServicesReturns201ForBoth() throws Exception {
        Fixture org = newOrganization(uniqueEmail("mon-owner-same"), "Mon Same Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Pagamentos");
        String first = createService(org.ownerBearer(), org.organizationId(), projectId, "API Um");
        String second = createService(org.ownerBearer(), org.organizationId(), projectId, "API Dois");

        // unicidade é POR serviço: o mesmo nome em serviços distintos é
        // cenário legítimo, não conflito
        createMonitor(org.ownerBearer(), org.organizationId(), projectId, first, "Health Check");
        createMonitor(org.ownerBearer(), org.organizationId(), projectId, second, "Health Check");

        mockMvc.perform(get(monitorsUrl(org.organizationId(), projectId, second))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Health Check"))
                .andExpect(jsonPath("$.content[0].serviceId").value(second));
    }

    // --- mass assignment ----------------------------------------------------

    @Test
    void createIgnoresServerOwnedFieldsSentInPayload() throws Exception {
        Scene scene = newScene("mon-owner-mass", "Mon Mass Corp");
        String attackerId = UUID.randomUUID().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Mass Assigned");
        payload.put("url", PUBLIC_URL);
        payload.put("id", attackerId);
        payload.put("organizationId", "00000000-0000-0000-0000-0000000000ff");
        payload.put("projectId", "00000000-0000-0000-0000-0000000000ee");
        payload.put("serviceId", "00000000-0000-0000-0000-0000000000dd");
        payload.put("type", "PING");
        payload.put("active", false);
        payload.put("nameNormalized", "hacked");
        payload.put("createdAt", "2000-01-01T00:00:00Z");
        payload.put("updatedAt", "2000-01-01T00:00:00Z");

        MvcResult result = mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()))
                .andExpect(jsonPath("$.projectId").value(scene.projectId()))
                .andExpect(jsonPath("$.organizationId").value(scene.org().organizationId()))
                .andReturn();

        Map<String, Object> body = responseBody(result);
        assertThat(body.get("id")).isNotEqualTo(attackerId);
        assertThat(body.get("createdAt")).isNotEqualTo("2000-01-01T00:00:00Z");

        Monitor persisted = monitorOf(scene.serviceId(), (String) body.get("id"));
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getType().name()).isEqualTo("HTTP");
        assertThat(persisted.getNameNormalized()).isEqualTo("mass assigned");
        assertThat(persisted.getCreatedAt()).isAfter(java.time.Instant.parse("2001-01-01T00:00:00Z"));
    }

    // --- GET: detalhe -------------------------------------------------------

    @Test
    void getReturns200WithFullMonitor() throws Exception {
        Scene scene = newScene("mon-owner-get", "Mon Get Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Relatórios");

        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(monitorId))
                .andExpect(jsonPath("$.organizationId").value(scene.org().organizationId()))
                .andExpect(jsonPath("$.projectId").value(scene.projectId()))
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()))
                .andExpect(jsonPath("$.name").value("Relatórios"))
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.intervalSeconds").value(60))
                .andExpect(jsonPath("$.timeoutMillis").value(5000))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.nameNormalized").doesNotExist());
    }

    @Test
    void getUnknownMonitorReturns404Generic() throws Exception {
        Scene scene = newScene("mon-owner-get-404", "Mon Get 404 Corp");

        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void getWithMalformedIdsReturns404Not500() throws Exception {
        Scene scene = newScene("mon-owner-get-mal", "Mon Get Mal Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Um");

        // monitorId malformado → 404 genérico (parse manual, nunca 500/400)
        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // projectId malformado → o mesmo 404
        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        "not-a-uuid",
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // serviceId malformado → o mesmo 404
        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        "not-a-uuid",
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- GET: listagem ------------------------------------------------------

    @Test
    void listReturnsStablePagedShapeWithDeterministicOrder() throws Exception {
        Fixture org = newOrganization(uniqueEmail("mon-owner-list"), "Mon List Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Lista");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "API");
        String first = createMonitor(org.ownerBearer(), org.organizationId(), projectId, serviceId, "Alpha");
        String second = createMonitor(org.ownerBearer(), org.organizationId(), projectId, serviceId, "Beta");
        String third = createMonitor(org.ownerBearer(), org.organizationId(), projectId, serviceId, "Gama");

        MvcResult result = mockMvc.perform(get(monitorsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) responseBody(result).get("content");
        // padrão canônico: createdAt asc com id asc como desempate
        assertThat(content)
                .extracting(item -> item.get("id"))
                .containsExactly(first, second, third);
        assertThat(content).allSatisfy(item -> {
            assertThat(item.get("serviceId")).isEqualTo(serviceId);
            assertThat(item.get("projectId")).isEqualTo(projectId);
            assertThat(item.get("organizationId")).isEqualTo(org.organizationId());
            assertThat(item.get("type")).isEqualTo("HTTP");
            assertThat(item.get("nameNormalized")).isNull();
        });
    }

    @Test
    void listPaginatesWithSizeAndPage() throws Exception {
        Scene scene = newScene("mon-owner-page", "Mon Page Corp");
        createMonitor(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Um");
        createMonitor(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Dois");
        createMonitor(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Três");
        String url = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url + "?page=0&size=2")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));

        mockMvc.perform(get(url + "?page=1&size=2")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void listIsScopedToItsServiceOnly() throws Exception {
        Fixture org = newOrganization(uniqueEmail("mon-owner-scope"), "Mon Scope Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Escopo");
        String first = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Um");
        String second = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Dois");
        Fixture other = newOrganization(uniqueEmail("mon-owner-scope-other"), "Mon Scope Other");
        String otherProject = createProject(other.ownerBearer(), other.organizationId(), "Outro");
        String otherService = createService(other.ownerBearer(), other.organizationId(), otherProject, "Serviço Alheio");

        createMonitor(org.ownerBearer(), org.organizationId(), projectId, first, "Health Check");
        createMonitor(org.ownerBearer(), org.organizationId(), projectId, second, "Health Check");
        createMonitor(other.ownerBearer(), other.organizationId(), otherProject, otherService, "Health Check");

        // mesmo com o MESMO nome, a listagem é por serviço — nunca um
        // agregado global de monitores
        mockMvc.perform(get(monitorsUrl(org.organizationId(), projectId, first))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].serviceId").value(first));

        mockMvc.perform(get(monitorsUrl(org.organizationId(), projectId, second))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].serviceId").value(second));

        mockMvc.perform(get(monitorsUrl(other.organizationId(), otherProject, otherService))
                        .header(HttpHeaders.AUTHORIZATION, other.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listFiltersByActiveFlag() throws Exception {
        Scene scene = newScene("mon-owner-filter", "Mon Filter Corp");
        createMonitor(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), scene.serviceId(), "Ativo Um");
        String off = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Desligado");
        changeMonitorActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                off,
                false);
        String url = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        // sem filtro: soft-delete NÃO some da listagem (a exclusão não existe)
        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get(url + "?active=true")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Ativo Um"));

        mockMvc.perform(get(url + "?active=false")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Desligado"))
                .andExpect(jsonPath("$.content[0].active").value(false));
    }

    @Test
    void listFiltersByMethod() throws Exception {
        Scene scene = newScene("mon-owner-method", "Mon Method Corp");
        String bearer = scene.org().ownerBearer();
        String projectId = scene.projectId();
        String serviceId = scene.serviceId();
        createMonitor(bearer, scene.org().organizationId(), projectId, serviceId, "Sonda GET");

        Map<String, Object> head = new LinkedHashMap<>();
        head.put("name", "Sonda HEAD");
        head.put("url", PUBLIC_URL);
        head.put("method", "HEAD");
        createMonitor(bearer, scene.org().organizationId(), projectId, serviceId, head);

        String url = monitorsUrl(scene.org().organizationId(), projectId, serviceId);

        mockMvc.perform(get(url + "?method=GET")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Sonda GET"))
                .andExpect(jsonPath("$.content[0].method").value("GET"));

        mockMvc.perform(get(url + "?method=HEAD")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Sonda HEAD"))
                .andExpect(jsonPath("$.content[0].method").value("HEAD"));

        // combinação de filtros: method × active × name (8 variantes de query)
        mockMvc.perform(get(url + "?method=HEAD&active=true&name=sonda")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Sonda HEAD"));
    }

    @Test
    void listSearchesByNameCaseInsensitiveAndSubstring() throws Exception {
        Scene scene = newScene("mon-owner-search", "Mon Search Corp");
        String bearer = scene.org().ownerBearer();
        String projectId = scene.projectId();
        String serviceId = scene.serviceId();
        createMonitor(bearer, scene.org().organizationId(), projectId, serviceId, "Payments API");
        createMonitor(bearer, scene.org().organizationId(), projectId, serviceId, "payments worker");
        createMonitor(bearer, scene.org().organizationId(), projectId, serviceId, "Frontend App");

        mockMvc.perform(get(monitorsUrl(scene.org().organizationId(), projectId, serviceId)
                                + "?name=PAYMENTS")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Payments API"))
                .andExpect(jsonPath("$.content[1].name").value("payments worker"));
    }

    @Test
    void listSortsByNameDescending() throws Exception {
        Scene scene = newScene("mon-owner-sort", "Mon Sort Corp");
        String bearer = scene.org().ownerBearer();
        String projectId = scene.projectId();
        String serviceId = scene.serviceId();
        createMonitor(bearer, scene.org().organizationId(), projectId, serviceId, "Aardvark");
        createMonitor(bearer, scene.org().organizationId(), projectId, serviceId, "Zebra");
        createMonitor(bearer, scene.org().organizationId(), projectId, serviceId, "Middle");
        String url = monitorsUrl(scene.org().organizationId(), projectId, serviceId);

        mockMvc.perform(get(url + "?sort=name,desc")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Zebra"))
                .andExpect(jsonPath("$.content[1].name").value("Middle"))
                .andExpect(jsonPath("$.content[2].name").value("Aardvark"));

        mockMvc.perform(get(url + "?sort=name,asc")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Aardvark"))
                .andExpect(jsonPath("$.content[1].name").value("Middle"))
                .andExpect(jsonPath("$.content[2].name").value("Zebra"));
    }

    @Test
    void listWithInvalidPaginationSortOrMethodReturns400WithFieldMessage() throws Exception {
        Scene scene = newScene("mon-owner-page-bad", "Mon Page Bad Corp");
        String url = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url + "?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("page: must not be negative"));

        mockMvc.perform(get(url + "?size=0")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));

        mockMvc.perform(get(url + "?size=101")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));

        mockMvc.perform(get(url + "?size=100")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(get(url + "?sort=password")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sort: must be one of createdAt, name, id"));

        mockMvc.perform(get(url + "?sort=name,sideways")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sort: must be one of createdAt, name, id"));

        mockMvc.perform(get(url + "?active=abc")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid request parameter"));

        // method é enum tipado no controller: valor fora de GET/HEAD vira o
        // MESMO 400 genérico de parâmetro, nunca 500
        mockMvc.perform(get(url + "?method=BOGUS")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid request parameter"));
    }

    // --- PATCH .../monitors/{monitorId} -------------------------------------

    @Test
    void updateRenamesMonitorAndPersistsNewNormalizedKey() throws Exception {
        Scene scene = newScene("mon-owner-rename", "Mon Rename Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Nome Antigo");

        MvcResult result = mockMvc.perform(
                        patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                                .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("name", "Nome Novo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(monitorId))
                .andExpect(jsonPath("$.name").value("Nome Novo"))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        assertThat(responseBody(result).get("updatedAt")).isNotNull();
        assertThat(monitorOf(scene.serviceId(), monitorId).getNameNormalized()).isEqualTo("nome novo");
    }

    @Test
    void updateTouchesOnlyProvidedFields() throws Exception {
        Scene scene = newScene("mon-owner-partial", "Mon Partial Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Original");

        // só o timeout → nome, URL, método, intervalo, tipo e active intactos
        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("timeoutMillis", 4000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Original"))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.intervalSeconds").value(60))
                .andExpect(jsonPath("$.timeoutMillis").value(4000))
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()));

        Monitor persisted = monitorOf(scene.serviceId(), monitorId);
        assertThat(persisted.getName()).isEqualTo("Original");
        assertThat(persisted.getTimeoutMillis()).isEqualTo(4000);
        assertThat(persisted.isActive()).isTrue();
    }

    @Test
    void updateWithInvalidPayloadReturns400AndAppliesNothing() throws Exception {
        Scene scene = newScene("mon-owner-patch-bad", "Mon Patch Bad Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Alvo");
        String url = monitorUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        // no PATCH não há @Valid no controller: é o service que distingue
        // "ausente" de "em branco" e aplica as regras de domínio
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "    "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name: must not be blank"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "x".repeat(121)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name: must be at most 120 characters"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("method", "DELETE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("method: must be GET or HEAD"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("intervalSeconds", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "intervalSeconds: must be between 30 and 86400"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("timeoutMillis", 99))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "timeoutMillis: must be between 100 and 30000"));

        // nada das tentativas foi aplicado
        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alvo"))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.intervalSeconds").value(60))
                .andExpect(jsonPath("$.timeoutMillis").value(5000));
    }

    @Test
    void updateRenameToNameOfAnotherMonitorReturns409() throws Exception {
        Scene scene = newScene("mon-owner-rename-dup", "Mon Rename Dup Corp");
        createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Ocupado");
        String free = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Livre");

        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        free))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ocupado"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITOR_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.message").value(
                        "Monitor with this name already exists in this service"));

        // o rename NÃO foi aplicado
        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        free))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Livre"));
    }

    @Test
    void updateRenameToOwnNameIsIdempotent200() throws Exception {
        Scene scene = newScene("mon-owner-rename-self", "Mon Rename Self Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Mesmo Nome");

        // renomear para o próprio nome (ou só mudar a caixa) não pode colidir
        // consigo mesmo → 200, nunca 409
        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "MESMO NOME"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MESMO NOME"));
    }

    @Test
    void updateMonitorOfAnotherServiceReturns404() throws Exception {
        // o MESMO admin e a MESMA organização: o 404 vem do escopo do serviço
        Fixture org = newOrganization(uniqueEmail("mon-owner-cross"), "Mon Cross Corp");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Escopo");
        String first = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Alpha");
        String second = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Beta");
        String alphaMonitor = createMonitor(org.ownerBearer(), org.organizationId(), projectId, first, "Monitor Alpha");
        String betaMonitor = createMonitor(org.ownerBearer(), org.organizationId(), projectId, second, "Monitor Beta");

        mockMvc.perform(patchCsrf(monitorUrl(org.organizationId(), projectId, first, betaMonitor))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Apropriado"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // e a escrita não aconteceu no monitor alvo
        assertThat(monitorOf(second, betaMonitor).getName()).isEqualTo("Monitor Beta");
        assertThat(monitorOf(first, alphaMonitor).getName()).isEqualTo("Monitor Alpha");
    }

    @Test
    void updateWithMalformedIdOrMissingBodyReturns404or400Never500() throws Exception {
        Scene scene = newScene("mon-owner-patch-mal", "Mon Patch Mal Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Intocável");
        String url = monitorUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        "not-a-uuid"))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Qualquer"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{corpo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void updateWithoutAuthenticationReturns401() throws Exception {
        Scene scene = newScene("mon-owner-patch-noauth", "Mon Patch NoAuth Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Seguro");

        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sem Auth"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void updateIgnoresServerOwnedFieldsSentInPayload() throws Exception {
        Scene scene = newScene("mon-owner-mass-up", "Mon Mass Up Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Original");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Renomeado");
        payload.put("active", false);
        payload.put("type", "PING");
        payload.put("serviceId", "00000000-0000-0000-0000-0000000000dd");
        payload.put("projectId", "00000000-0000-0000-0000-0000000000ee");
        payload.put("id", UUID.randomUUID().toString());
        payload.put("createdAt", "2000-01-01T00:00:00Z");
        payload.put("updatedAt", "2000-01-01T00:00:00Z");

        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renomeado"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.id").value(monitorId))
                .andExpect(jsonPath("$.serviceId").value(scene.serviceId()));

        Monitor persisted = monitorOf(scene.serviceId(), monitorId);
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getType().name()).isEqualTo("HTTP");
        assertThat(persisted.getUrl()).isEqualTo(PUBLIC_URL);
    }

    // --- ativação (idempotência detalhada na classe própria) ---------------

    @Test
    void activationTogglesActiveWithoutTouchingConfiguration() throws Exception {
        Scene scene = newScene("mon-owner-act", "Mon Act Corp");
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
                .andExpect(jsonPath("$.active").value(false))
                // desligar o cadastro NÃO reconfigura a checagem
                .andExpect(jsonPath("$.name").value("Ciclo"))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.intervalSeconds").value(60))
                .andExpect(jsonPath("$.timeoutMillis").value(5000))
                .andExpect(jsonPath("$.type").value("HTTP"));

        Monitor persisted = monitorOf(scene.serviceId(), monitorId);
        assertThat(persisted.isActive()).isFalse();
        assertThat(persisted.getType().name()).isEqualTo("HTTP");

        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.url").value(PUBLIC_URL));
    }

    @Test
    void activationWithMissingOrNullActiveReturns400NotNull() throws Exception {
        Scene scene = newScene("mon-owner-act-null", "Mon Act Null Corp");
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
                .andExpect(jsonPath("$.message").value("active: must not be null"));

        // corpo inexistente → 400 de entrada, nunca 500
        mockMvc.perform(patchCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isTrue();
    }

    // --- RBAC ----------------------------------------------------------------

    @Test
    void viewerCanReadMonitorsButGets403OnEveryMutation() throws Exception {
        Scene scene = newScene("mon-owner-viewer", "Mon Viewer Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Viewer Monitor");

        String viewerEmail = uniqueEmail("mon-viewer");
        String viewer = registerAndLogin("Viewer Vera", viewerEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), viewerEmail, "VIEWER");

        mockMvc.perform(get(monitorsUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("VIEWER"));

        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Viewer Create", "url", PUBLIC_URL))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Viewer Rename"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(patchCsrf(monitorActivationUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // nenhuma das três escritas aconteceu
        Monitor untouched = monitorOf(scene.serviceId(), monitorId);
        assertThat(untouched.getName()).isEqualTo("Viewer Monitor");
        assertThat(untouched.isActive()).isTrue();
    }

    @Test
    void operatorCanCreateUpdateAndActivateMonitors() throws Exception {
        Scene scene = newScene("mon-owner-op", "Mon Op Corp");
        String projectId = scene.projectId();
        String serviceId = scene.serviceId();

        String operatorEmail = uniqueEmail("mon-operator");
        String operator = registerAndLogin("Operator Otto", operatorEmail);
        addMember(scene.org().ownerBearer(), scene.org().organizationId(), operatorEmail, "OPERATOR");

        MvcResult created = mockMvc.perform(postCsrf(monitorsUrl(
                                        scene.org().organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Operado Pelo Otto", "url", PUBLIC_URL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn();
        String monitorId = (String) responseBody(created).get("id");

        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(), projectId, serviceId, monitorId))
                        .header(HttpHeaders.AUTHORIZATION, operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Operado Pelo Otto v2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Operado Pelo Otto v2"));

        mockMvc.perform(patchCsrf(monitorActivationUrl(
                                        scene.org().organizationId(), projectId, serviceId, monitorId))
                        .header(HttpHeaders.AUTHORIZATION, operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- pais inativos ------------------------------------------------------

    @Test
    void inactiveProjectAllowsReadsAndBlocksMutationsWith409() throws Exception {
        Scene scene = newScene("mon-owner-inactive", "Mon Inactive Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Sob Inativo");
        changeStatus(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), false);
        String listUrl = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        // LEITURA continua liberada: o catálogo de um projeto inativo é
        // consultável (200), não um erro
        mockMvc.perform(get(listUrl)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Sob Inativo"));

        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        // Mutações → 409 PROJECT_INACTIVE (projeto resolvido e inativo)
        mockMvc.perform(postCsrf(listUrl)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Criado Agora", "url", PUBLIC_URL))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"))
                .andExpect(jsonPath("$.message").value(
                        "Cannot create or modify monitors in an inactive project"));

        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renomeado Agora"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));

        // a checagem de pais acontece ANTES do no-op idempotente: pedir o
        // estado atual (active=true) num projeto inativo também responde 409
        mockMvc.perform(patchCsrf(monitorActivationUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));

        // nada foi aplicado
        Monitor untouched = monitorOf(scene.serviceId(), monitorId);
        assertThat(untouched.getName()).isEqualTo("Sob Inativo");
        assertThat(untouched.isActive()).isTrue();
        assertThat(monitorsOf(scene)).hasSize(1);

        // reativar o projeto devolve o CRUD normal
        changeStatus(scene.org().ownerBearer(), scene.org().organizationId(), scene.projectId(), true);
        mockMvc.perform(postCsrf(listUrl)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Criado Agora", "url", PUBLIC_URL))))
                .andExpect(status().isCreated());
    }

    @Test
    void inactiveServiceAllowsReadsAndBlocksMutationsWith409() throws Exception {
        Scene scene = newScene("mon-owner-svc-inactive", "Mon Svc Inactive Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Sob Serviço Inativo");
        changeActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                false);
        String listUrl = monitorsUrl(scene.org().organizationId(), scene.projectId(), scene.serviceId());

        // o catálogo continua legível mesmo com o serviço desativado
        mockMvc.perform(get(listUrl)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sob Serviço Inativo"));

        mockMvc.perform(postCsrf(listUrl)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Criado Agora", "url", PUBLIC_URL))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"))
                .andExpect(jsonPath("$.message").value(
                        "Cannot create or modify monitors in an inactive service"));

        mockMvc.perform(patchCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renomeado Agora"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"));

        assertThat(monitorsOf(scene)).hasSize(1);
        assertThat(monitorOf(scene.serviceId(), monitorId).getName())
                .isEqualTo("Sob Serviço Inativo");

        // reativar o serviço devolve o CRUD normal
        changeActivation(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                true);
        mockMvc.perform(postCsrf(listUrl)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Criado Agora", "url", PUBLIC_URL))))
                .andExpect(status().isCreated());
    }

    @Test
    void deactivateWithInactiveParentsIsFailSafeAndReactivationIsBlocked() throws Exception {
        Scene scene = newScene("mon-owner-failsafe", "Mon FailSafe Corp");
        String bearer = scene.org().ownerBearer();
        String monitorId = createMonitor(bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), "FailSafe");
        String activationUrl = monitorActivationUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), monitorId);

        // projeto inativo: DESLIGAR é sempre permitido (fail-safe)
        changeStatus(bearer, scene.org().organizationId(), scene.projectId(), false);
        mockMvc.perform(patchCsrf(activationUrl)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isFalse();

        // religar com o projeto inativo → 409 (pais exigidos só no false→true)
        mockMvc.perform(patchCsrf(activationUrl)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_INACTIVE"));

        // projeto reativado, serviço inativo → o MESMO bloqueio no false→true
        changeStatus(bearer, scene.org().organizationId(), scene.projectId(), true);
        changeActivation(bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), false);
        mockMvc.perform(patchCsrf(activationUrl)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"));
        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isFalse();

        // serviço reativado → a reativação volta a funcionar
        changeActivation(bearer, scene.org().organizationId(), scene.projectId(), scene.serviceId(), true);
        mockMvc.perform(patchCsrf(activationUrl)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        assertThat(monitorOf(scene.serviceId(), monitorId).isActive()).isTrue();
    }

    // --- ordem documentada das checagens ------------------------------------

    @Test
    void mutationOrderIsRoleThenProjectThenServiceThenPayloadThenUniqueness() throws Exception {
        Scene scene = newScene("mon-owner-order", "Mon Order Corp");
        String bearer = scene.org().ownerBearer();
        String projectId = scene.projectId();
        String serviceId = scene.serviceId();
        String monitorId =
                createMonitor(bearer, scene.org().organizationId(), projectId, serviceId, "Ordem Monitor");

        // serviço de OUTRO projeto, na mesma organização (prova que o lookup
        // é do projeto da URL, nunca global)
        Fixture org = scene.org();
        String otherProject = createProject(bearer, org.organizationId(), "Projeto Alheio");
        String otherService = createService(bearer, org.organizationId(), otherProject, "Serviço Alheio");
        String otherMonitor = createMonitor(bearer, org.organizationId(), otherProject, otherService, "Monitor Alheio");

        String viewerEmail = uniqueEmail("mon-order-viewer");
        String viewer = registerAndLogin("Viewer Ordem", viewerEmail);
        addMember(bearer, org.organizationId(), viewerEmail, "VIEWER");

        // payload DOMINIALMENTE inválido (URL pública para passar pelo guard)
        Map<String, Object> invalidPayload = new LinkedHashMap<>();
        invalidPayload.put("name", "Nunca Criado");
        invalidPayload.put("url", PUBLIC_URL);
        invalidPayload.put("intervalSeconds", 0);

        // (1) papel ANTES do projeto: VIEWER sobre projeto inexistente → 403
        mockMvc.perform(postCsrf(monitorsUrl(org.organizationId(), UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidPayload)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied"));

        // (2) papel OK → projeto: ADMIN sobre projeto inexistente → 404
        mockMvc.perform(postCsrf(monitorsUrl(org.organizationId(), UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidPayload)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // (3) projeto OK → serviço: serviço inexistente → 404 (nunca 400)
        mockMvc.perform(postCsrf(monitorsUrl(org.organizationId(), projectId, UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidPayload)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // (3b) serviço existente, mas de OUTRO projeto → o MESMO 404
        mockMvc.perform(postCsrf(monitorsUrl(org.organizationId(), projectId, otherService))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidPayload)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));

        // (4) serviço ativo ANTES do payload: serviço inativo → 409, nunca 400
        changeActivation(bearer, org.organizationId(), projectId, serviceId, false);
        mockMvc.perform(postCsrf(monitorsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"));
        changeActivation(bearer, org.organizationId(), projectId, serviceId, true);

        // (5) payload ANTES da unicidade: nome duplicado + payload inválido →
        // o 400 do payload, nunca o 409 (que confirmaria o nome)
        Map<String, Object> duplicateAndInvalid = new LinkedHashMap<>();
        duplicateAndInvalid.put("name", "ordem monitor");
        duplicateAndInvalid.put("url", PUBLIC_URL);
        duplicateAndInvalid.put("intervalSeconds", 0);
        mockMvc.perform(postCsrf(monitorsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(duplicateAndInvalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // (6) payload OK → unicidade responde o 409 documentado
        mockMvc.perform(postCsrf(monitorsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ordem monitor", "url", PUBLIC_URL))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITOR_NAME_DUPLICATE"));

        // nada nasceu: o serviço guarda exatamente o monitor original
        assertThat(monitorsOf(scene)).hasSize(1);
        assertThat(monitorOf(serviceId, monitorId).getName()).isEqualTo("Ordem Monitor");
        assertThat(monitorOf(otherService, otherMonitor).getName()).isEqualTo("Monitor Alheio");
    }

    // --- métodos não permitidos (405 no shape padrão) ------------------------

    @Test
    void deleteMonitorReturns405BecauseRemovalDoesNotExistInThisPrompt() throws Exception {
        Scene scene = newScene("mon-owner-delete", "Mon Delete Corp");
        String monitorId = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Permanente");

        mockMvc.perform(deleteCsrf(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Method DELETE not allowed"));

        // o monitor continua vivo: soft-delete é o .../activation, não DELETE
        mockMvc.perform(get(monitorUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId(),
                                        monitorId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void putOnMonitorCollectionReturns405() throws Exception {
        Scene scene = newScene("mon-owner-put", "Mon Put Corp");

        mockMvc.perform(putCsrf(monitorsUrl(
                                        scene.org().organizationId(),
                                        scene.projectId(),
                                        scene.serviceId()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Substituir", "url", PUBLIC_URL))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // --- apoio ----------------------------------------------------------------

    private List<Monitor> monitorsOf(Scene scene) {
        return monitorRepository
                .findByServiceId(UUID.fromString(scene.serviceId()), PageRequest.of(0, 100))
                .getContent();
    }
}
