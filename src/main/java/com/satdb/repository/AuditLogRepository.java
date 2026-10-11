package com.satdb.repository;

import com.satdb.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Репозиторий журнала аудита (SKILL.md 03-audit: таблица audit_log).
 * Журнал append-only: используются только save/чтение, удаления нет.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    // ---- История по конкретной записи ----

    /**
     * Полная история изменений одной записи (например, radio_emissions#42),
     * от новых к старым.
     */
    List<AuditLog> findByTableNameAndRecordIdOrderByChangedAtDesc(String tableName, Long recordId);

    /**
     * История изменений записи за период.
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.tableName = :tableName
              AND a.recordId = :recordId
              AND a.changedAt BETWEEN :from AND :to
            ORDER BY a.changedAt DESC
            """)
    List<AuditLog> findRecordHistory(@Param("tableName") String tableName,
                                     @Param("recordId") Long recordId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    // ---- Изменения за период ----

    /**
     * Все изменения за период по таблице (пагинация для UI/REST).
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.tableName = :tableName
              AND a.changedAt BETWEEN :from AND :to
            ORDER BY a.changedAt DESC
            """)
    Page<AuditLog> findChangesByPeriod(@Param("tableName") String tableName,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       Pageable pageable);

    /**
     * Все изменения за период без привязки к таблице.
     */
    Page<AuditLog> findByChangedAtBetweenOrderByChangedAtDesc(LocalDateTime from,
                                                               LocalDateTime to,
                                                               Pageable pageable);

    // ---- Дополнительные выборки ----

    /** Изменения конкретного пользователя за период. */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.user.id = :userId
              AND a.changedAt BETWEEN :from AND :to
            ORDER BY a.changedAt DESC
            """)
    List<AuditLog> findUserChanges(@Param("userId") Long userId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /** Записи, изменённые более N раз за период (индикатор конфликтов/частых правок). */
    @Query("""
            SELECT a.recordId FROM AuditLog a
            WHERE a.tableName = :tableName
              AND a.changedAt >= :since
            GROUP BY a.recordId
            HAVING COUNT(a.id) > :minCount
            """)
    List<Long> findFrequentlyChangedRecords(@Param("tableName") String tableName,
                                            @Param("since") LocalDateTime since,
                                            @Param("minCount") long minCount);
}
