package com.sentinel;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Valida o contexto Spring completo (contextLoads) contra um PostgreSQL
 * real provido por Testcontainers: o contexto carrega com web, JPA,
 * Flyway (autoconfig via spring-boot-starter-flyway, aplica V1) e
 * o ddl-auto=validate do JPA passa sem erro de schema.
 *
 * Lifecycle manual do container (start em @BeforeAll, stop em @AfterAll):
 * o módulo JUnit Jupiter do Testcontainers 2.x não integra ao escopo de
 * dependências aprovado; @ServiceConnection do Spring Boot resolve a
 * conexão a partir do campo estático.
 */
@SpringBootTest
class SentinelApplicationTests {

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
    void contextLoads() {
        // contexto Spring carregado (com Flyway aplicando V1 no Testcontainers)
        // e JPA ddl-auto=validate aprovado — sem entidades de domínio, o
        // validate apenas confirma que a conexão/EntityManager estão válidos
    }
}
