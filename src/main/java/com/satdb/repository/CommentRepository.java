package com.satdb.repository;

import com.satdb.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Репозиторий комментариев (SKILL.md 01: таблица comments, полиморфная связь table_name + record_id).
 */
@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** Корневые комментарии записи (без родителя), новые сверху; без удалённых. */
    List<Comment> findByTableNameAndRecordIdAndParentCommentIsNullAndIsDeletedFalseOrderByCreatedAtDesc(
            String tableName, Long recordId);

    /** Все комментарии записи вместе с тредами. */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.tableName = :tableName
              AND c.recordId = :recordId
              AND c.isDeleted = false
            ORDER BY c.createdAt ASC
            """)
    List<Comment> findAllForRecord(@Param("tableName") String tableName,
                                   @Param("recordId") Long recordId);

    Page<Comment> findByTableNameAndRecordIdAndIsDeletedFalseOrderByCreatedAtDesc(
            String tableName, Long recordId, Pageable pageable);

    /** Комментарии пользователя за всё время (лента активности). */
    Page<Comment> findByCreatedByIdAndIsDeletedFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Полнотекстовый поиск комментариев по FTS5-индексу comments_fts (V5__add_comments.sql).
     */
    @Query(value = """
            SELECT c.* FROM comments c
            JOIN comments_fts fts ON fts.rowid = c.id
            WHERE comments_fts MATCH :ftsQuery
              AND c.is_deleted = 0
            ORDER BY rank
            """, nativeQuery = true)
    List<Comment> fullTextSearch(@Param("ftsQuery") String ftsQuery);
}
