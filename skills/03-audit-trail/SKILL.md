---
name: audit-trail
description: Реализация истории изменений для всех операций с данными
version: 1.0
tags: [audit, history, logging, compliance]
triggers:
  - история изменений
  - аудит
  - кто изменил
  - когда изменили
  - логирование
---

# Audit Trail Skill

## Назначение
Обеспечить полную прослеживаемость всех изменений в системе: кто, когда, что 
изменил, какие были старые/новые значения, по какой причине.

## Когда применять
- Добавление новой таблицы (нужно подключить аудит)
- Реализация требования "история изменений"
- Расследование инцидентов (кто удалил запись?)
- Compliance и отчётность

## Ключевые требования
1. Логировать ВСЕ изменения (INSERT, UPDATE, DELETE, RESTORE)
2. Сохранять старые и новые значения
3. Фиксировать пользователя, время, причину
4. Защищать audit_log от изменений (только INSERT)
5. Не удалять записи audit_log (даже при soft delete основной записи)

## Схема audit_log

```sql
CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    
    -- Что изменено
    table_name VARCHAR(50) NOT NULL,
    record_id INTEGER NOT NULL,
    action VARCHAR(10) NOT NULL 
        CHECK (action IN ('INSERT', 'UPDATE', 'DELETE', 'RESTORE')),
    
    -- Детали изменения
    old_values TEXT,              -- JSON предыдущего состояния
    new_values TEXT,              -- JSON нового состояния
    changed_fields TEXT,          -- Список изменённых полей (CSV)
    
    -- Кто изменил
    user_id INTEGER REFERENCES users(id),
    user_name VARCHAR(50),
    user_role VARCHAR(20),
    
    -- Когда
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Контекст
    reason TEXT,                  -- Причина изменения
    session_id VARCHAR(255),
    approved_by INTEGER REFERENCES users(id)  -- Если изменение одобрено
);

-- Индексы для быстрого поиска
CREATE INDEX idx_audit_table_record ON audit_log(table_name, record_id);
CREATE INDEX idx_audit_user ON audit_log(user_id);
CREATE INDEX idx_audit_date ON audit_log(changed_at);
CREATE INDEX idx_audit_action ON audit_log(action);
```

## Реализация через AOP

### 1. Аннотация @Auditable

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String tableName();
    String action();  // INSERT, UPDATE, DELETE, RESTORE
}
```

### 2. Aspect для автоматического логирования

```java
@Aspect
@Component
public class AuditAspect {
    
    @Autowired
    private AuditLogRepository auditLogRepository;
    
    @Autowired
    private SecurityContext securityContext;
    
    @AfterReturning(pointcut = "@annotation(auditable)", returning = "result")
    public void auditAfterReturn(JoinPoint joinPoint, Auditable auditable, Object result) {
        AuditLog log = new AuditLog();
        log.setTableName(auditable.tableName());
        log.setAction(auditable.action());
        log.setRecordId(extractRecordId(result));
        
        // Пользователь
        User currentUser = securityContext.getCurrentUser();
        log.setUserId(currentUser.getId());
        log.setUserName(currentUser.getUsername());
        log.setUserRole(currentUser.getRole().name());
        
        // Значения (для UPDATE — сравнить old/new)
        if ("UPDATE".equals(auditable.action())) {
            Object[] args = joinPoint.getArgs();
            log.setOldValues(toJson(extractOldValues(args)));
            log.setNewValues(toJson(extractNewValues(result)));
            log.setChangedFields(detectChangedFields(args));
        } else if ("INSERT".equals(auditable.action())) {
            log.setNewValues(toJson(result));
        } else if ("DELETE".equals(auditable.action())) {
            log.setOldValues(toJson(extractOldValues(joinPoint.getArgs())));
        }
        
        auditLogRepository.save(log);
    }
}
```

### 3. Использование в сервисах

```java
@Service
public class RadioEmissionService {
    
    @Auditable(tableName = "radio_emissions", action = "INSERT")
    public RadioEmission create(CreateDto dto, Long userId) {
        RadioEmission entity = mapper.toEntity(dto);
        entity.setCreatedBy(userId);
        entity.setCreatedAt(LocalDateTime.now());
        return repository.save(entity);
    }
    
    @Auditable(tableName = "radio_emissions", action = "UPDATE")
    public RadioEmission update(Long id, UpdateDto dto, Long userId, String reason) {
        RadioEmission existing = repository.findById(id).orElseThrow();
        
        mapper.updateEntity(existing, dto);
        existing.setUpdatedBy(userId);
        existing.setUpdatedAt(LocalDateTime.now());
        
        return repository.save(existing);
    }
    
    @Auditable(tableName = "radio_emissions", action = "DELETE")
    public void softDelete(Long id, Long userId, String reason) {
        RadioEmission entity = repository.findById(id).orElseThrow();
        entity.setIsDeleted(true);
        entity.setUpdatedBy(userId);
        repository.save(entity);
    }
    
    @Auditable(tableName = "radio_emissions", action = "RESTORE")
    public void restore(Long id, Long userId, String reason) {
        RadioEmission entity = repository.findById(id).orElseThrow();
        entity.setIsDeleted(false);
        repository.save(entity);
    }
}
```

## Запросы к audit_log

### История конкретной записи

```sql
SELECT * FROM audit_log 
WHERE table_name = 'radio_emissions' AND record_id = ?
ORDER BY changed_at DESC;
```

### Кто удалял записи за период

```sql
SELECT user_name, changed_at, record_id, old_values
FROM audit_log
WHERE action = 'DELETE' 
  AND changed_at BETWEEN ? AND ?
ORDER BY changed_at DESC;
```

### Все изменения конкретного пользователя

```sql
SELECT * FROM audit_log
WHERE user_id = ?
ORDER BY changed_at DESC
LIMIT 100;
```

### Статистика изменений

```sql
SELECT action, COUNT(*) as count
FROM audit_log
WHERE changed_at >= DATE('now', '-7 days')
GROUP BY action;
```

### Изменения за день по пользователям

```sql
SELECT user_name, user_role, COUNT(*) as changes_count
FROM audit_log
WHERE DATE(changed_at) = DATE('now')
GROUP BY user_id, user_name, user_role
ORDER BY changes_count DESC;
```

## Формат хранения old_values / new_values

Старые и новые значения хранятся в JSON:

```json
{
  "frequency_mhz": 1575.42,
  "modulation_type": "CDMA",
  "power_dbm": -120.5
}
```

changed_fields хранит список изменённых полей:

```
frequency_mhz,modulation_type
```

## Чек-лист
- [ ] Все CRUD-операции покрыты @Auditable?
- [ ] audit_log доступен только для INSERT (нет UPDATE/DELETE)?
- [ ] Сохраняются ли old_values/new_values в JSON?
- [ ] Фиксируется ли причина изменения (reason)?
- [ ] Есть ли UI для просмотра истории записи?
- [ ] Есть ли UI для просмотра audit_log (для Admin/Supervisor)?
- [ ] Написаны тесты на логирование?

## Производительность
- audit_log растёт быстро — предусмотреть архивацию
- Индексы на (table_name, record_id) и (changed_at) обязательны
- Не делать JOIN audit_log с основными таблицами в частых запросах
- Для больших объёмов — партиционирование по дате

## Связанные skills
- [02-rbac-implementation](../02-rbac-implementation/SKILL.md)
- [04-approval-workflow](../04-approval-workflow/SKILL.md)
- [05-sqlite-optimization](../05-sqlite-optimization/SKILL.md)