---
name: sqlite-optimization
description: Оптимизация SQLite для быстрого поиска и фильтрации
version: 1.0
tags: [sqlite, performance, optimization, fts5, pragma]
triggers:
  - медленно работает
  - оптимизация БД
  - быстрый поиск
  - индекс
  - pragma
---

# SQLite Optimization Skill

## Назначение
Настройка SQLite для максимальной производительности при поиске и фильтрации 
параметров радиоизлучений (ключевое требование проекта).

## Когда применять
- Поиск/фильтрация работают медленно
- Настройка новой БД
- Оптимизация существующей БД
- Внедрение полнотекстового поиска

## Обязательные PRAGMA настройки

```java
@Component
public class SQLiteOptimizer {
    
    @Autowired
    private DataSource dataSource;
    
    @PostConstruct
    public void optimize() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // 1. WAL mode — параллельное чтение/запись (КРИТИЧНО для 3 пользователей)
            stmt.execute("PRAGMA journal_mode=WAL");
            
            // 2. Увеличенный кэш страниц (по умолчанию 2000, ставим 10000)
            stmt.execute("PRAGMA cache_size=10000");
            
            // 3. Баланс скорости и надёжности
            stmt.execute("PRAGMA synchronous=NORMAL");
            
            // 4. Memory-mapped I/O для больших файлов
            stmt.execute("PRAGMA mmap_size=268435456");  // 256 MB
            
            // 5. Временные данные в RAM
            stmt.execute("PRAGMA temp_store=MEMORY");
            
            // 6. Обязательная проверка FK
            stmt.execute("PRAGMA foreign_keys=ON");
            
            // 7. Оптимизация busy timeout (30 сек)
            stmt.execute("PRAGMA busy_timeout=30000");
            
        } catch (SQLException e) {
            log.error("SQLite optimization failed", e);
        }
    }
}
```

## Индексы — стратегия

### Правило 1: Индексировать всё, что в WHERE/JOIN/ORDER BY

```sql
-- Обязательные индексы
CREATE INDEX idx_emissions_frequency ON radio_emissions(frequency_mhz);
CREATE INDEX idx_emissions_satellite ON radio_emissions(satellite_system_id);
CREATE INDEX idx_emissions_date ON radio_emissions(measurement_date);
CREATE INDEX idx_emissions_classification ON radio_emissions(classification);
CREATE INDEX idx_emissions_deleted ON radio_emissions(is_deleted);
```

### Правило 2: Композитные индексы для типичных запросов

```sql
-- Частый запрос: "покажи все излучения спутника X, отсортированные по частоте"
CREATE INDEX idx_emissions_sat_freq ON radio_emissions(satellite_system_id, frequency_mhz);

-- Частый запрос: "поиск по диапазону частот за период"
CREATE INDEX idx_emissions_freq_date ON radio_emissions(frequency_mhz, measurement_date);
```

### Правило 3: Частичные индексы

```sql
-- Индекс только для активных записей (экономия места)
CREATE INDEX idx_emissions_active ON radio_emissions(frequency_mhz) 
WHERE is_deleted = 0;
```

## Full-Text Search (FTS5)

### Создание виртуальной таблицы

```sql
CREATE VIRTUAL TABLE radio_emissions_fts USING fts5(
    emission_name,
    emission_code,
    notes,
    technical_parameters,
    content='radio_emissions',
    content_rowid='id',
    tokenize='unicode61'  -- Поддержка Unicode
);
```

### Триггеры синхронизации

