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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Unicidade de nome POR SERVIÇO sob concorrência (Prompt 08).
 *
 * <p>A regra tem duas camadas — pré-check ({@code existsBy...}) e
 * {@code UNIQUE (service_id, name_normalized)} da migration V7 — e só a
 * segunda sobrevive à corrida entre o check e o INSERT. Cada teste dispara
 * duas requisições simultâneas ({@code CountDownLatch} alinhando o início) e
 * espera <b>exatamente</b> os status que a invariante admite: {201, 409} para
 * criação duplicada, {200, 409} para rename duplicado e {201, 201} para o
 * mesmo nome em serviços diferentes. Duas criações bem-sucedidas seriam a
 * prova de que o índice único não está protegendo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitorConcurrencyTest extends AbstractMonitorIntegrationTest {

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
        Scene scene = newScene("mon-conc-create", "Mon Concurrent Create");
        String url = monitorsUrl(
                scene.org().organizationId(), scene.projectId(), scene.serviceId());
        String payload = json(Map.of("name", "Duplicado", "url", PUBLIC_URL));

        List<Integer> statuses = concurrentPosts(
                new Post(scene.org().ownerBearer(), url, payload),
                new Post(scene.org().ownerBearer(), url, payload));

        // o banco é a última linha de defesa: exatamente UM monitor nasce
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(monitorsOf(scene.serviceId())).hasSize(1);
    }

    @Test
    void concurrentRenameToSameTargetResolvesToOne200AndOne409() throws Exception {
        Scene scene = newScene("mon-conc-rename", "Mon Concurrent Rename");
        String first = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Primeiro");
        String second = createMonitor(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                scene.serviceId(),
                "Segundo");
        String payload = json(Map.of("name", "Nome Alvo"));

        List<Integer> statuses = concurrentPatches(
                scene.org().ownerBearer(),
                monitorUrl(
                        scene.org().organizationId(),
                        scene.projectId(),
                        scene.serviceId(),
                        first),
                payload,
                scene.org().ownerBearer(),
                monitorUrl(
                        scene.org().organizationId(),
                        scene.projectId(),
                        scene.serviceId(),
                        second),
                payload);

        // um rename vence, o outro esbarra no índice único → 409
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        List<Monitor> monitors = monitorsOf(scene.serviceId());

        // invariante final: o nome alvo existe UMA única vez
        assertThat(monitors)
                .filteredOn(monitor -> "nome alvo".equals(monitor.getNameNormalized()))
                .hasSize(1);
        // e o perdedor permanece com o nome original (nada foi renomeado às cegas)
        assertThat(monitors)
                .filteredOn(monitor -> !"nome alvo".equals(monitor.getNameNormalized()))
                .extracting(Monitor::getNameNormalized)
                .allMatch(name -> name.equals("primeiro") || name.equals("segundo"));
    }

    @Test
    void concurrentCreateOfSameNameInDifferentServicesReturns201ForBoth() throws Exception {
        Scene scene = newScene("mon-conc-services", "Mon Concurrent Services");
        String serviceA = createService(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                "Serviço A");
        String serviceB = createService(
                scene.org().ownerBearer(),
                scene.org().organizationId(),
                scene.projectId(),
                "Serviço B");
        String payload = json(Map.of("name", "Mesmo Nome", "url", PUBLIC_URL));

        // unicidade é POR serviço: o índice composto (service, nome) não pode
        // bloquear dois serviços de dividir o mesmo nome de monitor
        List<Integer> statuses = concurrentPosts(
                new Post(
                        scene.org().ownerBearer(),
                        monitorsUrl(scene.org().organizationId(), scene.projectId(), serviceA),
                        payload),
                new Post(
                        scene.org().ownerBearer(),
                        monitorsUrl(scene.org().organizationId(), scene.projectId(), serviceB),
                        payload));

        assertThat(statuses).containsOnly(201);
        assertThat(monitorsOf(serviceA)).hasSize(1);
        assertThat(monitorsOf(serviceB)).hasSize(1);
    }

    // --- apoio ---------------------------------------------------------------

    private record Post(String bearer, String url, String payload) {
    }

    /** Monitores de um serviço via repositório (estado final após a corrida). */
    private List<Monitor> monitorsOf(String serviceId) {
        return monitorRepository
                .findByServiceId(UUID.fromString(serviceId), PageRequest.of(0, 100))
                .getContent();
    }

    /** Dois POSTs simultâneos — devolve os status. */
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
