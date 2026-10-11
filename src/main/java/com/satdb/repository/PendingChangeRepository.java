package com.satdb.repository;

import com.satdb.entity.PendingChange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Репозиторий ожидающих изменений (SKILL.md 04-approval: таблица pending_changes).
 */
@Repository
public interface PendingChangeRepository extends JpaRepository<PendingChange, Long> {

    // ---- Проверка наличия PENDING для записи ----

    /**
     * Есть ли у записи заявка в статусе PENDING.
     * Сервис использует её для блокировки повторных правок, пока изменение
     * не согласовано/отклонено (в БД это дублирует частичный уникальный индекс V4).
     */
    boolean existsByTableNameAndRecordIdAndStatus(String tableName,
                                                  Long recordId,
                                                  PendingChange.ChangeStatus status);

    /** То же самое явным JPQL — удобно вызывать с константой статуса. */
    @Query("""
            SELECT COUNT(pc) > 0 FROM PendingChange pc
            WHERE pc.tableName = :tableName
              AND pc.recordId = :recordId
              AND pc.status = com.satdb.entity.PendingChange$ChangeStatus.PENDING
            """)
    boolean hasPendingForRecord(@Param("tableName") String tableName,
                                @Param("recordId") Long recordId);

    /** Активная (PENDING) заявка по записи — «по этой записи уже есть заявка». */
    Optional<PendingChange> findByTableNameAndRecordIdAndStatus(
            String tableName, Long recordId, PendingChange.ChangeStatus status);

    // ---- Очередь на согласование ----

    List<PendingChange> findByStatusOrderByRequestedAtAsc(PendingChange.ChangeStatus status);

    Page<PendingChange> findByStatusOrderByRequestedAtDesc(PendingChange.ChangeStatus status, Pageable pageable);

    long countByStatus(PendingChange.ChangeStatus status);

    /** Очередь PENDING только для указанных таблиц (editable tables из SKILL.md). */
    @Query("""
            SELECT pc FROM PendingChange pc
            WHERE pc.status = com.satdb.entity.PendingChange$ChangeStatus.PENDING
              AND pc.tableName IN :tableNames
            ORDER BY pc.requestedAt ASC
            """)
    List<PendingChange> findPendingQueueForTables(@Param("tableNames") List<String> tableNames);

    // ---- Выборки по инициатору / ревьюеру ----

    Page<PendingChange> findByRequestedByIdOrderByRequestedAtDesc(Long userId, Pageable pageable);

    @Query("""
            SELECT pc FROM PendingChange pc
            WHERE pc.reviewedBy.id = :reviewerId
              AND pc.status <> com.satdb.entity.PendingChange$ChangeStatus.PENDING
            ORDER BY pc.reviewedAt DESC
            """)
    Page<PendingChange> findReviewedChanges(@Param("reviewerId") Long reviewerId, Pageable pageable);

    /** Старые незавершённые заявки (для напоминаний/эскалации supervisor'у). */
    @Query("""
            SELECT pc FROM PendingChange pc
            WHERE pc.status = com.satdb.entity.PendingChange$ChangeStatus.PENDING
              AND pc.requestedAt < :olderThan
            ORDER BY pc.requestedAt ASC
            """)
    List<PendingChange> findStalePending(@Param("olderThan") LocalDateTime olderThan);
}
