package com.satdb.repository;

import com.satdb.entity.RadioEmission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Репозиторий радиоизлучений (SKILL.md 01: таблица radio_emissions).
 * Мягкое удаление: все читающие запросы исключают is_deleted = 1.
 */
@Repository
public interface RadioEmissionRepository extends JpaRepository<RadioEmission, Long> {

    // ---- Базовые фильтры (без удалённых) ----

    Optional<RadioEmission> findByIdAndIsDeletedFalse(Long id);

    /** Все активные (не удалённые) излучения с пагинацией. */
    @Query("""
            SELECT e FROM RadioEmission e
            WHERE e.isDeleted = false
            ORDER BY e.satelliteSystem.id ASC, e.frequencyMhz ASC
            """,
           countQuery = "SELECT COUNT(e) FROM RadioEmission e WHERE e.isDeleted = false")
    Page<RadioEmission> findAllActive(Pageable pageable);

    List<RadioEmission> findBySatelliteSystemIdAndIsDeletedFalseOrderByFrequencyMhzAsc(Long satelliteSystemId);

    /** Фильтр по спутниковой системе с пагинацией. */
    Page<RadioEmission> findBySatelliteSystemIdAndIsDeletedFalse(Long satelliteSystemId, Pageable pageable);

    long countBySatelliteSystemIdAndIsDeletedFalse(Long satelliteSystemId);

    // ---- Поиск по диапазону частот (BigDecimal, правило 5 SKILL.md) ----

    /**
     * Все активные излучения в диапазоне [minFreq, maxFreq] МГц, по возрастанию частоты.
     */
    @Query("""
            SELECT e FROM RadioEmission e
            WHERE e.isDeleted = false
              AND e.frequencyMhz BETWEEN :minFreq AND :maxFreq
            ORDER BY e.frequencyMhz ASC
            """)
    List<RadioEmission> findByFrequencyRange(@Param("minFreq") BigDecimal minFreq,
                                             @Param("maxFreq") BigDecimal maxFreq);

    /**
     * Поиск по диапазону частот с пагинацией (для таблиц UI и REST-листаний).
     */
    @Query("""
            SELECT e FROM RadioEmission e
            WHERE e.isDeleted = false
              AND e.frequencyMhz BETWEEN :minFreq AND :maxFreq
            """)
    Page<RadioEmission> findByFrequencyRangePaged(@Param("minFreq") BigDecimal minFreq,
                                                  @Param("maxFreq") BigDecimal maxFreq,
                                                  Pageable pageable);

    /**
     * Диапазон частот + фильтр по спутниковой системе (необязательному) + пагинация.
     */
    @Query("""
            SELECT e FROM RadioEmission e
            WHERE e.isDeleted = false
              AND e.frequencyMhz BETWEEN :minFreq AND :maxFreq
              AND (:systemId IS NULL OR e.satelliteSystem.id = :systemId)
            """)
    Page<RadioEmission> findByFrequencyRangeAndSystem(@Param("minFreq") BigDecimal minFreq,
                                                      @Param("maxFreq") BigDecimal maxFreq,
                                                      @Param("systemId") Long systemId,
                                                      Pageable pageable);

    // ---- Комбинированный фильтр (система / класс / модуляция / дата) ----

    @Query("""
            SELECT e FROM RadioEmission e
            WHERE e.isDeleted = false
              AND (:systemId IS NULL OR e.satelliteSystem.id = :systemId)
              AND (:classification IS NULL OR e.classification = :classification)
              AND (:modulationType IS NULL OR LOWER(e.modulationType) LIKE LOWER(CONCAT('%', :modulationType, '%')))
              AND (:fromDate IS NULL OR e.measurementDate >= :fromDate)
              AND (:toDate IS NULL OR e.measurementDate <= :toDate)
            """)
    Page<RadioEmission> filterEmissions(@Param("systemId") Long systemId,
                                        @Param("classification") RadioEmission.Classification classification,
                                        @Param("modulationType") String modulationType,
                                        @Param("fromDate") LocalDateTime fromDate,
                                        @Param("toDate") LocalDateTime toDate,
                                        Pageable pageable);

    // ---- Полнотекстовый поиск через FTS5 (native query) ----

    /**
     * FTS5-поиск по radio_emissions_fts (V3__create_fts_indexes.sql):
     * поля emission_name, emission_code, notes, technical_parameters.
     * External-content таблица: rowid совпадает с id основной таблицы.
     * Удалённые (is_deleted = 1) записи исключаются на уровне SQL.
     *
     * @param ftsQuery синтаксис MATCH FTS5 ("QPSK", "geostationary OR LEO", "\"C band\"", префикс "navig*")
     */
    @Query(value = """
            SELECT e.* FROM radio_emissions e
            JOIN radio_emissions_fts fts ON fts.rowid = e.id
            WHERE radio_emissions_fts MATCH :ftsQuery
              AND e.is_deleted = 0
            ORDER BY rank
            """, nativeQuery = true)
    List<RadioEmission> fullTextSearch(@Param("ftsQuery") String ftsQuery);

    /**
     * FTS5-поиск с ограничением по спутниковой системе и пагинацией.
     */
    @Query(value = """
            SELECT e.* FROM radio_emissions e
            JOIN radio_emissions_fts fts ON fts.rowid = e.id
            WHERE radio_emissions_fts MATCH :ftsQuery
              AND e.is_deleted = 0
              AND (:systemId IS NULL OR e.satellite_system_id = :systemId)
            ORDER BY rank
            """,
            countQuery = """
            SELECT COUNT(*) FROM radio_emissions e
            JOIN radio_emissions_fts fts ON fts.rowid = e.id
            WHERE radio_emissions_fts MATCH :ftsQuery
              AND e.is_deleted = 0
              AND (:systemId IS NULL OR e.satellite_system_id = :systemId)
            """,
            nativeQuery = true)
    Page<RadioEmission> fullTextSearchPaged(@Param("ftsQuery") String ftsQuery,
                                            @Param("systemId") Long systemId,
                                            Pageable pageable);

    /**
     * Подсветка найденного фрагмента (snippet() FTS5) — удобно для UI.
     * Возвращает строки вида "id|snippet".
     */
    @Query(value = """
            SELECT e.id || '|' || snippet(radio_emissions_fts, 0, '[', ']', '...', 10)
            FROM radio_emissions e
            JOIN radio_emissions_fts fts ON fts.rowid = e.id
            WHERE radio_emissions_fts MATCH :ftsQuery
              AND e.is_deleted = 0
            ORDER BY rank
            LIMIT :limit
            """, nativeQuery = true)
    List<String> fullTextSearchWithSnippets(@Param("ftsQuery") String ftsQuery,
                                            @Param("limit") int limit);
}
