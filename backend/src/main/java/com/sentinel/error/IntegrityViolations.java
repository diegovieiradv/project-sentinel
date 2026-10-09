package com.sentinel.error;

import java.util.regex.Pattern;

/**
 * Classificação de violações de integridade vinda do banco (Prompt 17).
 *
 * <p><b>Por que caminhar a cadeia de causas:</b> quem detecta uma corrida
 * benigna precisa reconhecer o NOME da constraint violada
 * ({@code uq_alerts_open_rule}, {@code uq_incidents_auto_open_per_service}).
 * O nome nem sempre está em {@code DataIntegrityViolationException.getMessage()}
 * — depende de como o tradutor de exceção montou a mensagem — mas SEMPRE
 * está no {@code PSQLException} no fundo da pilha
 * ({@code ERROR: duplicate key value violates unique constraint "..."}). Casar
 * só contra a mensagem de topo transformaria uma corrida benigna em erro
 * (log de ERROR/mensagem engolida) ou, no sentido inverso, um erro real em
 * "corrida".</p>
 *
 * <p><b>Reconhecer é específico, não genérico:</b> a pergunta é "esta
 * EXATA constraint foi violada?", não "é alguma violação de integridade?" —
 * um {@code NOT NULL} ou um {@code CHECK} estourando no mesmo caminho é um
 * defeito e deve continuar subindo como erro.</p>
 *
 * <p><b>Log sem vazar dado:</b> a mensagem cru do Postgres traz
 * {@code DETAIL: Key (coluna)=(valor) already exists} — o valor da linha que
 * colidiu. {@link #safeMessage} corta {@code DETAIL}/{@code HINT} e trunca
 * antes de qualquer log.</p>
 */
public final class IntegrityViolations {

    /**
     * {@code Detail:} / {@code Hint:} do Postgres podem conter os VALORES da
     * linha que colidiu — nunca vão para o log.
     */
    private static final Pattern SENSITIVE_TAIL =
            Pattern.compile("\\s+(?:Detail|Hint)\\s*:.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Teto da mensagem logada — o suficiente para diagnosticar, sem eco. */
    private static final int MAX_LOG_MESSAGE_LENGTH = 300;

    /** Profundidade máxima da varredura (defesa contra cadeia malformada). */
    private static final int MAX_CAUSE_DEPTH = 16;

    private IntegrityViolations() {
    }

    /**
     * {@code true} quando a cadeia de causas de {@code ex} nomeia exatamente
     * {@code constraintName} — a assinatura da corrida benigna.
     *
     * @param ex             exceção recebida (tipicamente
     *                       {@code DataIntegrityViolationException})
     * @param constraintName nome da constraint parcial única
     * @return {@code true} só para ESTA constraint
     */
    public static boolean isUniqueConstraintViolation(Throwable ex, String constraintName) {
        if (constraintName == null || constraintName.isEmpty()) {
            return false;
        }
        Throwable cursor = ex;
        for (int depth = 0; cursor != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String message = cursor.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
            Throwable next = cursor.getCause();
            if (next == cursor) {
                break;
            }
            cursor = next;
        }
        return false;
    }

    /**
     * Mensagem própria para log: a causa mais específica (o texto original do
     * banco), sem {@code DETAIL}/{@code HINT} e truncada no teto.
     *
     * @param ex exceção a descrever
     * @return texto seguro; vazio quando não há mensagem
     */
    public static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "";
        }
        Throwable root = ex;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable next = root.getCause();
            if (next == null || next == root) {
                break;
            }
            root = next;
        }
        String message = root.getMessage();
        if (message == null) {
            message = ex.getMessage();
        }
        if (message == null) {
            return "";
        }
        String cleaned = SENSITIVE_TAIL.matcher(message).replaceAll("").trim();
        if (cleaned.length() <= MAX_LOG_MESSAGE_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_LOG_MESSAGE_LENGTH);
    }
}
