---
name: migration-strategy
description: Стратегия миграций БД с использованием Flyway
version: 1.0
tags: [flyway, migration, database, versioning]
triggers:
  - миграция
  - изменить схему
  - flyway
  - версия БД
---

# Migration Strategy Skill

## Назначение
Управление изменениями схемы БД через версионные миграции Flyway с гарантией 
воспроизводимости и возможностью отката.

## Когда применять
- Добавление/изменение таблицы
- Добавление индекса
- Изменение типа поля
- Перенос данных

## Структура миграций

```
src/main/resources/db/migration/
├── V1__initial_schema.sql
├── V2__add_audit_log.sql
├── V3__create_fts_indexes.sql
├── V4__add_pending_changes.sql
├── V5__optimize_indexes.sql
└── ...
```

## Правила именования

```
V{version}__{description}.sql
│      │
│      └── snake_case описание
└── номер версии (1, 2, 3...)
```

## Шаблон миграции

```sql
-- V{N}__{description}.sql

-- Описание: что делает миграция
-- Автор: {имя}
-- Дата: {дата}

-- === Изменения схемы ===
CREATE TABLE IF NOT EXISTS new_table (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- поля
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN DEFAULT 0,
    created_by INTEGER REFERENCES users(id)
);

-- === Индексы ===
CREATE INDEX IF NOT EXISTS idx_new_table_field ON new_table(field);

-- === Данные (если нужно) ===
INSERT INTO lookup_table (name) VALUES ('value1'), ('value2');
```

## Пример: V1__initial_schema.sql

```sql
-- V1__initial_schema.sql
-- Создание начальной схемы БД

-- === Пользователи ===
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'SHIFT_SUPERVISOR', 'OPERATOR')),
    email VARCHAR(100),
    is_active BOOLEAN DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP
);

-- === Спутниковые системы ===
CREATE TABLE IF NOT EXISTS satellite_systems (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    system_name VARCHAR(200) NOT NULL,
    system_code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    orbital_parameters TEXT,
    frequency_band VARCHAR(50),
    launch_date DATE,
    status VARCHAR(30) DEFAULT 'ACTIVE',
    country VARCHAR(100),
    operator VARCHAR(200),
    created_by INTEGER REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN DEFAULT 0
);

-- === Радиоизлучения ===
CREATE TABLE IF NOT EXISTS radio_emissions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    satellite_system_id INTEGER NOT NULL REFERENCES satellite_systems(id),
    emission_name VARCHAR(200) NOT NULL,
    emission_code VARCHAR(50) NOT NULL,
    frequency_mhz DECIMAL(15, 6) NOT NULL,
    bandwidth_khz DECIMAL(10, 3),
    modulation_type VARCHAR(50),
    power_dbm DECIMAL(8, 2),
    polarization VARCHAR(20),
    signal_type VARCHAR(50),
    technical_parameters TEXT,
    measurement_date TIMESTAMP,
    measurement_location VARCHAR(200),
    priority INTEGER DEFAULT 0,
    classification VARCHAR(50),
    notes TEXT,
    created_by INTEGER REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN DEFAULT 0,
    CONSTRAINT uq_emission_code UNIQUE (satellite_system_id, emission_code)
);

-- === Индексы ===
CREATE INDEX IF NOT EXISTS idx_emissions_satellite ON radio_emissions(satellite_system_id);
CREATE INDEX IF NOT EXISTS idx_emissions_frequency ON radio_emissions(frequency_mhz);
CREATE INDEX IF NOT EXISTS idx_emissions_name ON radio_emissions(emission_name);
CREATE INDEX IF NOT EXISTS idx_emissions_deleted ON radio_emissions(is_deleted);
```

## Пример: V2__add_audit_log.sql

```sql
-- V2__add_audit_log.sql
-- Добавление таблицы аудита

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    table_name VARCHAR(50) NOT NULL,
    record_id INTEGER NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('INSERT', 'UPDATE', 'DELETE', 'RESTORE')),
    old_values TEXT,
    new_values TEXT,
    changed_fields TEXT,
    user_id INTEGER REFERENCES users(id),
    user_name VARCHAR(50),
    user_role VARCHAR(20),
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reason TEXT,
    session_id VARCHAR(255),
    approved_by INTEGER REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_audit_table_record ON audit_log(table_name, record_id);
CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_date ON audit_log(changed_at);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action);
```

## Конфигурация Flyway

```properties
# application.properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=true
spring.flyway.validate-on-migrate=true
spring.flyway.out-of-order=false
```

## Правила миграций

### Делать
- Каждая миграция — атомарна (одно логическое изменение)
- Использовать `IF EXISTS` / `IF NOT EXISTS`
- Тестировать миграцию на копии данных
- Писать миграции идемпотентными
- Документировать сложные миграции в комментариях

### Не делать
- Не изменять уже применённые миграции
- Не использовать `DROP TABLE` без крайней необходимости
- Не делать миграции слишком большими
- Не смешивать schema и data changes в одной миграции

## Откат миграций

Flyway Community не поддерживает автоматический rollback. Стратегия:

```sql
-- V{N+1}__rollback_V{N}.sql
-- Откат изменений из V{N}

DROP TABLE IF EXISTS table_to_remove;
-- или
ALTER TABLE table_name DROP COLUMN column_to_remove;
```

## Тестирование миграций

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class MigrationTest {
    
    @Autowired
    private Flyway flyway;
    
    @Test
    void allMigrationsShouldApplySuccessfully() {
        // Flyway запускается автоматически при старте контекста
        // Если есть ошибка — тест упадёт
        assertThat(flyway.info().current().getState())
            .isEqualTo(MigrationInfoState.APPLIED);
    }
}
```

## Чек-лист
- [ ] Миграция атомарна?
- [ ] Использованы IF EXISTS / IF NOT EXISTS?
- [ ] Протестирована на копии данных?
- [ ] Есть откатная миграция (если нужно)?
- [ ] Проиндексированы новые поля для поиска?
- [ ] Не нарушена обратная совместимость?

## Связанные skills
- [01-database-design](../01-database-design/SKILL.md)
- [08-testing-strategy](../08-testing-strategy/SKILL.md)\