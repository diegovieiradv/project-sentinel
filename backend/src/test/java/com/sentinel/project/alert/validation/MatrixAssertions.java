package com.sentinel.project.alert.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinel.error.ApiException;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.http.HttpStatus;

/**
 * Asserções compartilhadas dos testes unitários da matriz (21.2–21.6).
 *
 * <p>Os cinco testes por tipo exercitam o MESMO contrato —
 * {@code ApiException} com {@code 400 VALIDATION_ERROR} e mensagem exata, ou
 * ausência de exceção — então a checagem vive em um único lugar em vez de
 * repetir {@code assertThatThrownBy} + casts em cada método.</p>
 */
final class MatrixAssertions {

    private MatrixAssertions() {
        // suporte de teste: instanciar nunca faz sentido
    }

    /** Condição REJEITADA: {@code ApiException} {@code 400} com a mensagem exata. */
    static void assertRejected(ThrowingCallable validation, String expectedMessage) {
        assertThatThrownBy(validation)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode())
                            .as("código de erro do shape padrão")
                            .isEqualTo("VALIDATION_ERROR");
                    assertThat(api.getHttpStatus())
                            .as("status HTTP")
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessage())
                            .as("mensagem do contrato")
                            .isEqualTo(expectedMessage);
                });
    }

    /** Condição ACEITA: a validação não lança nada. */
    static void assertAllowed(ThrowingCallable validation) {
        assertThatCode(validation).doesNotThrowAnyException();
    }
}
