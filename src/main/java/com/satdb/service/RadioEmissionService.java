package com.satdb.service;

import com.satdb.audit.Auditable;
import com.satdb.entity.AuditLog;
import com.satdb.entity.RadioEmission;
import com.satdb.entity.SatelliteSystem;
import com.satdb.entity.User;
import com.satdb.exception.NotFoundException;
import com.satdb.exception.ValidationException;
import com.satdb.repository.RadioEmissionRepository;
import com.satdb.repository.SatelliteSystemRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Бизнес-логика радиоизлучений (SKILL.md 06: @Service + @Transactional на уровне сервиса).
 * Права проверяются через PermissionService (SKILL.md 02), изменения пишутся в audit_log
 * через AuditService (SKILL.md 03). Оператор прямые update/delete не получает —
 * его путь — ApprovalService.requestChange().
 */
@Service
@Transactional(readOnly = true)
public class RadioEmissionService {

    public static final String TABLE_NAME = "radio_emissions";

    /** Диапазон допустимых частот, МГц (валидация по SKILL.md 01, правило 5). */
    private static final BigDecimal MIN_FREQUENCY_MHZ = BigDecimal.valueOf(8.3);
    private static final BigDecimal MAX_FREQUENCY_MHZ = BigDecimal.valueOf(400000.0);

    private final RadioEmissionRepository repository;
    private final SatelliteSystemRepository satelliteSystemRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public RadioEmissionService(RadioEmissionRepository repository,
                                SatelliteSystemRepository satelliteSystemRepository,
                                PermissionService permissionService,
                                AuditService auditService) {
        this.repository = repository;
        this.satelliteSystemRepository = satelliteSystemRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    // ---- Чтение (все роли, мягко удалённые не отдаём) ----

    public RadioEmission findById(Long id) {
        return repository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new NotFoundException("Излучение не найдено: " + id));
    }

    /** Снимок для аудита/согласований: включая удалённые (для restore). */
    public RadioEmission findIncludingDeleted(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Излучение не найдено: " + id));
    }

    /** Пагинированный список активных излучений (soft delete исключён). */
    public Page<RadioEmission> findAll(Pageable pageable) {
        return repository.findAllActive(pageable);
    }

    /** Поиск по диапазону частот с пагинацией (исключает удалённые — фильтр в репозитории). */
    @Cacheable(cacheNames = "emissionsByFrequency", key = "#root.methodName")
    public Page<RadioEmission> findByFrequencyRange(BigDecimal minFreqMhz,
                                                    BigDecimal maxFreqMhz,
                                                    Pageable pageable) {
        validateFrequencyRange(minFreqMhz, maxFreqMhz);
        return repository.findByFrequencyRangePaged(minFreqMhz, maxFreqMhz, pageable);
    }

    /** Диапазон + фильтр по спутниковой системе (systemId может быть null). */
    public Page<RadioEmission> findByFrequencyRangeAndSystem(BigDecimal minFreqMhz,
                                                             BigDecimal maxFreqMhz,
                                                             Long systemId,
                                                             Pageable pageable) {
        validateFrequencyRange(minFreqMhz, maxFreqMhz);
        return repository.findByFrequencyRangeAndSystem(minFreqMhz, maxFreqMhz, systemId, pageable);
    }

    /** Полнотекстовый поиск FTS5 (native query в репозитории). */
    public List<RadioEmission> fullTextSearch(String ftsQuery) {
        if (ftsQuery == null || ftsQuery.isBlank()) {
            throw new ValidationException("Поисковый запрос не может быть пустым");
        }
        return repository.fullTextSearch(ftsQuery.trim());
    }

    public Page<RadioEmission> fullTextSearch(String ftsQuery, Long systemId, Pageable pageable) {
        if (ftsQuery == null || ftsQuery.isBlank()) {
            throw new ValidationException("Поисковый запрос не может быть пустым");
        }
        return repository.fullTextSearchPaged(ftsQuery.trim(), systemId, pageable);
    }

    public List<String> fullTextSearchWithSnippets(String ftsQuery) {
        return repository.fullTextSearchWithSnippets(ftsQuery.trim(), 50);
    }

    public List<RadioEmission> findBySatelliteSystem(Long systemId) {
        return repository.findBySatelliteSystemIdAndIsDeletedFalseOrderByFrequencyMhzAsc(systemId);
    }

    public Page<RadioEmission> findBySatelliteSystem(Long systemId, Pageable pageable) {
        return repository.findBySatelliteSystemIdAndIsDeletedFalse(systemId, pageable);
    }

    // ---- Создание (все роли, SKILL.md 02) ----

