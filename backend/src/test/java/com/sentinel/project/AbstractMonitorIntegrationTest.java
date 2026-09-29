package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.repository.MonitorRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base dos testes do Prompt 08 (Monitors) — reaproveita os helpers do
 * {@code AbstractServiceIntegrationTest} (fixtures de tenant, URLs, criação de
 * projeto e de serviço) e acrescenta o vocabulário de monitor: URL de rota,
 * criação via API, ativação/desativação e leitura direta do repositório.
 *
 * <p>Assim como as bases anteriores, esta classe <b>não</b> tem anotações
 * Spring nem ciclo de vida: cada subclasse declara o seu próprio
 * {@code @SpringBootTest}/{@ServiceConnection}/container (uma classe de
 * contexto por container). Exceção planejada: só {@code MonitorSsrfTest}
 * declara um {@code @TestConfiguration} aninhado com um DNS stub — e como
 * configuração aninhada só é detectada pela classe que a declara, o stub
 * vive exclusivamente naquele contexto.</p>
 *
 * <p><b>URL de teste padrão:</b> {@link #PUBLIC_URL} usa um LITERAL IPv4
 * público — {@code InetAddress} não precisa de DNS para interpretar literais,
 * então as demais classes de teste rodam sem Internet e sem depender do
 * resolver do ambiente. Hostnames ficam restritos ao {@code MonitorSsrfTest},
 * que injeta o stub.</p>
 */
public abstract class AbstractMonitorIntegrationTest extends AbstractServiceIntegrationTest {

    /** Alvo público canônico dos testes que NÃO exercitam o guard. */
    protected static final String PUBLIC_URL = "https://93.184.216.34/health";

    @Autowired
    protected MonitorRepository monitorRepository;

    // --- URLs ---------------------------------------------------------------

    protected String monitorsUrl(String organizationId, String projectId, String serviceId) {
        return serviceUrl(organizationId, projectId, serviceId) + "/monitors";
    }

    protected String monitorUrl(
            String organizationId, String projectId, String serviceId, String monitorId) {
        return monitorsUrl(organizationId, projectId, serviceId) + "/" + monitorId;
    }

    protected String monitorActivationUrl(
            String organizationId, String projectId, String serviceId, String monitorId) {
        return monitorUrl(organizationId, projectId, serviceId, monitorId) + "/activation";
    }

    // --- cenário ------------------------------------------------------------

    /**
     * Cenário padrão dos testes: organização + projeto + serviço prontos para
     * receber monitores (a cadeia inteira já provada pela criação via API).
     */
    protected record Scene(Fixture org, String projectId, String serviceId) {
    }

    protected Scene newScene(String localPrefix, String organizationName) throws Exception {
        Fixture org = newOrganization(uniqueEmail(localPrefix), organizationName);
        String projectId = createProject(org.ownerBearer(), org.organizationId(), "Projeto Monitor");
        String serviceId = createService(org.ownerBearer(), org.organizationId(), projectId, "Serviço Monitor");
        return new Scene(org, projectId, serviceId);
    }

    // --- criação via API ----------------------------------------------------

    /** POST → 201, devolve o id, com a URL pública padrão e defaults do servidor. */
    protected String createMonitor(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            String name)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("url", PUBLIC_URL);
        return createMonitor(bearer, organizationId, projectId, serviceId, body);
    }

    /** POST → 201 com o corpo montado pelo teste (sem campos de escopo). */
    protected String createMonitor(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            Map<String, Object> body)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        postCsrf(monitorsUrl(organizationId, projectId, serviceId))
                                .header(HttpHeaders.AUTHORIZATION, bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = (String) responseBody(result).get("id");
        assertThat(id).as("201 must expose the monitor id").isNotBlank();
        return id;
    }

    /** POST → 201 com nome e URL explicitados (sem usar o literal padrão). */
    protected String createMonitor(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            String name,
            String url)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("url", url);
        return createMonitor(bearer, organizationId, projectId, serviceId, body);
    }

    // --- ativação -----------------------------------------------------------

    /** PATCH .../activation → 200 (controla o estado nos testes de filtro/RBAC). */
    protected void changeMonitorActivation(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            String monitorId,
            boolean active)
            throws Exception {
        mockMvc.perform(
                        patchCsrf(monitorActivationUrl(organizationId, projectId, serviceId, monitorId))
                                .header(HttpHeaders.AUTHORIZATION, bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("active", active))))
                .andExpect(status().isOk());
    }

    // --- leitura persistida -------------------------------------------------

    /** Monitor via repositório (estado final depois de um 400/409/concorrência). */
    protected Monitor monitorOf(String serviceId, String monitorId) {
        return monitorRepository
                .findByServiceIdAndId(UUID.fromString(serviceId), UUID.fromString(monitorId))
                .orElseThrow();
    }

}
