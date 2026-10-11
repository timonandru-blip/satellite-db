package com.satdb.ui.viewmodel;

import java.math.BigDecimal;

/**
 * ViewModel-строка таблицы излучений (слой ui/viewmodel по шаблону 06-SKILL.md).
 *
 * Отделяет FXML/TableView от JPA-сущности: сущность RadioEmission — lazy,
 * вне транзакции обращения к satelliteSystem бросают LazyInitializationException,
 * поэтому контроллер маппит её в immutable record на служебном потоке Task.
 */
public record EmissionRow(
        Long id,
        String code,
        String name,
        String systemName,
        BigDecimal frequencyMhz,
        BigDecimal bandwidthKhz,
        String modulation,
        String updatedAt) {
}
