package com.sentinel.organization.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST /api/v1/organizations/{organizationId}/members.
 *
 * <p>{@code role} é OBRIGATÓRIO e explícito (sem default implícito de
 * VIEWER): decidir o papel de alguém por omissão esconde permissão em
 * convenção — quem adiciona precisa nomear o papel que está concedendo.</p>
 *
 * <p>O papel chega como <b>string</b> e é convertido no service: um valor
 * fora do domínio (ADMIN/OPERATOR/VIEWER) vira 400 VALIDATION_ERROR com
 * mensagem de campo, e não um erro 500 de desserialização de enum (que o
 * handler genérico produziria).</p>
 */
public record AddMemberRequest(
        @Email @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(max = 32) String role) {
}
