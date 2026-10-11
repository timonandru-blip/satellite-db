package com.satdb.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Журнал аудита (таблица audit_log из skills/03-audit-trail/SKILL.md).
 * ВАЖНО: по требованиям audit_log защищён от изменений (только INSERT),
 * записи не удаляются — поэтому здесь НЕТ is_deleted и updated_at,
 * только changed_at (= created_at) и created_by (user_id).
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_table_record", columnList = "table_name,record_id"),
        @Index(name = "idx_audit_user", columnList = "user_id"),
        @Index(name = "idx_audit_date", columnList = "changed_at"),
        @Index(name = "idx_audit_action", columnList = "action")
})
public class AuditLog {

    /** Действие согласно CHECK (action IN ('INSERT','UPDATE','DELETE','RESTORE')). */
    public enum AuditAction {
        INSERT, UPDATE, DELETE, RESTORE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_name", nullable = false, length = 50)
    private String tableName;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 10)
    private AuditAction action;

    /** JSON предыдущего состояния. */
    @Column(name = "old_values", columnDefinition = "TEXT")
    private String oldValues;

    /** JSON нового состояния. */
    @Column(name = "new_values", columnDefinition = "TEXT")
    private String newValues;

    /** Список изменённых полей (CSV). */
    @Column(name = "changed_fields", columnDefinition = "TEXT")
    private String changedFields;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_audit_user"))
    private User user;

    /** Денормализованный снимок имени пользователя (запись остаётся даже при удалении user). */
    @Column(name = "user_name", length = 50)
    private String userName;

    /** Denormalized снимок роли пользователя на момент изменения. */
    @Column(name = "user_role", length = 20)
    private String userRole;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    /** Причина изменения. */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    /** Если изменение одобрено другим пользователем. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by", foreignKey = @ForeignKey(name = "fk_audit_approved_by"))
    private User approvedBy;

    // changed_at заполняется при создании, если не задан явно
    @jakarta.persistence.PrePersist
    void onCreate() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public AuditAction getAction() {
        return action;
    }

    public void setAction(AuditAction action) {
        this.action = action;
    }

    public String getOldValues() {
        return oldValues;
    }

    public void setOldValues(String oldValues) {
        this.oldValues = oldValues;
    }

    public String getNewValues() {
        return newValues;
    }

    public void setNewValues(String newValues) {
        this.newValues = newValues;
    }

    public String getChangedFields() {
        return changedFields;
    }

    public void setChangedFields(String changedFields) {
        this.changedFields = changedFields;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserRole() {
        return userRole;
    }

    public void setUserRole(String userRole) {
        this.userRole = userRole;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(LocalDateTime changedAt) {
        this.changedAt = changedAt;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public User getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(User approvedBy) {
        this.approvedBy = approvedBy;
    }
}
