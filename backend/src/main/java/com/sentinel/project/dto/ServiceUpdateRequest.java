package com.sentinel.project.dto;

/**
 * Payload de PATCH
 * /api/v1/organizations/{organizationId}/projects/{projectId}/services/{serviceId}
 * — atualização <b>parcial</b>.
 *
 * <p>Campo ausente ({@code null}) = <b>não altera</b>. Por isso NÃO há
 * {@code @NotBlank} aqui (diferente do create): a validação de nome vazio é
 * feita no service, porque só ele sabe distinguir "ausente" de "em branco"
 * (mesmo critério de {@code ProjectUpdateRequest}).</p>
 *
 * <p>DTO <b>fechado</b>: não existem {@code id}, {@code project},
 * {@code active}, {@code status} nem timestamps — ativação tem rota própria
 * ({@code .../activation}) e o status operacional não é alterável por nenhum
 * endpoint deste prompt.</p>
 *
 * <p>Tamanhos (120/1000) são validados no service, junto com o trim/blank —
 * a mesma regra do create, aplicada só quando o campo está presente.</p>
 */
public record ServiceUpdateRequest(String name, String description) {
}
