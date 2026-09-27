package com.sentinel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sentinel.project.model.Project;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Unicidade de nome POR organização sob concorrência (Prompt 06).
 *
 * <p>A regra tem duas camadas — pré-check ({@code existsBy...}) e
 * {@code UNIQUE (organization_id, name_normalized)} na migration V5 — e só a
 * segunda sobrevive à corrida entre o check e o INSERT. Cada teste dispara
 * duas requisições simultâneas (CountDownLatch alinhando o início) e espera
 * <b>exatamente</b> os status que a invariante admite: {201, 409} para criação
 * duplicada, {200, 409} para rename duplicado, {201, 201} para nomes iguais em
 * tenants diferentes. Duas criações bem-sucedidas seriam a prova de que o
 * índice único não está protegendo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectConcurrencyTest extends AbstractProjectIntegrationTest {

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
    void concurrentCreateOfSameNameResolvesToOne201AndOne409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-concurrent-create"), "Concurrent Create Corp");
        String url = projectsUrl(org.organizationId());
        String payload = json(Map.of("name", "Duplicado", "description", "sob corrida"));

        List<Integer> statuses = concurrentPosts(
                new Post(org.ownerBearer(), url, payload), new Post(org.ownerBearer(), url, payload));

        // o banco é a última linha de defesa: exatamente UM projeto nasce
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(projectRepository.findByOrganizationId(
                        UUID.fromString(org.organizationId()), PageRequest.of(0, 100)))
                .hasSize(1);
    }

    @Test
    void concurrentRenameToSameTargetResolvesToOne200AndOne409() throws Exception {
        Fixture org = newOrganization(uniqueEmail("owner-concurrent-rename"), "Concurrent Rename Corp");
        String first = createProject(org.ownerBearer(), org.organizationId(), "Primeiro");
        String second = createProject(org.ownerBearer(), org.organizationId(), "Segundo");
        String urlA = projectUrl(org.organizationId(), first);
        String urlB = projectUrl(org.organizationId(), second);
        String payload = json(Map.of("name", "Nome Alvo"));

        List<Integer> statuses = concurrentPatches(
                org.ownerBearer(), urlA, payload, org.ownerBearer(), urlB, payload);

        // um rename vence, o outro esbarra no índice único → 409
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        List<Project> projects = projectRepository
                .findByOrganizationId(UUID.fromString(org.organizationId()), PageRequest.of(0, 100))
                .getContent();

        // invariante final: o nome alvo existe UMA única vez
        assertThat(projects)
                .filteredOn(project -> "nome alvo".equals(project.getNameNormalized()))
                .hasSize(1);
        // e o perdedor permanece com o nome original (nada foi renomeado às cegas)
        assertThat(projects)
                .filteredOn(project -> !"nome alvo".equals(project.getNameNormalized()))
                .extracting(Project::getNameNormalized)
                .allMatch(name -> name.equals("primeiro") || name.equals("segundo"));
    }

    @Test
    void concurrentCreateOfSameNameInDifferentOrganizationsReturns201ForBoth() throws Exception {
        Fixture orgA = newOrganization(uniqueEmail("owner-concurrent-a"), "Concurrent A Corp");
        Fixture orgB = newOrganization(uniqueEmail("owner-concurrent-b"), "Concurrent B Corp");
        String payload = json(Map.of("name", "Mesmo Nome"));

        // unicidade é POR organização: o índice composto (org, nome) não pode
        // bloquear dois tenants de dividir o mesmo nome de projeto
        List<Integer> statuses = concurrentPosts(
                new Post(orgA.ownerBearer(), projectsUrl(orgA.organizationId()), payload),
                new Post(orgB.ownerBearer(), projectsUrl(orgB.organizationId()), payload));

        assertThat(statuses).containsOnly(201);
        assertThat(projectRepository.findByOrganizationId(
                        UUID.fromString(orgA.organizationId()), PageRequest.of(0, 100)))
                .hasSize(1);
        assertThat(projectRepository.findByOrganizationId(
                        UUID.fromString(orgB.organizationId()), PageRequest.of(0, 100)))
                .hasSize(1);
    }

    // --- apoio ---------------------------------------------------------------

    private record Post(String bearer, String url, String payload) {
    }

    /** Dois POSTs idênticos/disparados por duas threads — devolve os status. */
    private List<Integer> concurrentPosts(Post first, Post second) throws Exception {
        // CSRF materializado ANTES das threads (o submit estabelece o
        // happens-before da leitura cacheada nos workers)
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
            return collectStatuses(futures);
        }
        finally {
            pool.shutdownNow();
        }
    }

    /** Dois PATCHs simultâneos (URLs distintas, mesmo payload). */
    private List<Integer> concurrentPatches(
            String bearerA, String urlA, String payloadA, String bearerB, String urlB, String payloadB)
            throws Exception {
        csrf();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>(2);
            List<String> urls = List.of(urlA, urlB);
            List<String> bearers = List.of(bearerA, bearerB);
            List<String> payloads = List.of(payloadA, payloadB);
            for (int attempt = 0; attempt < 2; attempt++) {
                int index = attempt;
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(patchCsrf(urls.get(index))
                                    .header(HttpHeaders.AUTHORIZATION, bearers.get(index))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payloads.get(index)))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            return collectStatuses(futures);
        }
        finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> collectStatuses(List<Future<Integer>> futures) throws Exception {
        List<Integer> statuses = new ArrayList<>(2);
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        return statuses;
    }
}
