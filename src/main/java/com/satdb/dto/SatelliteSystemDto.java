package com.satdb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * DTO спутниковой системы для UI: карточка системы + справочник для
 * выпадающих списков. Поле emissions намеренно отсутствует — список
 * излучений грузится отдельным запросом (RadioEmissionRepository),
 * чтобы не тянуть N+1 в таблицу.
 */
public record SatelliteSystemDto(
        Long id,
        @NotBlank(message = "Название системы обязательно")
        @Size(max = 150)
        String systemName,
        @NotBlank(message = "Код системы обязателен")
        @Size(max = 30)
        String systemCode,
        @Size(max = 1000)
        String description,
        String orbitalParameters,
        @Size(max = 20)
        String frequencyBand,
        LocalDate launchDate,
        String status,
        @Size(max = 100)
        String country,
        @Size(max = 150)
        String operatorName,
        // ---- read-only ----
        String createdByUsername,
        LocalDate createdAt,
        long activeEmissionsCount
) {

    /** Форма создания/редактирования системы (ADMIN / SHIFT_SUPERVISOR). */
    public record Request(
            @NotBlank(message = "Название системы обязательно")
            @Size(max = 150)
            String systemName,
            @NotBlank(message = "Код системы обязателен")
            @Size(max = 30)
            String systemCode,
            @Size(max = 1000)
            String description,
            String orbitalParameters,
            @Size(max = 20)
            String frequencyBand,
            LocalDate launchDate,
            String status,
            @Size(max = 100)
            String country,
            @Size(max = 150)
            String operatorName
    ) {
    }
}
