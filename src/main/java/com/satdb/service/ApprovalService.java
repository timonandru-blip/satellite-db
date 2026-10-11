package com.satdb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.satdb.entity.AuditLog;
import com.satdb.entity.PendingChange;
import com.satdb.entity.User;
import com.satdb.exception.AccessDeniedException;
import com.satdb.exception.ConflictException;
import com.satdb.exception.NotFoundException;
import com.satdb.exception.ValidationException;
import com.satdb.repository.PendingChangeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Workflow согласований (SKILL.md 04-approval): оператор не может изменять/удалять
 * записи напрямую — он создаёт PendingChange, которое рассматривает Supervisor/Admin.
 * State machine: PENDING -> APPROVED | REJECTED | CANCELLED (reject/cancel -> повторный request возможен).
 */
@Service
@Transactional(readOnly = true)
public class ApprovalService {

    /** Таблицы, изменения которых проходят согласование. */
    private static final List<String> EDITABLE_TABLES = List.of(
            RadioEmissionService.TABLE_NAME, "satellite_systems");

    private final PendingChangeRepository pendingChangeRepository;
    private final PermissionService permissionService;
    private final RadioEmissionService radioEmissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ApprovalService(PendingChangeRepository pendingChangeRepository,
                           PermissionService permissionService,
                           RadioEmissionService radioEmissionService,
                           AuditService auditService,
                           ObjectMapper objectMapper) {
        this.pendingChangeRepository = pendingChangeRepository;
        this.permissionService = permissionService;
        this.radioEmissionService = radioEmissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ---- requestChange: оператор (или любой с REQUEST_CHANGES) создаёт заявку ----

    /**
     * Создаёт запрос на изменение/удаление записи.
     *
     * @param requesterId    автор заявки
     * @param tableName      целевая таблица (radio_emissions / satellite_systems)
     * @param recordId       id целевой записи
     * @param action         UPDATE или DELETE
     * @param proposedValues новые значения (для UPDATE); для DELETE может быть null/пусто
     * @param reason         обязательное обоснование
     */
    @Transactional
    public PendingChange requestChange(Long requesterId,
                                       String tableName,
                                       Long recordId,
                                       String action,
                                       Map<String, Object> proposedValues,
                                       String reason) {
        User requester = permissionService.getActiveUser(requesterId);
        permissionService.require(requester, PermissionService.Permission.REQUEST_CHANGES);

        validateRequest(tableName, recordId, action, reason);

        if (!EDITABLE_TABLES.contains(tableName)) {
            throw new ValidationException("Согласование доступно только для таблиц: " + EDITABLE_TABLES);
        }
        if (pendingChangeRepository.hasPendingForRecord(tableName, recordId)) {
            throw new ConflictException("На запись " + tableName + "#" + recordId
                    + " уже есть незавершённый (PENDING) запрос");
        }

        PendingChange change = new PendingChange();
        change.setTableName(tableName);
        change.setRecordId(recordId);
        change.setAction(PendingChange.ChangeAction.valueOf(action));
        change.setProposedValues(auditService.toJson(proposedValues == null ? Map.of() : proposedValues));
        change.setCurrentValues(auditService.toJson(currentSnapshot(tableName, recordId)));
        change.setRequestedBy(requester);
        change.setReason(reason.trim());
        change.setStatus(PendingChange.ChangeStatus.PENDING);

        return pendingChangeRepository.save(change);
    }

    // ---- reviewChange: supervisor/admin рассматривает заявку ----

    /**
     * Рассматривает заявку. При approve изменение немедленно исполняется и попадает в audit_log.
     * Правило SKILL.md 04: ревьюер не может быть автором заявки.
     */
    @Transactional
    public PendingChange reviewChange(Long reviewerId, Long changeId, boolean approved, String comment) {
        User reviewer = permissionService.getActiveUser(reviewerId);
        permissionService.checkCanApprove(reviewer.getId());

        PendingChange change = getChange(changeId);

        if (change.getStatus() != PendingChange.ChangeStatus.PENDING) {
            throw new IllegalStateException("Запрос уже рассмотрен (статус: " + change.getStatus() + ")");
        }
        if (change.getRequestedBy().getId().equals(reviewerId)) {
            throw new AccessDeniedException("Нельзя согласовать собственный запрос");
        }
        if (!approved && (comment == null || comment.isBlank())) {
            throw new ValidationException("При отклонении запроса комментарий обязателен");
        }

        change.setStatus(approved
                ? PendingChange.ChangeStatus.APPROVED
                : PendingChange.ChangeStatus.REJECTED);
        change.setReviewedBy(reviewer);
        change.setReviewedAt(LocalDateTime.now());
        change.setReviewComment(comment);

        if (approved) {
            executeChange(change, reviewer);
        }

        return pendingChangeRepository.save(change);
    }

    // ---- cancelChange: автор отзывает свою PENDING-заявку ----

    @Transactional
    public PendingChange cancelChange(Long requesterId, Long changeId) {
        User user = permissionService.getActiveUser(requesterId);
        PendingChange change = getChange(changeId);

        if (!change.getRequestedBy().getId().equals(requesterId)) {
            // админ может отменить любую заявку (common-sense дополнение матрицы 02)
            permissionService.require(user, PermissionService.Permission.MANAGE_USERS,
                    "Отменять можно только собственные запросы.");
        }
        if (change.getStatus() != PendingChange.ChangeStatus.PENDING) {
            throw new IllegalStateException("Отменять можно только PENDING-запросы");
        }

        change.setStatus(PendingChange.ChangeStatus.CANCELLED);
        change.setReviewedBy(user);
        change.setReviewedAt(LocalDateTime.now());
        return pendingChangeRepository.save(change);
    }

    // ---- Чтение очередей (VIEW_OWN_PENDING — все роли; очередь — supervisor/admin) ----

    public Page<PendingChange> pendingQueue(Pageable pageable) {
        return pendingChangeRepository.findByStatusOrderByRequestedAtDesc(
                PendingChange.ChangeStatus.PENDING, pageable);
    }

    public Page<PendingChange> myRequests(Long userId, Pageable pageable) {
        permissionService.getActiveUser(userId);
        return pendingChangeRepository.findByRequestedByIdOrderByRequestedAtDesc(userId, pageable);
    }

    public Page<PendingChange> reviewedByMe(Long reviewerId, Pageable pageable) {
        permissionService.checkCanApprove(reviewerId);
        return pendingChangeRepository.findReviewedChanges(reviewerId, pageable);
    }

    public boolean hasPending(String tableName, Long recordId) {
        return pendingChangeRepository.hasPendingForRecord(tableName, recordId);
    }

    public PendingChange getById(Long changeId) {
        return getChange(changeId);
    }

    // ---- internals ----

    /** Исполнение одобренного изменения + запись в audit_log (SKILL.md 04). */
    private void executeChange(PendingChange change, User executor) {
        Long auditLogId = switch (change.getAction()) {
            case UPDATE -> {
                Map<String, Object> values = fromJson(change.getProposedValues());
                if (RadioEmissionService.TABLE_NAME.equals(change.getTableName())) {
                    AuditLog log = auditService.record(change.getTableName(), change.getRecordId(),
                            AuditLog.AuditAction.UPDATE, executor,
                            fromJson(change.getCurrentValues()), values,
                            auditService.diffFields(fromJson(change.getCurrentValues()), values),
                            "Одобрено заявкой #" + change.getId() + ": " + nvl(change.getReviewComment()));
                    radioEmissionService.applyProposedValues(change.getRecordId(), values);
                    yield log.getId();
                }
                throw new ValidationException("UPDATE не поддерживается для таблицы: " + change.getTableName());
            }
            case DELETE -> {
                if (RadioEmissionService.TABLE_NAME.equals(change.getTableName())) {
                    yield radioEmissionService.softDelete(change.getRecordId(), executor.getId(),
                            "Одобрено заявкой #" + change.getId() + ": " + nvl(change.getReviewComment()));
                }
                throw new ValidationException("DELETE не поддерживается для таблицы: " + change.getTableName());
            }
        };

        change.setExecutedAt(LocalDateTime.now());
        if (auditLogId != null) {
            AuditLog ref = new AuditLog();
            ref.setId(auditLogId);
            change.setAuditLog(ref);
        }
    }

    private Map<String, Object> currentSnapshot(String tableName, Long recordId) {
        if (RadioEmissionService.TABLE_NAME.equals(tableName)) {
            return radioEmissionService.snapshot(radioEmissionService.findById(recordId));
        }
        throw new ValidationException("Неизвестная таблица: " + tableName);
    }

    private PendingChange getChange(Long changeId) {
        return pendingChangeRepository.findById(changeId)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена, id=" + changeId));
    }

    private void validateRequest(String tableName, Long recordId, String action, String reason) {
        if (tableName == null || tableName.isBlank()) {
            throw new ValidationException("Имя таблицы обязательно");
        }
        if (recordId == null) {
            throw new ValidationException("Id записи обязателен");
        }
        if (action == null) {
            throw new ValidationException("Действие (UPDATE/DELETE) обязательно");
        }
        try {
            PendingChange.ChangeAction.valueOf(action);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Допустимые действия: UPDATE, DELETE. Получено: " + action);
        }
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("Обоснование изменения обязательно (SKILL.md 04)");
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось прочитать JSON значений заявки", ex);
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
