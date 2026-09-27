package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import com.sentinel.project.repository.ServiceRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base dos testes do Prompt 07 (Services) — reaproveita os helpers do
 * {@code AbstractProjectIntegrationTest} (fixtures de tenant, URLs e criação
 * de projeto) e acrescenta o vocabulário de serviço: URL de rota, criação via
 * API e ativação/desativação.
 *
 * <p>Assim como as bases anteriores, esta classe <b>não</b> tem anotações
 * Spring nem ciclo de vida: cada subclasse declara o seu próprio
 * {@code @SpringBootTest}/{@ServiceConnection}/container (uma classe de
 * contexto por container).</p>
 */
public abstract class AbstractServiceIntegrationTest extends AbstractProjectIntegrationTest {

    @Autowired
    protected ServiceRepository serviceRepository;

    // --- URLs ---------------------------------------------------------------

    protected String servicesUrl(String organizationId, String projectId) {
        return projectUrl(organizationId, projectId) + "/services";
    }

    protected String serviceUrl(String organizationId, String projectId, String serviceId) {
        return servicesUrl(organizationId, projectId) + "/" + serviceId;
    }

    protected String serviceActivationUrl(String organizationId, String projectId, String serviceId) {
        return serviceUrl(organizationId, projectId, serviceId) + "/activation";
    }

    // --- criação via API ----------------------------------------------------

    /** POST → 201, devolve o id. Descrição opcional: {@code null} omitida. */
    protected String createService(
            String bearer,
            String organizationId,
            String projectId,
            String name,
            String description)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (description != null) {
            body.put("description", description);
        }
        MvcResult result = mockMvc.perform(postCsrf(servicesUrl(organizationId, projectId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = (String) responseBody(result).get("id");
        assertThat(id).as("201 must expose the service id").isNotBlank();
        return id;
    }

    protected String createService(
            String bearer, String organizationId, String projectId, String name) throws Exception {
        return createService(bearer, organizationId, projectId, name, null);
    }

    // --- ativação -----------------------------------------------------------

    /** PATCH .../activation → 200 (controla o estado nos testes de filtro/RBAC). */
    protected void changeActivation(
            String bearer,
            String organizationId,
            String projectId,
            String serviceId,
            boolean active)
            throws Exception {
        mockMvc.perform(patchCsrf(serviceActivationUrl(organizationId, projectId, serviceId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", active))))
                .andExpect(status().isOk());
    }
}
