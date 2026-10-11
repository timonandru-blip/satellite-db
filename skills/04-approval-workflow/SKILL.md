---
name: approval-workflow
description: Workflow согласования изменений для операторов
version: 1.0
tags: [workflow, approval, operator, business-logic]
triggers:
  - согласование
  - запрос на изменение
  - pending change
  - одобрить
  - отклонить
---

# Approval Workflow Skill

## Назначение
Реализация бизнес-процесса: оператор не может напрямую изменять/удалять записи, 
а создаёт запрос на согласование, который рассматривает Начальник смены или Админ.

## Когда применять
- Оператор хочет изменить/удалить запись
- Начальник смены рассматривает запрос
- Нужно отклонить запрос с комментарием
- Нужно отменить собственный запрос

## Состояния запроса (state machine)

```
         ┌──────────────────────────────────┐
         │                                  │
         ▼                                  │
   ┌──────────┐   approve   ┌──────────┐    │
   │ PENDING  │────────────▶│ APPROVED │    │
   └──────────┘             └──────────┘    │
         │                                  │
         │ reject                           │ cancel
         ▼                                  │
   ┌──────────┐                             │
   │ REJECTED │                             │
   └──────────┘                             │
         │                                  │
         └──────────────────────────────────┘
```

## Схема pending_changes

```sql
CREATE TABLE pending_changes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    
    -- Что меняем
    table_name VARCHAR(50) NOT NULL,
    record_id INTEGER NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('UPDATE', 'DELETE')),
    
    -- Предложенные изменения
    proposed_values TEXT NOT NULL,  -- JSON
    current_values TEXT,            -- JSON (снимок на момент запроса)
    
    -- Кто запросил
    requested_by INTEGER NOT NULL REFERENCES users(id),
    requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reason TEXT NOT NULL,           -- Обязательное обоснование
    
    -- Статус согласования
    status VARCHAR(20) DEFAULT 'PENDING' 
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    
    -- Кто рассмотрел
    reviewed_by INTEGER REFERENCES users(id),
    reviewed_at TIMESTAMP,
    review_comment TEXT,
    
    -- Исполнение
    executed_at TIMESTAMP,
    audit_log_id INTEGER REFERENCES audit_log(id)
);

-- Индексы
CREATE INDEX idx_pending_status ON pending_changes(status);
CREATE INDEX idx_pending_table_record ON pending_changes(table_name, record_id);
CREATE INDEX idx_pending_requester ON pending_changes(requested_by);
CREATE INDEX idx_pending_reviewer ON pending_changes(reviewed_by);
```

## Сервис согласований

```java
@Service
@Transactional
public class ApprovalService {
    
    @Autowired private PendingChangeRepository repository;
    @Autowired private AuditService auditService;
    @Autowired private EmissionService emissionService;
    
    /**
     * Оператор создаёт запрос на изменение
     */
    public PendingChange requestChange(
        Long requesterId,
        String tableName,
        Long recordId,
        String action,
        Map<String, Object> proposedValues,
        String reason
    ) {
        // Валидация
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("Обоснование обязательно");
        }
        
        // Проверка: нет ли уже PENDING запроса на эту запись
        if (repository.hasPendingForRecord(tableName, recordId)) {
            throw new ConflictException("Уже есть незавершённый запрос на эту запись");
        }
        
        PendingChange change = new PendingChange();
        change.setTableName(tableName);
        change.setRecordId(recordId);
        change.setAction(action);
        change.setProposedValues(JsonUtils.toJson(proposedValues));
        change.setCurrentValues(JsonUtils.toJson(getCurrentValues(tableName, recordId)));
        change.setRequestedBy(requesterId);
        change.setReason(reason);
        change.setStatus(ChangeStatus.PENDING);
        
        return repository.save(change);
    }
    
    /**
     * Начальник смены / Админ рассматривает запрос
     */
    public void reviewChange(Long reviewerId, Long changeId, boolean approved, String comment) {
        PendingChange change = repository.findById(changeId)
            .orElseThrow(() -> new NotFoundException(changeId));
        
        // Проверка статуса
        if (change.getStatus() != ChangeStatus.PENDING) {
            throw new IllegalStateException("Запрос уже рассмотрен");
        }
        
        // Проверка прав: ревьюер не может быть тем же, кто запросил
        if (change.getRequestedBy().equals(reviewerId)) {
            throw new AccessDeniedException("Нельзя согласовать собственный запрос");
        }
        
        change.setStatus(approved ? ChangeStatus.APPROVED : ChangeStatus.REJECTED);
        change.setReviewedBy(reviewerId);
        change.setReviewedAt(LocalDateTime.now());
        change.setReviewComment(comment);
        
        if (approved) {
            executeChange(change, reviewerId);
        }
        
        repository.save(change);
    }
    
    /**
     * Оператор отменяет свой запрос
     */
    public void cancelChange(Long requesterId, Long changeId) {
        PendingChange change = repository.findById(changeId)
            .orElseThrow();
        
        if (!change.getRequestedBy().equals(requesterId)) {
            throw new AccessDeniedException("Можно отменять только свои запросы");
        }
        if (change.getStatus() != ChangeStatus.PENDING) {
            throw new IllegalStateException("Можно отменять только PENDING запросы");
        }
        
        change.setStatus(ChangeStatus.CANCELLED);
        repository.save(change);
    }
    
    /**
     * Применение одобренного изменения
     */
    private void executeChange(PendingChange change, Long executorId) {
        Map<String, Object> values = JsonUtils.fromJson(change.getProposedValues());
        
        Long auditId = switch (change.getAction()) {
            case "UPDATE" -> emissionService.update(change.getRecordId(), values, executorId, 
                "Одобрено: " + change.getReviewComment());
            case "DELETE" -> emissionService.softDelete(change.getRecordId(), executorId,
                "Одобрено: " + change.getReviewComment());
            default -> throw new IllegalStateException();
        };
        
        change.setExecutedAt(LocalDateTime.now());
        change.setAuditLogId(auditId);
    }
}
```