    @Auditable(tableName = TABLE_NAME, action = "INSERT")
    @Transactional
    @CacheEvict(cacheNames = "emissionsByFrequency", allEntries = true)
    public RadioEmission create(RadioEmission emission, Long userId) {
        User user = permissionService.getActiveUser(userId);
        permissionService.require(user, PermissionService.Permission.CREATE_RECORDS);

        validateForSave(emission);
        requireSystemExists(emission);

        emission.setId(null);
        emission.setCreatedBy(user);
        emission.setIsDeleted(false);

        RadioEmission saved = repository.save(emission);

        auditService.record(TABLE_NAME, saved.getId(), AuditLog.AuditAction.INSERT,
                user, null, snapshot(saved), null, null);
        return saved;
    }

    // ---- Прямое редактирование (только Admin/Supervisor) ----

    @Auditable(tableName = TABLE_NAME, action = "UPDATE")
    @Transactional
    @CacheEvict(cacheNames = "emissionsByFrequency", allEntries = true)
    public RadioEmission update(Long id, Map<String, Object> changes, Long userId, String reason) {
        User user = permissionService.getActiveUser(userId);
        permissionService.checkCanEditDirectly(user.getId());

        RadioEmission emission = findById(id);
        Map<String, Object> before = snapshot(emission);

        applyChanges(emission, changes);
        validateForSave(emission);

        RadioEmission saved = repository.save(emission);
        List<String> changedFields = changedFieldNames(before, snapshot(saved));

        auditService.record(TABLE_NAME, saved.getId(), AuditLog.AuditAction.UPDATE,
                user, before, snapshot(saved), changedFields, reason);
        return saved;
    }

    /** Точечное обновление одного поля (используется ApprovalService при исполнении UPDATE). */
    @Transactional
    public RadioEmission updateField(Long id, String field, Object value, Long userId, String reason) {
        return update(id, Map.of(field, value), userId, reason);
    }

    // ---- Soft delete (только Admin/Supervisor) ----

    @Auditable(tableName = TABLE_NAME, action = "DELETE")
    @Transactional
    @CacheEvict(cacheNames = "emissionsByFrequency", allEntries = true)
    public Long softDelete(Long id, Long userId, String reason) {
        User user = permissionService.getActiveUser(userId);
        permissionService.require(user, PermissionService.Permission.DELETE_RECORDS_DIRECT);

        RadioEmission emission = findById(id);
        Map<String, Object> before = snapshot(emission);

        emission.setIsDeleted(true);
        repository.save(emission);

        AuditLog log = auditService.record(TABLE_NAME, id, AuditLog.AuditAction.DELETE,
                user, before, null, List.of("is_deleted"), reason);
        return log.getId();
    }

    // ---- Восстановление (Admin/Supervisor, SKILL.md 02) ----

    @Auditable(tableName = TABLE_NAME, action = "RESTORE")
    @Transactional
    @CacheEvict(cacheNames = "emissionsByFrequency", allEntries = true)
    public RadioEmission restore(Long id, Long userId, String reason) {
        User user = permissionService.getActiveUser(userId);
        permissionService.require(user, PermissionService.Permission.RESTORE_RECORDS);

        RadioEmission emission = findIncludingDeleted(id);
        emission.setIsDeleted(false);
        RadioEmission saved = repository.save(emission);

        auditService.record(TABLE_NAME, id, AuditLog.AuditAction.RESTORE,
                user, null, snapshot(saved), List.of("is_deleted"), reason);
        return saved;
    }

    // ---- Валидация (SKILL.md 06: validateFrequency) ----

    protected void validateFrequency(BigDecimal frequencyMhz) {
        if (frequencyMhz == null) {
            throw new ValidationException("Частота (frequencyMhz) обязательна");
        }
        if (frequencyMhz.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Частота должна быть положительной, получено: " + frequencyMhz);
        }
        if (frequencyMhz.compareTo(MIN_FREQUENCY_MHZ) < 0
                || frequencyMhz.compareTo(MAX_FREQUENCY_MHZ) > 0) {
            throw new ValidationException(
                    "Частота вне допустимого диапазона [" + MIN_FREQUENCY_MHZ + ", "
                            + MAX_FREQUENCY_MHZ + "] МГц: " + frequencyMhz);
        }
    }

    private void validateFrequencyRange(BigDecimal min, BigDecimal max) {
        if (min == null || max == null) {
            throw new ValidationException("Границы диапазона частот обязательны");
        }
        if (min.compareTo(max) > 0) {
            throw new ValidationException("Нижняя граница диапазона не может быть выше верхней");
        }
    }

