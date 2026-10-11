package com.satdb.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO радиоизлучения для UI (REST + JavaFX).
 *
 * <p>Содержит только поля, необходимые интерфейсу согласно ТЗ: название, код,
 * частота, модуляция, мощность, приоритет — плюс минимальный контекст
 * (система, классификация, измерения) и read-only аудиторные поля.
 * passwordHash / lazy-ассоциации сущностей сюда не попадают намеренно.</p>
 *
 * <p>record — immutable carrier для чтения (toDto); для ввода используется
 * {@link Request} со стеком jakarta.validation.</p>
 */
public record RadioEmissionDto(
        Long id,
        Long satelliteSystemId,
        String satelliteSystemName,
        String satelliteSystemCode,
        @NotBlank(message = "Название излучения обязательно")
        @Size(max = 150, message = "Название не длиннее 150 символов")
        String emissionName,
        @NotBlank(message = "Код излучения обязателен")
        @Size(min = 1, max = 50, message = "Код от 1 до 50 символов")
        String emissionCode,
        @NotNull(message = "Частота обязательна")
        @DecimalMin(value = "0.0", inclusive = false, message = "Частота должна быть положительной")
        @DecimalMax(value = "400000.0", message = "Частота вне диапазона РЭС (≤ 400 000 МГц)")
        BigDecimal frequencyMhz,
        @DecimalMin(value = "0.0", inclusive = false, message = "Ширина полосы должна быть положительной")
        BigDecimal bandwidthKhz,
        @Size(max = 50)
        String modulationType,
        BigDecimal powerDbm,
        @Size(max = 20)
        String polarization,
        @Size(max = 50)
        String signalType,
        Integer priority,
        String classification,
        LocalDateTime measurementDate,
        @Size(max = 150)
        String measurementLocation,
        @Size(max = 2000)
        String notes,
        // ---- read-only аудит (для отображения в UI) ----
        String createdByUsername,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * Входная форма создания/изменения излучения. Валидируется декларативно
     * перед попаданием в сервис; поля аудита здесь отсутствуют по определению.
     */
    public record Request(
            @NotNull(message = "Спутниковая система обязательна")
            Long satelliteSystemId,
            @NotBlank(message = "Название излучения обязательно")
            @Size(max = 150)
            String emissionName,
            @NotBlank(message = "Код излучения обязателен")
            @Size(max = 50)
            String emissionCode,
            @NotNull(message = "Частота обязательна")
            @DecimalMin(value = "0.0", inclusive = false, message = "Частота должна быть положительной")
            @DecimalMax(value = "400000.0", message = "Частота вне диапазона РЭС (≤ 400 000 МГц)")
            BigDecimal frequencyMhz,
            @DecimalMin(value = "0.0", inclusive = false, message = "Ширина полосы должна быть положительной")
            BigDecimal bandwidthKhz,
            @Size(max = 50)
            String modulationType,
            BigDecimal powerDbm,
            @Size(max = 20)
            String polarization,
            @Size(max = 50)
            String signalType,
            Integer priority,
            String classification,
            LocalDateTime measurementDate,
            @Size(max = 150)
            String measurementLocation,
            @Size(max = 2000)
            String notes,
            String technicalParameters
    ) {
    }
}
