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
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Радиоизлучение (таблица radio_emissions из skills/01-database-design/SKILL.md).
 * Числовые параметры — BigDecimal строго по правило 5:
 * frequency_mhz DECIMAL(15,6), bandwidth_khz DECIMAL(10,3), power_dbm DECIMAL(8,2)
 * (антипаттерн: REAL для частот — потеря точности).
 */
@Entity
@Table(
        name = "radio_emissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_emission_code",
                columnNames = {"satellite_system_id", "emission_code"}),
        indexes = {
                @Index(name = "idx_emissions_satellite", columnList = "satellite_system_id"),
                @Index(name = "idx_emissions_frequency", columnList = "frequency_mhz"),
                @Index(name = "idx_emissions_name", columnList = "emission_name"),
                @Index(name = "idx_emissions_date", columnList = "measurement_date"),
                @Index(name = "idx_emissions_classification", columnList = "classification"),
                @Index(name = "idx_emissions_deleted", columnList = "is_deleted"),
                @Index(name = "idx_emissions_sat_freq", columnList = "satellite_system_id,frequency_mhz"),
                @Index(name = "idx_emissions_freq_date", columnList = "frequency_mhz,measurement_date")
        }
)
public class RadioEmission extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK на спутниковую систему (связь 1:N). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "satellite_system_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_emissions_satellite"))
    private SatelliteSystem satelliteSystem;

    @Column(name = "emission_name", nullable = false, length = 200)
    private String emissionName;

    @Column(name = "emission_code", nullable = false, length = 50)
    private String emissionCode;

    /** Частота, МГц — DECIMAL(15,6), точность до 6 знаков. */
    @Column(name = "frequency_mhz", nullable = false, precision = 15, scale = 6)
    private BigDecimal frequencyMhz;

    /** Полоса пропускания, кГц — DECIMAL(10,3). */
    @Column(name = "bandwidth_khz", precision = 10, scale = 3)
    private BigDecimal bandwidthKhz;

    @Column(name = "modulation_type", length = 50)
    private String modulationType;

    /** Мощность, dBm — DECIMAL(8,2). */
    @Column(name = "power_dbm", precision = 8, scale = 2)
    private BigDecimal powerDbm;

    @Column(name = "polarization", length = 20)
    private String polarization;

    @Column(name = "signal_type", length = 50)
    private String signalType;

    /** JSON: произвольные технические параметры (правило 4). */
    @Column(name = "technical_parameters", columnDefinition = "TEXT")
    private String technicalParameters;

    @Column(name = "measurement_date")
    private LocalDateTime measurementDate;

    @Column(name = "measurement_location", length = 200)
    private String measurementLocation;

    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", length = 50)
    private Classification classification;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** created_by — обязательное audit-поле (правило 1). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_emissions_created_by"))
    private User createdBy;

    public enum Classification {
        OPEN, RESTRICTED, CONFIDENTIAL
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public SatelliteSystem getSatelliteSystem() {
        return satelliteSystem;
    }

    public void setSatelliteSystem(SatelliteSystem satelliteSystem) {
        this.satelliteSystem = satelliteSystem;
    }

    public String getEmissionName() {
        return emissionName;
    }

    public void setEmissionName(String emissionName) {
        this.emissionName = emissionName;
    }

    public String getEmissionCode() {
        return emissionCode;
    }

    public void setEmissionCode(String emissionCode) {
        this.emissionCode = emissionCode;
    }

    public BigDecimal getFrequencyMhz() {
        return frequencyMhz;
    }

    public void setFrequencyMhz(BigDecimal frequencyMhz) {
        this.frequencyMhz = frequencyMhz;
    }

    public BigDecimal getBandwidthKhz() {
        return bandwidthKhz;
    }

    public void setBandwidthKhz(BigDecimal bandwidthKhz) {
        this.bandwidthKhz = bandwidthKhz;
    }

    public String getModulationType() {
        return modulationType;
    }

    public void setModulationType(String modulationType) {
        this.modulationType = modulationType;
    }

    public BigDecimal getPowerDbm() {
        return powerDbm;
    }

    public void setPowerDbm(BigDecimal powerDbm) {
        this.powerDbm = powerDbm;
    }

    public String getPolarization() {
        return polarization;
    }

    public void setPolarization(String polarization) {
        this.polarization = polarization;
    }

    public String getSignalType() {
        return signalType;
    }

    public void setSignalType(String signalType) {
        this.signalType = signalType;
    }

    public String getTechnicalParameters() {
        return technicalParameters;
    }

    public void setTechnicalParameters(String technicalParameters) {
        this.technicalParameters = technicalParameters;
    }

    public LocalDateTime getMeasurementDate() {
        return measurementDate;
    }

    public void setMeasurementDate(LocalDateTime measurementDate) {
        this.measurementDate = measurementDate;
    }

    public String getMeasurementLocation() {
        return measurementLocation;
    }

    public void setMeasurementLocation(String measurementLocation) {
        this.measurementLocation = measurementLocation;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Classification getClassification() {
        return classification;
    }

    public void setClassification(Classification classification) {
        this.classification = classification;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }
}