## Уведомления (внутренние)

Поскольку система offline, уведомления — через UI:

```java
@Service
public class NotificationService {
    
    // При создании PENDING — добавить в список для Supervisor/Admin
    public void notifyPendingCreated(PendingChange change) {
        // Сохранить в таблицу notifications или показать в UI
    }
    
    // При одобрении/отклонении — уведомить оператора
    public void notifyReviewResult(PendingChange change) {
        // Показать оператору в его списке запросов
    }
}
```

## Диалог запроса изменения (для оператора)

```java
public class ChangeRequestDialog extends Dialog<ChangeRequestDto> {
    
    private TextArea reasonField;
    private Map<String, Control> changeFields;
    
    public ChangeRequestDialog(RadioEmission current) {
        setTitle("Запрос на изменение");
        
        // Поля для изменений (предзаполнены текущими значениями)
        changeFields = buildChangeFields(current);
        
        // Обязательное обоснование
        reasonField = new TextArea();
        reasonField.setPromptText("Обоснуйте необходимость изменения...");
        
        getDialogPane().setContent(buildContent());
        
        // Валидация
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(ActionEvent.ACTION, e -> {
            if (reasonField.getText().isBlank()) {
                showError("Обоснование обязательно");
                e.consume();
            }
        });
    }
}
```

## UI для Начальника смены

```java
public class PendingChangesView {
    
    private TableView<PendingChange> tableView;
    
    private void initialize() {
        tableView.setItems(pendingChangeService.getPendingForReview(currentUserId));
        
        // Колонки: дата, кто запросил, таблица, запись, действие, обоснование
    }
    
    @FXML
    private void onApprove() {
        PendingChange selected = tableView.getSelectionModel().getSelectedItem();
        String comment = showCommentDialog("Комментарий к одобрению (опционально)");
        approvalService.reviewChange(currentUserId, selected.getId(), true, comment);
        refresh();
    }
    
    @FXML
    private void onReject() {
        PendingChange selected = tableView.getSelectionModel().getSelectedItem();
        String comment = showCommentDialog("Причина отклонения (обязательно)");
        if (comment == null || comment.isBlank()) {
            showError("Укажите причину отклонения");
            return;
        }
        approvalService.reviewChange(currentUserId, selected.getId(), false, comment);
        refresh();
    }
}
```

## Чек-лист
- [ ] Оператор физически не может обойти workflow?
- [ ] Нельзя согласовать собственный запрос?
- [ ] Нельзя изменить/удалить запись с PENDING запросом?
- [ ] Обоснование обязательно?
- [ ] Причина отклонения обязательна?
- [ ] После одобрения — запись в audit_log?
- [ ] UI показывает статус запроса в реальном времени?
- [ ] Есть история всех запросов (включая отклонённые)?

## Связанные skills
- [02-rbac-implementation](../02-rbac-implementation/SKILL.md)
- [03-audit-trail](../03-audit-trail/SKILL.md)
- [10-javafx-ui](../10-javafx-ui/SKILL.md)