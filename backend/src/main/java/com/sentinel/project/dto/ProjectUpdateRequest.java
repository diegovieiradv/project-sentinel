package com.sentinel.project.dto;

/**
 * Payload de PATCH /api/v1/organizations/{organizationId}/projects/{projectId}
 * — atualização <b>parcial</b>.
 *
 * <p>Campo ausente ({@code null}) = <b>não altera</b>. Por isso NÃO há
 * {@code @NotBlank} aqui (diferente do create): a validação de nome vazio é
 * feita no service, porque só ele sabe distinguir "ausente" de "em branco".</p>
 *
 * <p>DTO <b>fechado</b>: não existem {@code id}, {@code organization},
 * {@code active} nem timestamps — atributos de servidor não podem ser
 * alterados por este endpoint ({@code active} tem rota própria,
 * {@code .../status}). {@code description} com valor vazio limpa a descrição;
 * {@code null} (ausente) a preserva.</p>
 *
 * <p>Tamanhos (120/1000) são validados no service, junto com o trim/blank —
 * a mesma regra do create, aplicada só quando o campo está presente.</p>
 */
public record ProjectUpdateRequest(String name, String description) {
}
