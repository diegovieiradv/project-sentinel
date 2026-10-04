package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.alert.model.AlertSeverity;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.model.IncidentEventType;
import com.sentinel.project.incident.model.IncidentStatus;

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
 * Contrato HTTP da API de incidentes (Prompt 14) — os nove endpoints, a
 * máquina de estados, a imutabilidade pós-{@code RESOLVED} e a validação de
 * domínio contra contexto real.
 *
 * <p><b>Sem relógio congelado</b> (ao contrário do engine de alertas): esta
 * classe roda com o {@code SystemClock} real de propósito, porque os testes de
 * {@code startedAt} precisam do relógio corrente para exercitar as janelas
 * (±5 min / 30 dias).</p>
 *
 * <p>Cobre o shape do 201, a ordem fixa {@code startedAt DESC, id DESC}, os
 * dois filtros tipados, a paginação validada, o PATCH parcial com eventos por
 * campo (e no-op que não escreve), os campos server-owned ignorados, as seis
 * transições + o 409 de autotransição/retrocesso, o 409 de conteúdo pós-resolução,
 * o 405 do DELETE de incidente e o 401 anônimo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IncidentApiTest extends AbstractIncidentIntegrationTest {

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

    // --- cenário ------------------------------------------------------------

    private Scene sceneWithIncident(String prefix) throws Exception {
        Scene scene = newScene(prefix, "Org IncidentApi " + prefix);
        createIncident(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Incidente de " + prefix);
        return scene;
    }

    private String ownerIncident(Scene scene) throws Exception {
        List<Incident> all = incidentsOf(scene.serviceId());
        assertThat(all).hasSize(1);
        return all.get(0).getId().toString();
    }

    // --- 401 anônimo --------------------------------------------------------

    @Test
    void anonymousWriteAndRead_return401() throws Exception {
        Scene scene = sceneWithIncident("incident-anon");
        String url = incidentsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(postCsrf(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(incidentPayload("Sem token"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    // --- POST / 201 ---------------------------------------------------------

    @Test
    void create_returns201WithFullShapeAndOpenStatus() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-create"), "Org IncidentCreate");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Incidente");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Incidente");
        String url = incidentsUrl(org.organizationId(), projectId, serviceId);

        Map<String, Object> body = incidentPayload(
                "Checkout fora do ar", "5xx na rota de pagamento", AlertSeverity.SEV1);

        MvcResult result = mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Checkout fora do ar"))
                .andExpect(jsonPath("$.description").value("5xx na rota de pagamento"))
                .andExpect(jsonPath("$.severity").value("SEV1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.serviceId").value(serviceId))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.organizationId").value(org.organizationId()))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        Map<String, Object> response = responseBody(result);
        assertThat((String) response.get("id")).isNotBlank();
        assertThat((String) response.get("createdByUserId")).isNotBlank();
        assertThat(response.get("startedAt")).isNotNull();
    }

    @Test
    void create_withoutStartedAtDefaultsToDomainClock() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-started-default"), "Org Incident Started");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Started");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Started");
        String url = incidentsUrl(org.organizationId(), projectId, serviceId);

        Instant before = Instant.now();
        MvcResult result = mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(incidentPayload("Sem startedAt"))))
                .andExpect(status().isCreated())
                .andReturn();
        Instant after = Instant.now();

        String id = (String) responseBody(result).get("id");
        Instant startedAt = incidentOf(serviceId, id).getStartedAt();
        assertThat(startedAt).isBetween(before, after);
    }

    @Test
    void create_withExplicitStartedAtHonorsIt() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-started-explicit"), "Org Incident Explicit");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Started");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Started");
        String url = incidentsUrl(org.organizationId(), projectId, serviceId);

        Instant startedAt = Instant.now().minus(Duration.ofHours(3));
        Map<String, Object> body = incidentPayload("Incidente reatrasado");
        body.put("startedAt", startedAt.toString());

        MvcResult result = mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String id = (String) responseBody(result).get("id");
        assertThat(Duration.between(incidentOf(serviceId, id).getStartedAt(), startedAt).abs())
                .as("TIMESTAMPTZ guarda microssegundos: só o nano extra é arredondado")
                .isLessThanOrEqualTo(Duration.ofNanos(1_000));
    }

    // --- POST / validação ---------------------------------------------------

    @Test
    void create_blankTitle_returns400() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-blank"), "Org Incident Blank");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Blank");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Blank");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "   ");
        body.put("severity", "SEV3");

        mockMvc.perform(postCsrf(incidentsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("title: must not be blank"));

        assertThat(incidentsOf(serviceId)).isEmpty();
    }

    @Test
    void create_titleOverColumnLimit_returns400() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-title-long"), "Org Incident TitleLong");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto TitleLong");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço TitleLong");

        Map<String, Object> body = incidentPayload("a".repeat(201));

        mockMvc.perform(postCsrf(incidentsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("title: must be at most 200 characters"));
    }

    @Test
    void create_descriptionOverColumnLimit_returns400() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-desc-long"), "Org Incident DescLong");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto DescLong");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço DescLong");

        Map<String, Object> body = incidentPayload("Descrição enorme", "d".repeat(4001), AlertSeverity.SEV2);

        mockMvc.perform(postCsrf(incidentsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("description: must be at most 4000 characters"));
    }

    @Test
    void create_withoutSeverity_returns400() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-no-sev"), "Org Incident NoSeverity");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto NoSeverity");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço NoSeverity");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Sem severidade");

        mockMvc.perform(postCsrf(incidentsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("severity: must not be null"));
    }

    @Test
    void create_malformedJson_returns400() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-badjson"), "Org Incident BadJson");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto BadJson");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço BadJson");

        mockMvc.perform(postCsrf(incidentsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void create_controlCharactersAreStrippedBeforePersist() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-control"), "Org Incident Control");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Control");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Control");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "  Pagina\u0000ção  ");
        body.put("description", "linha um\nlinha dois");
        body.put("severity", "SEV2");

        MvcResult result = mockMvc.perform(postCsrf(incidentsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String id = (String) responseBody(result).get("id");
        Incident persisted = incidentOf(serviceId, id);
        assertThat(persisted.getTitle()).isEqualTo("Paginação");
        assertThat(persisted.getDescription()).isEqualTo("linha umlinha dois");
    }

    // --- startedAt / janela -------------------------------------------------

    @Test
    void create_startedAtTooFarInFuture_returns400() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-future"), "Org Incident Future");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Future");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Future");

        Map<String, Object> body = incidentPayload("Do futuro");
        body.put("startedAt", Instant.now().plus(Duration.ofHours(2)).toString());

        mockMvc.perform(postCsrf(incidentsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("startedAt: must not be more than 5 minutes in the future"));
    }

    @Test
    void create_startedAtTooOld_returns400() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-ancient"), "Org Incident Ancient");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Ancient");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Ancient");

        Map<String, Object> body = incidentPayload("Do passado distante");
        body.put("startedAt", Instant.now().minus(Duration.ofDays(40)).toString());

        mockMvc.perform(postCsrf(incidentsUrl(org.organizationId(), projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("startedAt: must not be older than 30 days"));
    }

    @Test
    void create_startedAtJustInsideTheWindowsIsAccepted() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-window"), "Org Incident Window");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Window");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Window");
        String url = incidentsUrl(org.organizationId(), projectId, serviceId);

        Map<String, Object> recent = incidentPayload("Quase agora");
        recent.put("startedAt", Instant.now().plus(Duration.ofMinutes(4)).toString());
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(recent)))
                .andExpect(status().isCreated());

        Map<String, Object> old = incidentPayload("Limite dos 30 dias");
        old.put("startedAt", Instant.now().minus(Duration.ofDays(29)).toString());
        mockMvc.perform(postCsrf(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(old)))
                .andExpect(status().isCreated());
    }

    // --- GET listagem -------------------------------------------------------

    @Test
    void list_returnsPageOrderedByStartedAtDesc() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-order"), "Org Incident Order");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Order");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Order");
        String url = incidentsUrl(org.organizationId(), projectId, serviceId);

        for (int i = 0; i < 3; i++) {
            Map<String, Object> body = incidentPayload("Incidente " + i);
            body.put("startedAt", Instant.now().minus(Duration.ofHours(10 - i)).toString());
            mockMvc.perform(postCsrf(url)
                            .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].title").value("Incidente 2"))
                .andExpect(jsonPath("$.content[1].title").value("Incidente 1"))
                .andExpect(jsonPath("$.content[2].title").value("Incidente 0"));
    }

    @Test
    void list_filtersByStatusAndSeverity() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-filter"), "Org Incident Filter");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Filter");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Filter");
        String url = incidentsUrl(org.organizationId(), projectId, serviceId);

        String first = createIncident(
                org.ownerBearer(), org.organizationId(), projectId, serviceId,
                incidentPayload("SEV1 aberto", null, AlertSeverity.SEV1));
        createIncident(
                org.ownerBearer(), org.organizationId(), projectId, serviceId,
                incidentPayload("SEV4 aberto", null, AlertSeverity.SEV4));
        changeIncidentStatus(
                org.ownerBearer(), org.organizationId(), projectId, serviceId, first, "RESOLVED");

        mockMvc.perform(get(url).param("severity", "SEV1")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("SEV1 aberto"));

        mockMvc.perform(get(url).param("status", "RESOLVED")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("RESOLVED"));

        mockMvc.perform(get(url).param("status", "OPEN").param("severity", "SEV4")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("SEV4 aberto"));

        mockMvc.perform(get(url).param("status", "OPEN").param("severity", "SEV1")
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void list_paginationIsValidated() throws Exception {
        Scene scene = sceneWithIncident("incident-page");
        String url = incidentsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url).param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page: must not be negative"));

        mockMvc.perform(get(url).param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));

        mockMvc.perform(get(url).param("size", "101")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));
    }

    @Test
    void list_unknownEnumValue_returns400Never500() throws Exception {
        Scene scene = sceneWithIncident("incident-enum");
        String url = incidentsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId());

        mockMvc.perform(get(url).param("status", "CLOSED")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get(url).param("severity", "SEV9")
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // --- GET detalhe --------------------------------------------------------

    @Test
    void get_returns200AndUnknownReturns404() throws Exception {
        Scene scene = sceneWithIncident("incident-get");
        String incidentId = ownerIncident(scene);

        mockMvc.perform(get(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId))
                .andExpect(jsonPath("$.title").value("Incidente de incident-get"));

        mockMvc.perform(get(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), UUID.randomUUID().toString()))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // --- PATCH conteúdo -----------------------------------------------------

    @Test
    void patch_changesOnlyTheSentFieldsAndEmitsOneEvent() throws Exception {
        Scene scene = sceneWithIncident("incident-patch-partial");
        String incidentId = ownerIncident(scene);
        Instant startedAt = incidentOf(scene.serviceId(), incidentId).getStartedAt();

        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("severity", "SEV1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("SEV1"))
                .andExpect(jsonPath("$.title").value("Incidente de incident-patch-partial"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.startedAt").exists());

        Incident persisted = incidentOf(scene.serviceId(), incidentId);
        assertThat(persisted.getSeverity()).isEqualTo(AlertSeverity.SEV1);
        assertThat(persisted.getTitle()).isEqualTo("Incidente de incident-patch-partial");
        assertThat(persisted.getStartedAt())
                .as("startedAt é imutável depois do create")
                .isEqualTo(startedAt);
        assertThat(persisted.getUpdatedAt()).isAfter(persisted.getCreatedAt());
        assertThat(persisted.getVersion()).isEqualTo(1L);
        assertThat(eventTypes(incidentId))
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.SEVERITY_CHANGED);
    }

    @Test
    void patch_emptyDescriptionClearsItAndBlankTitleIsRejected() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-patch-desc"), "Org Incident PatchDesc");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto PatchDesc");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço PatchDesc");
        String incidentId = createIncident(
                org.ownerBearer(), org.organizationId(), projectId, serviceId,
                incidentPayload("Com descrição", "descrição original", AlertSeverity.SEV3));

        mockMvc.perform(patch(incidentUrl(org.organizationId(), projectId, serviceId, incidentId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist());

        assertThat(incidentOf(serviceId, incidentId).getDescription()).isNull();

        mockMvc.perform(patch(incidentUrl(org.organizationId(), projectId, serviceId, incidentId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("title: must not be blank"));
    }

    @Test
    void patch_noopWritesNoEventAndDoesNotBumpUpdatedAt() throws Exception {
        Scene scene = sceneWithIncident("incident-patch-noop");
        String incidentId = ownerIncident(scene);
        Incident before = incidentOf(scene.serviceId(), incidentId);

        mockMvc.perform(patch(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", before.getTitle()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(before.getTitle()));

        Incident after = incidentOf(scene.serviceId(), incidentId);
        assertThat(after.getUpdatedAt())
                .as("no-op não reescreve a linha")
                .isEqualTo(before.getUpdatedAt());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(eventTypes(incidentId))
                .as("nada mudou, nada é registrado")
                .containsExactly(IncidentEventType.CREATED);
    }

    @Test
    void patch_multipleFieldsEmitsEventsInFixedOrder() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-patch-multi"), "Org Incident PatchMulti");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto PatchMulti");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço PatchMulti");
        String incidentId = createIncident(
                org.ownerBearer(), org.organizationId(), projectId, serviceId,
                incidentPayload("Título original", "descrição original", AlertSeverity.SEV3));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Título novo");
        body.put("description", "descrição nova");
        body.put("severity", "SEV1");

        mockMvc.perform(patch(incidentUrl(org.organizationId(), projectId, serviceId, incidentId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Título novo"))
                .andExpect(jsonPath("$.description").value("descrição nova"))
                .andExpect(jsonPath("$.severity").value("SEV1"));

        assertThat(eventTypes(incidentId))
                .as("ordem fixa: title → description → severity")
                .containsExactly(
                        IncidentEventType.CREATED,
                        IncidentEventType.TITLE_CHANGED,
                        IncidentEventType.DESCRIPTION_CHANGED,
                        IncidentEventType.SEVERITY_CHANGED);
    }

    @Test
    void patchIgnoresServerOwnedFields() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-patch-owned"), "Org Incident PatchOwned");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto PatchOwned");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço PatchOwned");
        String incidentId = createIncident(
                org.ownerBearer(), org.organizationId(), projectId, serviceId, "Contrato protegido");
        Incident before = incidentOf(serviceId, incidentId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Contrato protegido");
        body.put("status", "RESOLVED");
        body.put("id", UUID.randomUUID().toString());
        body.put("serviceId", UUID.randomUUID().toString());
        body.put("organizationId", UUID.randomUUID().toString());
        body.put("startedAt", "2020-01-01T00:00:00Z");
        body.put("resolvedAt", "2020-01-01T00:00:00Z");
        body.put("createdByUserId", UUID.randomUUID().toString());
        body.put("version", 99);
        body.put("escalationPolicy", "PAGERDUTY");

        mockMvc.perform(patch(incidentUrl(org.organizationId(), projectId, serviceId, incidentId))
                        .header(HttpHeaders.AUTHORIZATION, org.ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.escalationPolicy").doesNotExist());

        Incident after = incidentOf(serviceId, incidentId);
        assertThat(after.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(after.getResolvedAt()).isNull();
        assertThat(after.getId().toString()).isEqualTo(incidentId);
        assertThat(after.getService().getId().toString()).isEqualTo(serviceId);
        assertThat(after.getStartedAt()).isEqualTo(before.getStartedAt());
        assertThat(after.getCreatedByUserId()).isEqualTo(before.getCreatedByUserId());
        assertThat(after.getVersion()).isEqualTo(0L);
    }

    // --- PATCH status -------------------------------------------------------

    @Test
    void status_forwardTransitionsAreAllowed() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-status"), "Org Incident Status");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Status");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Status");
        String incidentId = createIncident(
                org.ownerBearer(), org.organizationId(), projectId, serviceId, "Três passos");

        changeIncidentStatus(
                org.ownerBearer(), org.organizationId(), projectId, serviceId,
                incidentId, "INVESTIGATING");
        changeIncidentStatus(
                org.ownerBearer(), org.organizationId(), projectId, serviceId,
                incidentId, "IDENTIFIED");
        changeIncidentStatus(
                org.ownerBearer(), org.organizationId(), projectId, serviceId,
                incidentId, "RESOLVED");

        Incident persisted = incidentOf(serviceId, incidentId);
        assertThat(persisted.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(persisted.getResolvedAt()).isNotNull();
        assertThat(eventTypes(incidentId))
                .containsExactly(
                        IncidentEventType.CREATED,
                        IncidentEventType.STATUS_CHANGED,
                        IncidentEventType.STATUS_CHANGED,
                        IncidentEventType.STATUS_CHANGED);
    }

    @Test
    void status_jumpFromOpenToResolvedIsAllowed() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-jump"), "Org Incident Jump");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Jump");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Jump");
        String incidentId = createIncident(
                org.ownerBearer(), org.organizationId(), projectId, serviceId, "Atalho");

        changeIncidentStatus(
                org.ownerBearer(), org.organizationId(), projectId, serviceId,
                incidentId, "RESOLVED");

        assertThat(incidentOf(serviceId, incidentId).getResolvedAt()).isNotNull();
    }

    @Test
    void status_invalidTransitionsReturn409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-status-bad"), "Org Incident StatusBad");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto StatusBad");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço StatusBad");
        String url = incidentsUrl(org.organizationId(), projectId, serviceId);
        String bearer = org.ownerBearer();

        // autotransição OPEN → OPEN
        String selfOpen = createIncident(bearer, org.organizationId(), projectId, serviceId, "Autotransição");
        expectTransition409(bearer, url, selfOpen, "OPEN", "OPEN");

        // retrocesso INVESTIGATING → OPEN
        String backwards = createIncident(bearer, org.organizationId(), projectId, serviceId, "Retrocesso");
        changeIncidentStatus(bearer, org.organizationId(), projectId, serviceId,
                backwards, "INVESTIGATING");
        expectTransition409(bearer, url, backwards, "INVESTIGATING", "OPEN");

        // retrocesso IDENTIFIED → INVESTIGATING
        String identified = createIncident(bearer, org.organizationId(), projectId, serviceId, "Identificado");
        changeIncidentStatus(bearer, org.organizationId(), projectId, serviceId,
                identified, "IDENTIFIED");
        expectTransition409(bearer, url, identified, "IDENTIFIED", "INVESTIGATING");

        // saída de RESOLVED
        String resolved = createIncident(bearer, org.organizationId(), projectId, serviceId, "Encerrado");
        changeIncidentStatus(bearer, org.organizationId(), projectId, serviceId,
                resolved, "RESOLVED");
        expectTransition409(bearer, url, resolved, "RESOLVED", "OPEN");
        expectTransition409(bearer, url, resolved, "RESOLVED", "INVESTIGATING");
        expectTransition409(bearer, url, resolved, "RESOLVED", "RESOLVED");
    }

    private void expectTransition409(
            String bearer, String url, String incidentId, String from, String to) throws Exception {
        mockMvc.perform(patchCsrf(url + "/" + incidentId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", to))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message")
                        .value("Cannot transition from " + from + " to " + to));
    }

    @Test
    void status_missingFieldReturns400() throws Exception {
        Scene scene = sceneWithIncident("incident-status-missing");
        String incidentId = ownerIncident(scene);

        mockMvc.perform(patchCsrf(incidentStatusUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status: must not be null"));
    }

    // --- imutabilidade pós-RESOLVED ----------------------------------------

    @Test
    void resolvedIncidentRejectsContentAndLinkMutationsWith409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("incident-resolved"), "Org Incident Resolved");
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Resolved");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Resolved");
        String bearer = org.ownerBearer();
        String incidentId = createIncident(bearer, org.organizationId(), projectId, serviceId, "Fechado");
        changeIncidentStatus(bearer, org.organizationId(), projectId, serviceId, incidentId, "RESOLVED");

        Incident before = incidentOf(serviceId, incidentId);

        mockMvc.perform(patch(incidentUrl(org.organizationId(), projectId, serviceId, incidentId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Reescrito"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_RESOLVED"))
                .andExpect(jsonPath("$.message")
                        .value("Incident is resolved and can no longer be modified"));

        Incident after = incidentOf(serviceId, incidentId);
        assertThat(after.getTitle()).isEqualTo(before.getTitle());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(eventTypes(incidentId))
                .as("bloqueio não escreve nada na timeline")
                .containsExactly(IncidentEventType.CREATED, IncidentEventType.STATUS_CHANGED);
    }

    // --- verbos -------------------------------------------------------------

    @Test
    void deleteIncidentReturns405() throws Exception {
        Scene scene = sceneWithIncident("incident-delete");
        String incidentId = ownerIncident(scene);

        mockMvc.perform(deleteCsrf(incidentUrl(
                                        scene.org().organizationId(), scene.projectId(),
                                        scene.serviceId(), incidentId))
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void postOnCollectionWithStatusPathReturns405() throws Exception {
        Scene scene = sceneWithIncident("incident-wrong-verb");
        String incidentId = ownerIncident(scene);
        String statusUrl = incidentStatusUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId(), incidentId);

        mockMvc.perform(postCsrf(statusUrl)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "RESOLVED"))))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(deleteCsrf(statusUrl)
                        .header(HttpHeaders.AUTHORIZATION, scene.org().ownerBearer()))
                .andExpect(status().isMethodNotAllowed());
    }
}
