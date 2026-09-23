package com.sentinel.error;

import org.springframework.http.HttpStatus;

/**
 * Exceção de aplicação com código de erro estável e status HTTP associado.
 */
public class ApiException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public ApiException(String errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public ApiException(String errorCode, String message, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
