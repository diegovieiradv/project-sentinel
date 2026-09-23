package com.sentinel.error;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Corpo padrão de erro da API: {"code","message","timestamp","path"}.
 * `path` é omitido do JSON quando nulo.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String code;
    private final String message;
    private final Instant timestamp;
    private final String path;

    public ErrorResponse(String code, String message, Instant timestamp, String path) {
        this.code = code;
        this.message = message;
        this.timestamp = timestamp;
        this.path = path;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getPath() {
        return path;
    }
}
