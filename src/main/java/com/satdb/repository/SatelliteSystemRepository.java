package com.satdb.repository;

import com.satdb.entity.SatelliteSystem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий спутниковых систем (SKILL.md 01: таблица satellite_systems).
 * Все запросы по умолчанию исключают помеченные на удаление записи (is_deleted = 0).
 */
@Repository
public interface SatelliteSystemRepository extends JpaRepository<SatelliteSystem, Long> {

    Optional<SatelliteSystem> findBySystemCodeAndIsDeletedFalse(String systemCode);

    List<SatelliteSystem> findByStatusAndIsDeletedFalseOrderByIdAsc(SatelliteSystem.Status status);

    Page<SatelliteSystem> findByFrequencyBandAndIsDeletedFalse(String frequencyBand, Pageable pageable);

    /**
     * Поиск по названию/коду с фильтром по диапазону частот и без удалённых.
     */
    @Query("""
            SELECT s FROM SatelliteSystem s
            WHERE s.isDeleted = false
              AND (LOWER(s.systemName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(s.systemCode) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:frequencyBand IS NULL OR s.frequencyBand = :frequencyBand)
            """)
    Page<SatelliteSystem> searchSystems(@Param("keyword") String keyword,
                                        @Param("frequencyBand") String frequencyBand,
                                        Pageable pageable);

    /**
     * Полнотекстовый поиск по FTS5-индексу satellite_systems_fts (V3__create_fts_indexes.sql).
     * External-content таблица: rowid = id основной таблицы.
     */
    @Query(value = """
            SELECT s.* FROM satellite_systems s
            JOIN satellite_systems_fts fts ON fts.rowid = s.id
            WHERE satellite_systems_fts MATCH :query
              AND s.is_deleted = 0
            ORDER BY rank
            """, nativeQuery = true)
    List<SatelliteSystem> fullTextSearch(@Param("query") String query);
}
