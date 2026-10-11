package com.satdb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * DTO заявки на изменение (pending_changes) для UI согласований.
 *
 * <p>proposedValues/currentValues передаются как JSON-строки «как есть» —
 * их парсит ApprovalService/клиент, маппер не дублирует бизнес-логику.</p>
 */
public record PendingChangeDto(
        Long id,
        @NotBlank(message = "Имя таблицы обязательно")
        String tableName,
        @NotNull(message = "ID записи обязателен")
        Long recordId,
        @Pattern(regexp = "UPDATE|DELETE", message = "Действие: UPDATE или DELETE")
        String action,
        String proposedValues,
        String currentValues,
        @NotBlank(message = "Обоснование обязательно")
        @Size(max = 1000, message = "Обоснование не длиннее 1000 символов")
        String reason,
        // ---- read-only статусная информация ----
        String status,
        String requestedByUsername,
        String requestedUserRole,
        LocalDateTime requestedAt,
        String reviewedByUsername,
        LocalDateTime reviewedAt,
        String reviewComment,
        LocalDateTime executedAt
) {

    /** Форма запроса изменения оператором (POST /api/.../request-change). */
    public record Request(
            @NotBlank(message = "Имя таблицы обязательно")
            String tableName,
            @NotNull(message = "ID записи обязателен")
            Long recordId,
            @Pattern(regexp = "UPDATE|DELETE", message = "Действие: UPDATE или DELETE")
            String action,
            String proposedValues,
            @NotBlank(message = "Обоснование обязательно")
            @Size(max = 1000)
            String reason
    ) {
    }

    /** Форма ревью заявки supervisor/admin. */
    public record ReviewRequest(
            @NotNull(message = "Решение обязательно")
            Boolean approved,
            @Size(max = 1000)
            String comment
    ) {
    }
}
