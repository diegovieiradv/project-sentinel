package com.sentinel.project.monitor.model;

/**
 * Tipo de monitor — hoje existe apenas {@link #HTTP}.
 *
 * <p>É INVARIANTE do cadastro: nasce {@code HTTP} no construtor de domínio e
 * nenhum endpoint o altera (create/update não expõem o campo). O CHECK
 * {@code monitors_type_check} da migration V7 espelha o enum; quando outro
 * tipo de checagem existir (prompt futuro), a entrada entra aqui + migration
 * nova — nunca como string livre no payload.</p>
 */
public enum MonitorType {

    /** Checagem de fronteira HTTP/HTTPS por URL (única implementada). */
    HTTP
}
