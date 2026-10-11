package com.satdb.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Спутниковая система (таблица satellite_systems из skills/01-database-design/SKILL.md).
 * orbital_parameters — JSON в TEXT-поле (правило 4: гибкость структуры).
 */
@Entity
@Table(name = "satellite_systems", indexes = {
        @Index(name = "idx_satellite_name", columnList = "system_name"),
        @Index(name = "idx_satellite_code", columnList = "system_code", unique = true),
        @Index(name = "idx_satellite_status", columnList = "status"),
        @Index(name = "idx_satellite_band", columnList = "frequency_band"),
        @Index(name = "idx_satellite_deleted", columnList = "is_deleted")
})
public class SatelliteSystem extends AuditableEntity {

    /** Статусы согласно CHECK (status IN ('ACTIVE','INACTIVE','DECOMMISSIONED','PLANNED')). */
    public enum Status {
        ACTIVE, INACTIVE, DECOMMISSIONED, PLANNED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "system_name", nullable = false, length = 200)
    private String systemName;

    @Column(name = "system_code", nullable = false, length = 50, unique = true)
    private String systemCode;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** JSON: орбитальные параметры (не индексируется напрямую). */
    @Column(name = "orbital_parameters", columnDefinition = "TEXT")
    private String orbitalParameters;

    @Column(name = "frequency_band", length = 50)
    private String frequencyBand;

    @Column(name = "launch_date")
    private LocalDate launchDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.ACTIVE;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "operator", length = 200)
    private String operator;

    /** created_by — обязательное audit-поле (правило 1). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", foreignKey = @jakarta.persistence.ForeignKey(name = "fk_satellite_created_by"))
    private User createdBy;

    /** 1:N спутниковая система -> радиоизлучения (таблица radio_emissions). */
    @OneToMany(mappedBy = "satelliteSystem", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<RadioEmission> emissions = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public String getSystemCode() {
        return systemCode;
    }

    public void setSystemCode(String systemCode) {
        this.systemCode = systemCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOrbitalParameters() {
        return orbitalParameters;
    }

    public void setOrbitalParameters(String orbitalParameters) {
        this.orbitalParameters = orbitalParameters;
    }

    public String getFrequencyBand() {
        return frequencyBand;
    }

    public void setFrequencyBand(String frequencyBand) {
        this.frequencyBand = frequencyBand;
    }

    public LocalDate getLaunchDate() {
        return launchDate;
    }

    public void setLaunchDate(LocalDate launchDate) {
        this.launchDate = launchDate;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public List<RadioEmission> getEmissions() {
        return emissions;
    }

    public void setEmissions(List<RadioEmission> emissions) {
        this.emissions = emissions;
    }

    /** Вспомогательный метод для поддержания двусторонней связи. */
    public void addEmission(RadioEmission emission) {
        emissions.add(emission);
        emission.setSatelliteSystem(this);
    }

    public void removeEmission(RadioEmission emission) {
        emissions.remove(emission);
        emission.setSatelliteSystem(null);
    }
}
