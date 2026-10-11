package com.satdb.service;

import com.satdb.entity.AuditLog;
import com.satdb.entity.User;
import com.satdb.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Низкоуровневая запись в audit_log (SKILL.md 03-audit).
 * Таблица append-only: сервис только создаёт записи, никогда не изменяет/удаляет.
 */
@Service
@Transactional(readOnly = true)
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Сохранить запись журнала. REQUIRES_NEW — аудит не должен откатываться
     * вместе с основной бизнес-транзакцией (требование надёжности SKILL.md 03).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog record(String tableName,
                           Long recordId,
                           AuditLog.AuditAction action,
                           User user,
                           Map<String, Object> oldValues,
                           Map<String, Object> newValues,
                           Collection<String> changedFields,
                           String reason) {
        AuditLog log = new AuditLog();
        log.setTableName(tableName);
        log.setRecordId(recordId);
        log.setAction(action);
        log.setUser(user);
        if (user != null) {
            // денормализованные поля (SKILL.md 03: имя/роль фиксируются на момент изменения)
            log.setUserName(user.getUsername());
            log.setUserRole(user.getRole() != null ? user.getRole().name() : null);
        }
        log.setOldValues(toJson(oldValues));
        log.setNewValues(toJson(newValues));
        log.setChangedFields(changedFields == null || changedFields.isEmpty()
                ? null : String.join(",", changedFields));
        log.setReason(reason);
        log.setChangedAt(LocalDateTime.now());
        return auditLogRepository.save(log);
    }

    /** Вычислить список изменённых полей по снимку «до» и объекту «после». */
    public Set<String> diffFields(Map<String, Object> oldSnapshot, Map<String, Object> updated) {
        Set<String> changed = new LinkedHashSet<>();
        if (oldSnapshot == null || updated == null) {
            return changed;
        }
        for (Map.Entry<String, Object> e : updated.entrySet()) {
            Object before = oldSnapshot.get(e.getKey());
            Object after = e.getValue();
            if (before == null ? after != null : !before.equals(after)) {
                changed.add(e.getKey());
            }
        }
        return changed;
    }

    /** История изменений конкретной записи (для UI/REST). */
    public List<AuditLog> historyOf(String tableName, Long recordId) {
        return auditLogRepository.findByTableNameAndRecordIdOrderByChangedAtDesc(tableName, recordId);
    }

    public String toJson(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Не удалось сериализовать значения для аудита", ex);
        }
    }
}
