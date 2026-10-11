package com.satdb.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * DTO пользователя для UI.
 *
 * <p><b>Безопасность:</b> passwordHash никогда не маппится из сущности в этот
 * DTO — наружу уходят только профильные поля. Пароль задаётся отдельно через
 * {@link CreateRequest} (и меняется через сервис UserService.changePassword).</p>
 */
public record UserDto(
        Long id,
        String username,
        String fullName,
        @NotNull(message = "Роль обязательна")
        @Pattern(regexp = "ADMIN|SHIFT_SUPERVISOR|OPERATOR",
                message = "Роль: ADMIN, SHIFT_SUPERVISOR или OPERATOR")
        String role,
        @Email(message = "Некорректный email")
        @Size(max = 150)
        String email,
        boolean active,
        LocalDateTime lastLogin,
        // ---- read-only аудит ----
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** Форма создания пользователя (только ADMIN). Пароль обязателен. */
    public record CreateRequest(
            @NotBlank(message = "Логин обязателен")
            @Size(min = 3, max = 50, message = "Логин от 3 до 50 символов")
            @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Логин: буквы, цифры, . _ -")
            String username,
            @NotBlank(message = "Пароль обязателен")
            @Size(min = 8, max = 100, message = "Пароль не короче 8 символов")
            String password,
            @NotBlank(message = "ФИО обязательно")
            @Size(max = 150)
            String fullName,
            @NotNull(message = "Роль обязательна")
            @Pattern(regexp = "ADMIN|SHIFT_SUPERVISOR|OPERATOR")
            String role,
            @Email
            @Size(max = 150)
            String email
    ) {
    }

    /** Форма редактирования профиля (пароль здесь не меняется). */
    public record UpdateRequest(
            @Size(max = 150)
            String fullName,
            @Pattern(regexp = "ADMIN|SHIFT_SUPERVISOR|OPERATOR",
                    message = "Роль: ADMIN, SHIFT_SUPERVISOR или OPERATOR")
            String role,
            @Email
            @Size(max = 150)
            String email,
            Boolean active
    ) {
    }
}
