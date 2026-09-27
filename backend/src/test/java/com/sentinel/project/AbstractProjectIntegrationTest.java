package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sentinel.identity.user.UserRepository;
import com.sentinel.organization.AbstractTenantIntegrationTest;
import com.sentinel.organization.repository.MembershipRepository;
import com.sentinel.project.repository.ProjectRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base dos testes do Prompt 06 (Projects) — reaproveita os helpers puros do
 * {@code AbstractTenantIntegrationTest} (JSON, CSRF double-submit real,
 * register/login/createOrganization/addMember) e acrescenta o vocabulário de
 * projeto: fixture de tenant, URL de rota e criação de projeto via API.
 *
 * <p>Assim como a base do Prompt 04, esta classe <b>não</b> tem anotações
 * Spring nem ciclo de vida: cada subclasse declara o seu próprio
 * {@code @SpringBootTest}/{@ServiceConnection}/container (uma classe de
 * contexto por container). Aqui ficam apenas helpers e o acesso ao repositório
 * para asserções de estado persistido.</p>
 */
public abstract class AbstractProjectIntegrationTest extends AbstractTenantIntegrationTest {

    /** Sufixo por teste: cadastro e organizações nunca colidem dentro da classe. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    protected ProjectRepository projectRepository;

    @Autowired
    protected MembershipRepository membershipRepository;

    @Autowired
    protected UserRepository userRepository;

    /** E-mail único por invocação — os testes de uma classe compartilham o banco. */
    protected static String uniqueEmail(String local) {
        return local + "-" + SEQUENCE.incrementAndGet() + "@example.com";
    }

    /** Organização recém-criada: dono autenticado + papel ADMIN implícito. */
    protected record Fixture(String ownerBearer, String organizationId, String ownerEmail) {
    }

    protected Fixture newOrganization(String ownerEmail, String organizationName) throws Exception {
        String bearer = registerAndLogin("Owner", ownerEmail);
        return new Fixture(bearer, createOrganization(bearer, organizationName), ownerEmail);
    }

    // --- URLs ---------------------------------------------------------------

    protected String projectsUrl(String organizationId) {
        return "/api/v1/organizations/" + organizationId + "/projects";
    }

    protected String projectUrl(String organizationId, String projectId) {
        return projectsUrl(organizationId) + "/" + projectId;
    }

    protected String projectStatusUrl(String organizationId, String projectId) {
        return projectUrl(organizationId, projectId) + "/status";
    }

    // --- criação via API ----------------------------------------------------

    /** POST → 201, devolve o id. Descrição opcional: {@code null} omitida. */
    protected String createProject(
            String bearer, String organizationId, String name, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (description != null) {
            body.put("description", description);
        }
        MvcResult result = mockMvc.perform(postCsrf(projectsUrl(organizationId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = (String) responseBody(result).get("id");
        assertThat(id).as("201 must expose the project id").isNotBlank();
        return id;
    }

    protected String createProject(String bearer, String organizationId, String name) throws Exception {
        return createProject(bearer, organizationId, name, null);
    }

    /** PATCH .../status → 200 (controla o estado nos testes de filtro). */
    protected void changeStatus(
            String bearer, String organizationId, String projectId, boolean active) throws Exception {
        mockMvc.perform(patchCsrf(projectStatusUrl(organizationId, projectId))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", active))))
                .andExpect(status().isOk());
    }
}
