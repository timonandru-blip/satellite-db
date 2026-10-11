---
name: rbac-implementation
description: Реализация системы прав доступа с тремя ролями (Admin, Supervisor, Operator)
version: 1.0
tags: [security, rbac, java, spring, authorization]
triggers:
  - права доступа
  - роли пользователей
  - разграничение доступа
  - permission
---

# RBAC Implementation Skill

## Назначение
Реализация разграничения прав доступа для трёх ролей пользователей с учётом 
бизнес-требования: операторы не могут напрямую изменять/удалять записи.

## Когда применять
- Создание новой роли или изменение прав
- Защита endpoint'а аннотацией прав
- Реализация проверки доступа в сервисе
- Настройка UI-элементов в зависимости от роли

## Матрица прав

| Действие | ADMIN | SUPERVISOR | OPERATOR |
|----------|:-----:|:----------:|:--------:|
| Просмотр записей | да | да | да |
| Создание записей | да | да | да |
| Редактирование записей | да | да | через согласование |
| Удаление записей | да | да | через согласование |
| Восстановление удалённых | да | да | нет |
| Согласование изменений | да | да | нет |
| Управление пользователями | да | нет | нет |
| Просмотр audit_log | да | да | нет |
| Просмотр своих pending_changes | да | да | да |

## Ключевые бизнес-правила

### Правило 1: Оператор НЕ может напрямую изменять/удалять
Все изменения от оператора проходят через `pending_changes`:

```
Оператор → requestChange() → pending_changes (PENDING)
                                  ↓
Начальник смены → reviewChange() → применить / отклонить
```

### Правило 2: Начальник смены — полные права на данные
Но НЕ может управлять пользователями (это только ADMIN).

### Правило 3: Админ — полные права
Включая управление пользователями и просмотр audit_log.

## Схема таблицы users

```sql
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL 
        CHECK (role IN ('ADMIN', 'SHIFT_SUPERVISOR', 'OPERATOR')),
    email VARCHAR(100),
    is_active BOOLEAN DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_role ON users(role);
```

## Java-реализация

### 1. Enum ролей

```java
public enum UserRole {
    ADMIN,
    SHIFT_SUPERVISOR,
    OPERATOR;
    
    public boolean canEditDirectly() {
        return this == ADMIN || this == SHIFT_SUPERVISOR;
    }
    
    public boolean canManageUsers() {
        return this == ADMIN;
    }
    
    public boolean canViewAuditLog() {
        return this == ADMIN || this == SHIFT_SUPERVISOR;
    }
    
    public boolean canApproveChanges() {
        return this == ADMIN || this == SHIFT_SUPERVISOR;
    }
}
```

### 2. Enum прав (детализация)

```java
public enum Permission {
    VIEW_RECORDS,
    CREATE_RECORDS,
    EDIT_RECORDS_DIRECT,
    DELETE_RECORDS_DIRECT,
    RESTORE_RECORDS,
    REQUEST_CHANGES,
    APPROVE_CHANGES,
    MANAGE_USERS,
    VIEW_AUDIT_LOG,
    VIEW_OWN_PENDING
}
```

### 3. Защита endpoint'ов

```java
@RestController
@RequestMapping("/api/radio-emissions")
public class RadioEmissionController {
    
    // Создание — все роли
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SHIFT_SUPERVISOR', 'OPERATOR')")
    public RadioEmissionDto create(@RequestBody CreateDto dto) { ... }
    
    // Прямое редактирование — только Admin и Supervisor
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SHIFT_SUPERVISOR')")
    public RadioEmissionDto update(@PathVariable Long id, @RequestBody UpdateDto dto) { ... }
    
    // Запрос на изменение — для оператора
    @PostMapping("/{id}/request-change")
    @PreAuthorize("hasRole('OPERATOR')")
    public PendingChangeDto requestChange(@PathVariable Long id, @RequestBody ChangeRequestDto dto) { ... }
    
    // Согласование — только Admin и Supervisor
    @PostMapping("/pending/{changeId}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SHIFT_SUPERVISOR')")
    public void review(@PathVariable Long changeId, @RequestBody ReviewDto dto) { ... }
    
    // Управление пользователями — только Admin
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public UserDto createUser(@RequestBody CreateUserDto dto) { ... }
}
```

### 4. Сервисная проверка

```java
@Service
public class PermissionService {
    
    public void checkCanEditDirectly(Long userId) {
        User user = getUser(userId);
        if (!user.getRole().canEditDirectly()) {
            throw new AccessDeniedException(
                "Роль " + user.getRole() + " не может редактировать напрямую. " +
                "Создайте запрос на согласование."
            );
        }
    }
    
    public void routeEditRequest(Long userId, Long recordId, Map<String, Object> changes) {
        User user = getUser(userId);
        if (user.getRole().canEditDirectly()) {
            // Прямое редактирование
            emissionService.update(recordId, changes, userId);
        } else {
            // Запрос на согласование
            approvalService.requestChange(userId, "radio_emissions", recordId, "UPDATE", changes);
        }
    }
}
```

### 5. Глобальный обработчик исключений

```java
@RestControllerAdvice
public class SecurityExceptionHandler {
    
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleAccessDenied(AccessDeniedException ex) {
        return new ErrorResponse("ACCESS_DENIED", ex.getMessage());
    }
}
```

## UI-адаптация (JavaFX)

```java
public class UIPermissions {
    
    public static void applyRoleBasedVisibility(UserRole role, Node... editControls) {
        boolean canEditDirectly = role.canEditDirectly();
        for (Node node : editControls) {
            node.setVisible(canEditDirectly);
            node.setManaged(canEditDirectly);
        }
    }
    
    // Для оператора — кнопка "Редактировать" становится "Запросить изменение"
    public static Button createEditButton(UserRole role, Runnable onDirectEdit, Runnable onRequestChange) {
        Button btn = new Button();
        if (role.canEditDirectly()) {
            btn.setText("Редактировать");
            btn.setOnAction(e -> onDirectEdit.run());
        } else {
            btn.setText("Запросить изменение");
            btn.setOnAction(e -> onRequestChange.run());
        }
        return btn;
    }
}
```

## Чек-лист реализации
- [ ] Все endpoint'ы защищены `@PreAuthorize`?
- [ ] Оператор физически не может вызвать прямой edit/delete?
- [ ] Есть единая `PermissionService` для проверок?
- [ ] UI адаптируется под роль пользователя?
- [ ] Все отказы в доступе логируются в audit_log?
- [ ] Есть понятные сообщения об ошибках для пользователя?
- [ ] Написаны unit-тесты для каждой роли?

## Тест-кейсы

```java
@Test
void operatorShouldNotEditDirectly() {
    // Given: оператор
    // When: пытается вызвать PUT /api/radio-emissions/{id}
    // Then: получает 403 Forbidden
}

@Test
void operatorShouldCreatePendingChange() {
    // Given: оператор
    // When: запрашивает изменение записи
    // Then: создаётся запись в pending_changes со статусом PENDING
}

@Test
void supervisorShouldApprovePendingChange() {
    // Given: pending change от оператора
    // When: supervisor одобряет
    // Then: изменение применено, статус = APPROVED
}
```

## Связанные skills
- [04-approval-workflow](../04-approval-workflow/SKILL.md)
- [03-audit-trail](../03-audit-trail/SKILL.md)
- [10-javafx-ui](../10-javafx-ui/SKILL.md)