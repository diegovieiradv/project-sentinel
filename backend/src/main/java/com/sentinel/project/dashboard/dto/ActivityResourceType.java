package com.sentinel.project.dashboard.dto;

/**
 * Tipo do recurso apontado por {@link ActivityItem#resourceId()} — separa
 * alerta de incidente para a UI navegar sem adivinhar pelo texto.
 */
public enum ActivityResourceType {
    ALERT,
    INCIDENT
}
