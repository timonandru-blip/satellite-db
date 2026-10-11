---
name: database-design
description: Проектирование схемы SQLite для системы учёта спутниковых радиоизлучений
version: 1.0
tags: [database, sqlite, schema, satellite, design]
triggers:
  - создать таблицу
  - добавить поле
  - спроектировать схему
  - нормализация БД
---

# Database Design Skill

## Назначение
Помогает AI-агенту проектировать схему БД SQLite для системы учёта спутниковых 
систем и радиоизлучений с учётом специфики космической отрасли.

## Когда применять
- Пользователь просит создать/изменить таблицу
- Нужно добавить новое поле в существующую сущность
- Требуется спроектировать связь между таблицами
- Нужна консультация по нормализации
- Возникают вопросы о типах данных

## Контекст проекта
- **СУБД**: SQLite (локальный файл, offline)
- **Домен**: Спутниковые системы, радиоизлучения
- **Объём**: 100K - 10M записей
- **Ключевые сущности**: satellite_systems, radio_emissions, users, comments

## Правила проектирования

### 1. Обязательные поля для всех таблиц
Каждая таблица ДОЛЖНА содержать:

```sql
created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
is_deleted BOOLEAN DEFAULT 0,
created_by INTEGER REFERENCES users(id)
```

### 2. Стандарты именования
- **Таблицы**: snake_case, множественное число → `radio_emissions`
- **Поля**: snake_case → `frequency_mhz`, `measurement_date`
- **Индексы**: `idx_{table}_{column}` → `idx_emissions_frequency`
- **Ограничения**: `chk_{desc}`, `uq_{desc}`, `fk_{table}_{ref}`

### 3. Обязательное индексирование
Всегда индексировать:
- Foreign keys
- Поля в WHERE / JOIN / ORDER BY
- Поля для фильтрации (frequency, status, date)
- Поля для soft delete (`is_deleted`)

### 4. JSON-поля для гибкости
Использовать TEXT для JSON, когда структура может меняться:

```sql
orbital_parameters TEXT,
technical_parameters TEXT
```

Не индексировать JSON-поля напрямую — использовать generated columns.

### 5. Числовые параметры радиоизлучений
- Частота: `DECIMAL(15, 6)` — точность до 6 знаков после запятой
- Мощность: `DECIMAL(8, 2)` — dBm
- Полоса пропускания: `DECIMAL(10, 3)` — kHz

## Шаблон новой таблицы

```sql
CREATE TABLE {table_name} (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    
    -- Бизнес-поля
    name VARCHAR(200) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    status VARCHAR(30) DEFAULT 'ACTIVE',
    
    -- Audit-поля (ОБЯЗАТЕЛЬНО)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN DEFAULT 0,
    created_by INTEGER REFERENCES users(id),
    
    -- Ограничения
    CONSTRAINT chk_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'PLANNED'))
);

-- Обязательные индексы
CREATE INDEX idx_{table}_is_deleted ON {table}(is_deleted);
CREATE INDEX idx_{table}_created_at ON {table}(created_at);
CREATE INDEX idx_{table}_code ON {table}(code);
```

## Пример: таблица radio_emissions

```sql
CREATE TABLE radio_emissions (
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

CREATE INDEX idx_emissions_satellite ON radio_emissions(satellite_system_id);
CREATE INDEX idx_emissions_frequency ON radio_emissions(frequency_mhz);
CREATE INDEX idx_emissions_name ON radio_emissions(emission_name);
CREATE INDEX idx_emissions_date ON radio_emissions(measurement_date);
CREATE INDEX idx_emissions_classification ON radio_emissions(classification);
CREATE INDEX idx_emissions_deleted ON radio_emissions(is_deleted);
CREATE INDEX idx_emissions_sat_freq ON radio_emissions(satellite_system_id, frequency_mhz);
CREATE INDEX idx_emissions_freq_date ON radio_emissions(frequency_mhz, measurement_date);
```

## Пример: таблица satellite_systems

```sql
CREATE TABLE satellite_systems (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    system_name VARCHAR(200) NOT NULL,
    system_code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    orbital_parameters TEXT,
    frequency_band VARCHAR(50),
    launch_date DATE,
    status VARCHAR(30) DEFAULT 'ACTIVE' 
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'DECOMMISSIONED', 'PLANNED')),
    country VARCHAR(100),
    operator VARCHAR(200),
    
    created_by INTEGER REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN DEFAULT 0
);

CREATE INDEX idx_satellite_name ON satellite_systems(system_name);
CREATE INDEX idx_satellite_code ON satellite_systems(system_code);
CREATE INDEX idx_satellite_status ON satellite_systems(status);
CREATE INDEX idx_satellite_band ON satellite_systems(frequency_band);
CREATE INDEX idx_satellite_deleted ON satellite_systems(is_deleted);
```

## Пример: таблица comments

```sql
CREATE TABLE comments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    table_name VARCHAR(50) NOT NULL,
    record_id INTEGER NOT NULL,
    comment_type VARCHAR(20) NOT NULL 
        CHECK (comment_type IN ('TECHNICAL', 'INFORMATIONAL')),
    
    title VARCHAR(200),
    content TEXT NOT NULL,
    
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN DEFAULT 0,
    
    parent_comment_id INTEGER REFERENCES comments(id)
);

CREATE INDEX idx_comments_record ON comments(table_name, record_id);
CREATE INDEX idx_comments_type ON comments(comment_type);
CREATE INDEX idx_comments_author ON comments(created_by);
CREATE INDEX idx_comments_date ON comments(created_at);
```

## Типы связей

| Тип | Пример | Реализация |
|-----|--------|-----------|
| 1:1 | Спутник — паспорт | `passport_id INTEGER UNIQUE REFERENCES passports(id)` |
| 1:N | Спутник — излучения | `satellite_system_id REFERENCES satellite_systems(id)` |
| M:N | Излучение — теги | Промежуточная таблица `emission_tags` |

## Пример промежуточной таблицы (M:N)

```sql
CREATE TABLE emission_tags (
    emission_id INTEGER NOT NULL REFERENCES radio_emissions(id) ON DELETE CASCADE,
    tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (emission_id, tag_id)
);

CREATE INDEX idx_et_emission ON emission_tags(emission_id);
CREATE INDEX idx_et_tag ON emission_tags(tag_id);
```

## Чек-лист перед созданием таблицы
- [ ] Есть ли soft delete (`is_deleted`)?
- [ ] Есть ли audit-поля (`created_at`, `updated_at`, `created_by`)?
- [ ] Проиндексированы ли все FK?
- [ ] Проиндексированы ли поля для поиска/фильтрации?
- [ ] Выбраны ли правильные типы данных (особенно для частот)?
- [ ] Есть ли ограничения (CHECK, UNIQUE, NOT NULL)?
- [ ] Учтена ли возможность будущих изменений (JSON-поля)?
- [ ] Есть ли композитные индексы для типичных запросов?

## Антипаттерны
- Хранить CSV-значения в одном поле
- Использовать REAL для частот (потеря точности)
- Забывать индексировать FK
- Использовать `SELECT *` в продакшене
- Хранить производные данные (можно вычислить)
- Избыточная нормализация (слишком много JOIN)

## Связанные skills
- [05-sqlite-optimization](../05-sqlite-optimization/SKILL.md)
- [07-migration-strategy](../07-migration-strategy/SKILL.md)
- [03-audit-trail](../03-audit-trail/SKILL.md)