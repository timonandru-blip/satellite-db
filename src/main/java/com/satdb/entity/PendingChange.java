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
 * Запрос на изменение в очереди согласований
 * (таблица pending_changes из skills/04-approval-workflow/SKILL.md).
 * Оператор создаёт запрос (PENDING), Supervisor/Admin одобряет или отклоняет,
 * после исполнения создаётся запись audit_log и сохраняется ссылка auditLogId.
 */
@Entity
@Table(name = "pending_changes", indexes = {
        @Index(name = "idx_pending_status", columnList = "status"),
        @Index(name = "idx_pending_table_record", columnList = "table_name,record_id"),
        @Index(name = "idx_pending_requester", columnList = "requested_by"),
        @Index(name = "idx_pending_reviewer", columnList = "reviewed_by")
})
public class PendingChange extends AuditableEntity {

    /** Статус согласования согласно CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')). */
    public enum ChangeStatus {
        PENDING, APPROVED, REJECTED, CANCELLED
    }

    /** Тип операции согласно CHECK (action IN ('UPDATE','DELETE')). */
    public enum ChangeAction {
        UPDATE, DELETE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Целевая таблица (полиморфная ссылка как в comments). */
    @Column(name = "table_name", nullable = false, length = 50)
    private String tableName;

    /** ID целевой записи. */
    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 10)
    private ChangeAction action;

    /** JSON предложенных изменений (обязательное). */
    @Column(name = "proposed_values", nullable = false, columnDefinition = "TEXT")
    private String proposedValues;

    /** JSON снимка текущих значений на момент запроса. */
    @Column(name = "current_values", columnDefinition = "TEXT")
    private String currentValues;

    /** Кто запросил изменение (обязательное). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false,
            foreignKey = @ForeignKey(name = "fk_pending_requested_by"))
    private User requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    /** Обязательное обоснование. */
    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChangeStatus status = ChangeStatus.PENDING;

    /** Кто рассмотрел запрос. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by", foreignKey = @ForeignKey(name = "fk_pending_reviewed_by"))
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    /** Момент фактического применения изменения. */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** Ссылка на созданную при исполнении запись аудита. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audit_log_id", foreignKey = @ForeignKey(name = "fk_pending_audit_log"))
    private AuditLog auditLog;

    // requested_at заполняется при создании, если не задан явно
    @jakarta.persistence.PrePersist
    void onCreate() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
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

    public ChangeAction getAction() {
        return action;
    }

    public void setAction(ChangeAction action) {
        this.action = action;
    }

    public String getProposedValues() {
        return proposedValues;
    }

    public void setProposedValues(String proposedValues) {
        this.proposedValues = proposedValues;
    }

    public String getCurrentValues() {
        return currentValues;
    }

    public void setCurrentValues(String currentValues) {
        this.currentValues = currentValues;
    }

    public User getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(User requestedBy) {
        this.requestedBy = requestedBy;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public ChangeStatus getStatus() {
        return status;
    }

    public void setStatus(ChangeStatus status) {
        this.status = status;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(User reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    public void setAuditLog(AuditLog auditLog) {
        this.auditLog = auditLog;
    }
}