    private void validateForSave(RadioEmission e) {
        validateFrequency(e.getFrequencyMhz());
        if (e.getEmissionName() == null || e.getEmissionName().isBlank()) {
            throw new ValidationException("Название излучения обязательно");
        }
        if (e.getEmissionCode() == null || e.getEmissionCode().isBlank()) {
            throw new ValidationException("Код излучения обязателен");
        }
        BigDecimal bw = e.getBandwidthKhz();
        if (bw != null && bw.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Ширина полосы должна быть положительной");
        }
    }

    private void requireSystemExists(RadioEmission emission) {
        SatelliteSystem system = emission.getSatelliteSystem();
        if (system == null || system.getId() == null) {
            throw new ValidationException("Излучение должно быть привязано к спутниковой системе");
        }
        satelliteSystemRepository.findByIdAndIsDeletedFalse(system.getId())
                .orElseThrow(() -> new NotFoundException(
                        "Спутниковая система не найдена: " + system.getId()));
    }

    // ---- Применение изменений из pending-заявки (для ApprovalService) ----

    /** Применяет proposed values к сущности без записи в аудит (аудит пишет исполнитель). */
    @Transactional
    public RadioEmission applyProposedValues(Long id, Map<String, Object> changes) {
        RadioEmission emission = findById(id);
        applyChanges(emission, changes);
        validateForSave(emission);
        return repository.save(emission);
    }

    /** Мягкое удаление без проверок прав — вызывается только из ApprovalService после approve. */
    @Transactional
    public void markDeletedInternal(Long id) {
        RadioEmission emission = findById(id);
        emission.setIsDeleted(true);
        repository.save(emission);
    }

    // ---- helpers ----

    private void applyChanges(RadioEmission e, Map<String, Object> changes) {
        if (changes == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            String field = entry.getKey();
            Object raw = entry.getValue();
            switch (field) {
                case "emissionName" -> e.setEmissionName(str(raw, field));
                case "emissionCode" -> e.setEmissionCode(str(raw, field));
                case "frequencyMhz" -> e.setFrequencyMhz(decimal(raw, field));
                case "bandwidthKhz" -> e.setBandwidthKhz(decimal(raw, field));
                case "powerDbm" -> e.setPowerDbm(decimal(raw, field));
                case "modulationType" -> e.setModulationType(str(raw, field));
                case "polarization" -> e.setPolarization(str(raw, field));
                case "signalType" -> e.setSignalType(str(raw, field));
                case "technicalParameters" -> e.setTechnicalParameters(str(raw, field));
                case "measurementLocation" -> e.setMeasurementLocation(str(raw, field));
                case "priority" -> e.setPriority(raw == null ? null : ((Number) toNumber(raw, field)).intValue());
                case "classification" -> e.setClassification(
                        raw == null ? null : RadioEmission.Classification.valueOf(String.valueOf(raw)));
                case "notes" -> e.setNotes(str(raw, field));
                default -> throw new ValidationException("Неизвестное поле для изменения: " + field);
            }
        }
    }

    private static String str(Object raw, String field) {
        if (raw != null && !(raw instanceof String)) {
            throw new ValidationException("Поле " + field + " должно быть строкой");
        }
        return (String) raw;
    }

    private static BigDecimal decimal(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal b) {
            return b;
        }
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            throw new ValidationException("Поле " + field + " должно быть числом, получено: " + raw);
        }
    }

    private static Number toNumber(Object raw, String field) {
        if (raw instanceof Number n) {
            return n;
        }
        try {
            return Integer.valueOf(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            throw new ValidationException("Поле " + field + " должно быть целым числом");
        }
    }

    /** Снимок всех изменяемых полей (порядок сохраняется). */
    public Map<String, Object> snapshot(RadioEmission e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("satelliteSystemId", e.getSatelliteSystem() == null ? null : e.getSatelliteSystem().getId());
        m.put("emissionName", e.getEmissionName());
        m.put("emissionCode", e.getEmissionCode());
        m.put("frequencyMhz", e.getFrequencyMhz());
        m.put("bandwidthKhz", e.getBandwidthKhz());
        m.put("powerDbm", e.getPowerDbm());
        m.put("modulationType", e.getModulationType());
        m.put("polarization", e.getPolarization());
        m.put("signalType", e.getSignalType());
        m.put("technicalParameters", e.getTechnicalParameters());
        m.put("measurementDate", e.getMeasurementDate() == null ? null : e.getMeasurementDate().toString());
        m.put("measurementLocation", e.getMeasurementLocation());
        m.put("priority", e.getPriority());
        m.put("classification", e.getClassification() == null ? null : e.getClassification().name());
        m.put("notes", e.getNotes());
        m.put("isDeleted", e.isIsDeleted());
        return m;
    }

    private List<String> changedFieldNames(Map<String, Object> before, Map<String, Object> after) {
        return auditService.diffFields(before, after).stream().toList();
    }
}
