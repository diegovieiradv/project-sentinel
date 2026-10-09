package com.sentinel.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Classificação de violações de integridade (Prompt 17 / RES-05 e RES-07) —
 * os dois lados do contrato que o {@code AlertEngine} e o
 * {@code AlertIncidentAutomation} usam para separar CORRIDA benigna de
 * DEFEITO, sem depender de onde o Postgres enfiou o nome da constraint na
 * cadeia de causas.
 *
 * <p>Puros, sem Spring nem banco: a cadeia é montada à mão no formato real
 * (o driver {@code PSQLException} no fundo, a tradução do Spring no topo).</p>
 */
class IntegrityViolationsTest {

    private static final String OPEN_RULE = "uq_alerts_open_rule";
    private static final String AUTO_OPEN = "uq_incidents_auto_open_per_service";

    /** Forma real: tradução genérica em cima, texto do Postgres embaixo. */
    private static DataIntegrityViolationException chained(String sqlMessage) {
        return new DataIntegrityViolationException(
                "could not execute statement", new PSQLException(sqlMessage, PSQLState.UNIQUE_VIOLATION));
    }

    // --- isUniqueConstraintViolation ----------------------------------------

    @Test
    void recognisesTheConstraintEvenWhenItOnlyAppearsInTheRootCause() {
        // caso típico: a mensagem do Spring é genérica e o nome da constraint
        // só existe no PSQLException — casar só no topo classificaria a
        // corrida como defeito (ou o contrário)
        DataIntegrityViolationException ex = chained(
                "ERROR: duplicate key value violates unique constraint \"" + OPEN_RULE + "\"");

        assertThat(IntegrityViolations.isUniqueConstraintViolation(ex, OPEN_RULE)).isTrue();
        assertThat(IntegrityViolations.isUniqueConstraintViolation(ex, AUTO_OPEN)).isFalse();
    }

    @Test
    void otherConstraintInSameChainIsNotACorrelation() {
        // mesma pilha, outra violação: NOT NULL não é a corrida conhecida
        DataIntegrityViolationException ex = chained(
                "ERROR: null value in column \"incident_id\" violates not-null constraint");

        assertThat(IntegrityViolations.isUniqueConstraintViolation(ex, OPEN_RULE)).isFalse();
        assertThat(IntegrityViolations.isUniqueConstraintViolation(ex, AUTO_OPEN)).isFalse();
    }

    @Test
    void matchesNothingWhenConstraintNameIsMissingOrBlank() {
        DataIntegrityViolationException ex = chained(
                "ERROR: duplicate key value violates unique constraint \"" + OPEN_RULE + "\"");

        assertThat(IntegrityViolations.isUniqueConstraintViolation(ex, null)).isFalse();
        assertThat(IntegrityViolations.isUniqueConstraintViolation(ex, "")).isFalse();
        assertThat(IntegrityViolations.isUniqueConstraintViolation(null, OPEN_RULE)).isFalse();
    }

    @Test
    void nullExceptionIsNeverACorrelation() {
        assertThat(IntegrityViolations.isUniqueConstraintViolation(null, OPEN_RULE)).isFalse();
    }

    @Test
    void deepChainIsScannedWithoutHanging() {
        // 40 níveis — acima do teto de varredura a resposta é "não conheço",
        // o que EM DÚVIDA vira erro (falha-se para o lado seguro)
        Throwable root = chained(
                "ERROR: duplicate key value violates unique constraint \"" + OPEN_RULE + "\"");
        Throwable cursor = root;
        for (int i = 0; i < 40; i++) {
            cursor = new IllegalStateException("wrap " + i, cursor);
        }

        assertThat(IntegrityViolations.isUniqueConstraintViolation(cursor, OPEN_RULE))
                .as("além do teto: não classifica como corrida")
                .isFalse();
    }

    @Test
    void selfReferentialCauseStopsInsteadOfLooping() {
        Throwable cyclic = new CyclicException();
        assertThat(IntegrityViolations.isUniqueConstraintViolation(cyclic, OPEN_RULE)).isFalse();
    }

    // --- safeMessage ---------------------------------------------------------

    @Test
    void stripsDetailAndHintThatLeakTheCollidingRow() {
        // DETAIL traz o VALOR que colidiu — é o dado do usuário saindo no log
        DataIntegrityViolationException ex = chained(
                "ERROR: duplicate key value violates unique constraint \"" + OPEN_RULE + "\"\n"
                        + "  Detail: Key (alert_rule_id)=(11111111-1111-1111-1111-111111111111)"
                        + " already exists.\n"
                        + "  Hint: Looks like a concurrent insert.");

        assertThat(IntegrityViolations.safeMessage(ex))
                .doesNotContain("Detail:", "11111111-1111-1111-1111-111111111111", "Hint:")
                .contains("duplicate key value violates unique constraint \"" + OPEN_RULE + "\"");
    }

    @Test
    void truncatesLongMessagesAtTheLogCeiling() {
        DataIntegrityViolationException ex = chained("x".repeat(900));

        assertThat(IntegrityViolations.safeMessage(ex)).hasSize(300);
    }

    @Test
    void nullThrowableOrMessageIsEmptyNotNull() {
        assertThat(IntegrityViolations.safeMessage(null)).isEmpty();
        assertThat(IntegrityViolations.safeMessage(new IllegalStateException())).isEmpty();
    }

    /** Cadeia de causas que aponta para si mesma — a varredura precisa parar. */
    private static final class CyclicException extends RuntimeException {
        @Override
        public Throwable getCause() {
            return this;
        }
    }
}
