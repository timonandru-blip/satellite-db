package com.satdb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO комментария для UI (полиморфные заметки к записям + треды ответов).
 *
 * <p>Дерео строится плоским списком: дочерние ответы содержатся в
 * {@code childComments}, сериализация рекурсивная — глубина тредов в UI
 * ограничена на уровне контроллера.</p>
 */
public record CommentDto(
        Long id,
        @NotBlank(message = "Имя таблицы обязательно")
        String tableName,
        @NotNull(message = "ID записи обязателен")
        Long recordId,
        @Pattern(regexp = "TECHNICAL|INFORMATIONAL",
                message = "Тип комментария: TECHNICAL или INFORMATIONAL")
        String commentType,
        @Size(max = 200, message = "Заголовок не длиннее 200 символов")
        String title,
        @NotBlank(message = "Текст комментария обязателен")
        @Size(max = 4000, message = "Комментарий не длиннее 4000 символов")
        String content,
        Long parentCommentId,
        // ---- read-only ----
        String createdByUsername,
        String createdByRole,
        LocalDateTime createdAt,
        List<CommentDto> childComments
) {

    /** Форма добавления комментария ко любой записи. */
    public record Request(
            @NotBlank(message = "Имя таблицы обязательно")
            String tableName,
            @NotNull(message = "ID записи обязателен")
            Long recordId,
            @Pattern(regexp = "TECHNICAL|INFORMATIONAL")
            String commentType,
            @Size(max = 200)
            String title,
            @NotBlank(message = "Текст комментария обязателен")
            @Size(max = 4000)
            String content,
            Long parentCommentId
    ) {
    }
}