```sql
-- INSERT
CREATE TRIGGER emissions_fts_insert AFTER INSERT ON radio_emissions BEGIN
    INSERT INTO radio_emissions_fts(rowid, emission_name, emission_code, notes, technical_parameters)
    VALUES (new.id, new.emission_name, new.emission_code, new.notes, new.technical_parameters);
END;

-- DELETE
CREATE TRIGGER emissions_fts_delete AFTER DELETE ON radio_emissions BEGIN
    INSERT INTO radio_emissions_fts(radio_emissions_fts, rowid, emission_name, emission_code, notes, technical_parameters)
    VALUES ('delete', old.id, old.emission_name, old.emission_code, old.notes, old.technical_parameters);
END;

-- UPDATE
CREATE TRIGGER emissions_fts_update AFTER UPDATE ON radio_emissions BEGIN
    INSERT INTO radio_emissions_fts(radio_emissions_fts, rowid, emission_name, emission_code, notes, technical_parameters)
    VALUES ('delete', old.id, old.emission_name, old.emission_code, old.notes, old.technical_parameters);
    INSERT INTO radio_emissions_fts(rowid, emission_name, emission_code, notes, technical_parameters)
    VALUES (new.id, new.emission_name, new.emission_code, new.notes, new.technical_parameters);
END;
```

### Использование в Java

```java
@Query(value = """
    SELECT r.* FROM radio_emissions r
    JOIN radio_emissions_fts fts ON r.id = fts.rowid
    WHERE radio_emissions_fts MATCH :searchTerm
    AND r.is_deleted = 0
    ORDER BY rank
    """, nativeQuery = true)
List<RadioEmission> fullTextSearch(@Param("searchTerm") String searchTerm);
```

Примеры запросов:
- `"частота 1000"` — поиск по всем полям
- `"emission_name:GPS"` — поиск только в названии
- `"код AND сигнал"` — булев поиск

## Оптимизация запросов

### Плохо: SELECT *

```java
// Загружает все поля, включая большие JSON
@Query("SELECT r FROM RadioEmission r WHERE r.frequencyMhz BETWEEN :from AND :to")
List<RadioEmission> findByFrequencyRange(BigDecimal from, BigDecimal to);
```

### Хорошо: только нужные поля

```java
@Query("SELECT r.id, r.emissionName, r.frequencyMhz, r.measurementDate " +
       "FROM RadioEmission r " +
       "WHERE r.frequencyMhz BETWEEN :from AND :to " +
       "AND r.isDeleted = false " +
       "ORDER BY r.frequencyMhz")
List<Object[]> findBasicByFrequencyRange(BigDecimal from, BigDecimal to);
```

### Пагинация обязательна

```java
@Query("SELECT r FROM RadioEmission r WHERE r.isDeleted = false")
Page<RadioEmission> findAllActive(Pageable pageable);

// Использование:
Page<RadioEmission> page = repository.findAllActive(PageRequest.of(0, 50));
```

## Connection Pool (HikariCP)

```properties
# application.properties
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=2
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
```

Для SQLite не ставить слишком большой pool — SQLite плохо справляется с параллельной записью.

## Мониторинг производительности

### Анализ запросов

```sql
-- Посмотреть план запроса
EXPLAIN QUERY PLAN 
SELECT * FROM radio_emissions 
WHERE frequency_mhz BETWEEN 100 AND 200 
AND is_deleted = 0;
```

### Статистика

```java
@Component
public class PerformanceMonitor {
    
    @Scheduled(fixedRate = 3600000)  // Каждый час
    public void logStats() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            ResultSet rs = stmt.executeQuery("PRAGMA page_count");
            long pageCount = rs.getLong(1);
            
            rs = stmt.executeQuery("PRAGMA page_size");
            long pageSize = rs.getLong(1);
            
            long dbSizeMb = (pageCount * pageSize) / (1024 * 1024);
            log.info("SQLite DB size: {} MB", dbSizeMb);
            
        } catch (SQLException e) {
            log.error("Failed to get stats", e);
        }
    }
}
```

## Чек-лист оптимизации
- [ ] WAL mode включён?
- [ ] Все FK проиндексированы?
- [ ] Есть композитные индексы для частых запросов?
- [ ] FTS5 настроен для текстового поиска?
- [ ] Используется pagination для больших выборок?
- [ ] Избегаем SELECT *?
- [ ] HikariCP настроен (не слишком большой pool)?
- [ ] Есть мониторинг размера БД?
- [ ] Проведён EXPLAIN QUERY PLAN для критичных запросов?

## Связанные skills
- [01-database-design](../01-database-design/SKILL.md)
- [03-audit-trail](../03-audit-trail/SKILL.md)