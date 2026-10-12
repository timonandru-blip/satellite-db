package com.satdb.ui.viewmodel;

import com.satdb.dto.RadioEmissionDto;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ViewModel радиоизлучения для двухстороннего связывания (data binding) в JavaFX.
 *
 * <p>Оборачивает неизменяемый {@link RadioEmissionDto} в наблюдаемые JavaFX-свойства,
 * чтобы TableView/TextField/Label реагировали на изменения без ручного обновления UI.
 * Типы свойств подобраны под виджеты: текст — {@link StringProperty}, числа —
 * {@link ObjectProperty}&lt;BigDecimal&gt; (сохраняет точность частот, ТЗ требует
 * DECIMAL(15,6)), приоритет — {@link IntegerProperty}, статус согласования —
 * {@link BooleanProperty}.</p>
 *
 * <p>Жизненный цикл: контроллер создаёт ViewModel из DTO
 * ({@code new RadioEmissionViewModel(dto)}), при сохранении формы собирает обратно
 * обновлённый DTO через {@link #toDto()} (record immutable — возвращается новый
 * экземпляр). Поля аудита (createdBy/createdAt/updatedAt, id, привязка к системе)
 * read-only: они не участвуют в редактировании и переносятся как есть.</p>
 *
 * <p>Потоковая безопасность: свойства изменяются только с JavaFX Application Thread
 * (классическое требование JavaFX); длительные операции чтения выполняются в
 * Task-потоках сервисного слоя, а результат публикуется через Platform.runLater.</p>
 */
public final class RadioEmissionViewModel {

    /** Формат дат для колонок таблицы и подписей (dd.MM.yyyy HH:mm). */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    // ==================== идентификатор и контекст (read-only) ====================

    /** PK излучения; null — новая (несохранённая) запись. Не редактируется пользователем. */
    private final ObjectProperty<Long> id = new SimpleObjectProperty<>(this, "id");

    /** FK спутниковой системы — переносится из DTO, меняется только программно. */
    private final ObjectProperty<Long> satelliteSystemId =
            new SimpleObjectProperty<>(this, "satelliteSystemId");

    /** Отображаемое имя системы (для колонок/хлебных крошек). */
    private final StringProperty satelliteSystemName =
            new SimpleStringProperty(this, "satelliteSystemName", "");

    /** Короткий код системы (например, "GEO-KU-01"). */
    private final StringProperty satelliteSystemCode =
            new SimpleStringProperty(this, "satelliteSystemCode", "");

    // ==================== редактируемые поля формы ====================

    private final StringProperty emissionName = new SimpleStringProperty(this, "emissionName", "");
    private final StringProperty emissionCode = new SimpleStringProperty(this, "emissionCode", "");

    /**
     * Центральная частота, МГц — DoubleProperty по требованию контракта ViewModel.
     * Значение конвертируется из BigDecimal DTO (doubleValue), при обратном
     * маппинге восстанавливается через BigDecimal.valueOf (точный round-trip
     * для значений с плавающей точкой Java). Для расчётов точности внутри UI
     * доступен оригинал: см. {@link #frequencyMhzDecimal()}.
     */
    private final DoubleProperty frequencyMhz =
            new SimpleDoubleProperty(this, "frequencyMhz", 0.0);

    /** Ширина канала, кГц (DECIMAL(10,3)). */
    private final ObjectProperty<BigDecimal> bandwidthKhz =
            new SimpleObjectProperty<>(this, "bandwidthKhz", BigDecimal.ZERO);

    /** Мощность сигнала, дБм (DECIMAL(8,2)). */
    private final ObjectProperty<BigDecimal> powerDbm =
            new SimpleObjectProperty<>(this, "powerDbm", BigDecimal.ZERO);

    private final StringProperty modulationType = new SimpleStringProperty(this, "modulationType", "");
    private final StringProperty polarization = new SimpleStringProperty(this, "polarization", "");
    private final StringProperty signalType = new SimpleStringProperty(this, "signalType", "");

    /** Приоритет записи (целое 1..5 по матрице классификации ТЗ). */
    private final IntegerProperty priority = new SimpleIntegerProperty(this, "priority", 3);

    /** Классификация: PUBLIC / INTERNAL / RESTRICTED (enum-as-string из DTO). */
    private final StringProperty classification = new SimpleStringProperty(this, "classification", "");

    /**
     * Дата измерения (календарная дата) — ObjectProperty&lt;LocalDate&gt; по контракту
     * ViewModel: привязывается к DatePicker. DTO хранит полный timestamp
     * LocalDateTime, поэтому при импорте берётся date-часть (см. конструктор),
     * а toDto() восстанавливает время 00:00, если исходный timestamp отсутствовал.
     */
    private final ObjectProperty<LocalDate> measurementDate =
            new SimpleObjectProperty<>(this, "measurementDate");

    /** Точный оригинал частоты из DTO (BigDecimal) — только для чтения/расчётов. */
    private final ObjectProperty<BigDecimal> frequencyMhzDecimal =
            new SimpleObjectProperty<>(this, "frequencyMhzDecimal", BigDecimal.ZERO);

    private final StringProperty measurementLocation =
            new SimpleStringProperty(this, "measurementLocation", "");
    private final StringProperty notes = new SimpleStringProperty(this, "notes", "");

    // ==================== статус согласования ====================

    /**
     * true — для записи существует PENDING-заявка в workflow согласований.
     * Контроллер выставляет флаг после загрузки очереди (см. ApprovalService.hasPending):
     * таблица подсвечивает такие строки (pseudo-class "pending"), кнопка «Изменить»
     * для оператора превращается в «Запросить изменение».
     */
    private final BooleanProperty isPending = new SimpleBooleanProperty(this, "isPending", false);

    // ==================== read-only аудит ====================

    private final StringProperty createdByUsername =
            new SimpleStringProperty(this, "createdByUsername", "");
    private final ObjectProperty<LocalDateTime> createdAt =
            new SimpleObjectProperty<>(this, "createdAt");
    private final ObjectProperty<LocalDateTime> updatedAt =
            new SimpleObjectProperty<>(this, "updatedAt");

    // ==================== конструкторы ====================

    /**
     * Создаёт ViewModel из DTO (чтение). Null-поля DTO нормализуются:
     * строки → "", числа → ZERO, чтобы биндинги не показывали "null".
     */
    public RadioEmissionViewModel(RadioEmissionDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("dto must not be null");
        }
        id.set(dto.id());
        satelliteSystemId.set(dto.satelliteSystemId());
        satelliteSystemName.set(nvl(dto.satelliteSystemName()));
        satelliteSystemCode.set(nvl(dto.satelliteSystemCode()));
        emissionName.set(nvl(dto.emissionName()));
        emissionCode.set(nvl(dto.emissionCode()));
        frequencyMhzDecimal.set(nvlZero(dto.frequencyMhz()));
        frequencyMhz.set(dto.frequencyMhz() == null ? 0.0 : dto.frequencyMhz().doubleValue());
        bandwidthKhz.set(nvlZero(dto.bandwidthKhz()));
        powerDbm.set(nvlZero(dto.powerDbm()));
        modulationType.set(nvl(dto.modulationType()));
        polarization.set(nvl(dto.polarization()));
        signalType.set(nvl(dto.signalType()));
        priority.set(dto.priority() == null ? 3 : dto.priority());
        classification.set(nvl(dto.classification()));
        // DTO хранит LocalDateTime → ViewModelcontract'у отдаём календарную дату
        measurementDate.set(dto.measurementDate() == null ? null : dto.measurementDate().toLocalDate());
        measurementLocation.set(nvl(dto.measurementLocation()));
        notes.set(nvl(dto.notes()));
        createdByUsername.set(nvl(dto.createdByUsername()));
        createdAt.set(dto.createdAt());
        updatedAt.set(dto.updatedAt());
    }

    /** Пустая форма «создать излучение»: id == null, безопасные значения по умолчанию. */
    public RadioEmissionViewModel() {
        this(emptyDto());
    }

    // ==================== обратный маппинг ====================

    /**
     * Собирает текущее состояние наблюдаемых свойств обратно в immutable DTO.
     * Используется контроллером перед вызовом сервиса (PUT / requestChange).
     * Аудиторные поля и привязка к системе переносятся без изменений — их
     * право менять принадлежит серверу (Spring Security + @PreAuthorize).
     *
     * <p>Частота: если DoubleProperty редактировался пользователем, его значение
     * становится источником истины (BigDecimal.valueOf(double) даёт каноничное
     * десятичное представление); иначе сохраняется точный BigDecimal-оригинал.</p>
     */
    public RadioEmissionDto toDto() {
        return new RadioEmissionDto(
                id.get(),
                satelliteSystemId.get(),
                blankToNull(satelliteSystemName.get()),
                blankToNull(satelliteSystemCode.get()),
                trim(emissionName.get()),
                trim(emissionCode.get()),
                currentFrequency(),
                bandwidthKhz.get(),
                blankToNull(modulationType.get()),
                powerDbm.get(),
                blankToNull(polarization.get()),
                blankToNull(signalType.get()),
                priority.get(),
                blankToNull(classification.get()),
                measurementDateTime(),
                blankToNull(measurementLocation.get()),
                blankToNull(notes.get()),
                blankToNull(createdByUsername.get()),
                createdAt.get(),
                updatedAt.get());
    }

    /**
     * Входной вариант {@link RadioEmissionDto.Request} для POST/PUT-вызовов:
     * только редактируемые поля формы, без аудита.
     */
    public RadioEmissionDto.Request toRequest() {
        return new RadioEmissionDto.Request(
                satelliteSystemId.get(),
                trim(emissionName.get()),
                trim(emissionCode.get()),
                currentFrequency(),
                bandwidthKhz.get(),
                blankToNull(modulationType.get()),
                powerDbm.get(),
                blankToNull(polarization.get()),
                blankToNull(signalType.get()),
                priority.get(),
                blankToNull(classification.get()),
                measurementDateTime(),
                blankToNull(measurementLocation.get()),
                blankToNull(notes.get()),
                null /* technicalParameters заполняет сервис */);
    }

    // ==================== конвертеры контракта ViewModel <-> DTO ====================

    /**
     * Приоритет источника частоты: отредактированная пользователем DoubleProperty
     * важнее импортированного BigDecimal-оригинала; при отсутствии редактирования
     * возвращаем оригинал без потерь точности DECIMAL(15,6).
     */
    private BigDecimal currentFrequency() {
        double d = frequencyMhz.get();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return frequencyMhzDecimal.get();
        }
        BigDecimal fromDouble = BigDecimal.valueOf(d).setScale(6, RoundingMode.HALF_UP);
        return fromDouble.compareTo(frequencyMhzDecimal.get()) == 0
                ? frequencyMhzDecimal.get()   // не менялась — сохраняем оригинальный scale
                : fromDouble.stripTrailingZeros().scale() < 0
                        ? fromDouble          // держим читаемый scale
                        : fromDouble.stripTrailingZeros();
    }

    /** LocalDate контракта → LocalDateTime DTO (полнота timestamp: время 00:00). */
    private LocalDateTime measurementDateTime() {
        LocalDate date = measurementDate.get();
        return date == null ? null : date.atStartOfDay();
    }

    // ==================== производственные (derived) свойства ====================

    /**
     * Готовая строка «частота+полоса» для колонки таблицы: "1545.200 МГц ±3.750 кГц".
     * ReadOnly + lazy-пересчёт: зависит от frequencyMhz/bandwidthKhz через bind(),
     * поэтому остаётся актуальной при любом изменении исходных свойств.
     */
    public ReadOnlyStringProperty channelLabelProperty() {
        ReadOnlyStringWrapper label = new ReadOnlyStringWrapper();
        label.bind(frequencyMhz.asString("%.3f МГц")
                .concat(bandwidthKhz.map(b -> b == null || b.signum() == 0
                        ? "" : " ±" + b.stripTrailingZeros().toPlainString() + " кГц")));
        return label.getReadOnlyProperty();
    }

    /** Отформатированная дата изменения ("dd.MM.yyyy HH:mm") — прямо в TableView. */
    public ReadOnlyStringProperty updatedAtTextProperty() {
        ReadOnlyStringWrapper text = new ReadOnlyStringWrapper();
        text.bind(updatedAt.map(d -> d == null ? "" : DATE_FORMAT.format(d)));
        return text.getReadOnlyProperty();
    }

    // ==================== accessors (JavaFX property convention) ====================

    public ObjectProperty<Long> idProperty() { return id; }
    public Long getId() { return id.get(); }

    public ObjectProperty<Long> satelliteSystemIdProperty() { return satelliteSystemId; }
    public void setSatelliteSystemId(Long value) { satelliteSystemId.set(value); }

    public StringProperty satelliteSystemNameProperty() { return satelliteSystemName; }
    public String getSatelliteSystemName() { return satelliteSystemName.get(); }

    public StringProperty satelliteSystemCodeProperty() { return satelliteSystemCode; }
    public String getSatelliteSystemCode() { return satelliteSystemCode.get(); }

    public StringProperty emissionNameProperty() { return emissionName; }
    public void setEmissionName(String value) { emissionName.set(value); }
    public String getEmissionName() { return emissionName.get(); }

    public StringProperty emissionCodeProperty() { return emissionCode; }
    public void setEmissionCode(String value) { emissionCode.set(value); }
    public String getEmissionCode() { return emissionCode.get(); }

    /** Частота как DoubleProperty (контракт ViewModel) — для TextField/Spinner форм. */
    public DoubleProperty frequencyMhzProperty() { return frequencyMhz; }
    public void setFrequencyMhz(double value) { frequencyMhz.set(value); }
    public double getFrequencyMhz() { return frequencyMhz.get(); }

    /** Точный BigDecimal-оригинал частоты из DTO — только чтение (расчёты, тултипы). */
    public ObjectProperty<BigDecimal> frequencyMhzDecimalProperty() { return frequencyMhzDecimal; }
    public BigDecimal getFrequencyMhzDecimal() { return frequencyMhzDecimal.get(); }

    public ObjectProperty<BigDecimal> bandwidthKhzProperty() { return bandwidthKhz; }
    public void setBandwidthKhz(BigDecimal value) { bandwidthKhz.set(nvlZero(value)); }
    public BigDecimal getBandwidthKhz() { return bandwidthKhz.get(); }

    public ObjectProperty<BigDecimal> powerDbmProperty() { return powerDbm; }
    public void setPowerDbm(BigDecimal value) { powerDbm.set(nvlZero(value)); }
    public BigDecimal getPowerDbm() { return powerDbm.get(); }

    public StringProperty modulationTypeProperty() { return modulationType; }
    public void setModulationType(String value) { modulationType.set(value); }
    public String getModulationType() { return modulationType.get(); }

    public StringProperty polarizationProperty() { return polarization; }
    public void setPolarization(String value) { polarization.set(value); }

    public StringProperty signalTypeProperty() { return signalType; }
    public void setSignalType(String value) { signalType.set(value); }

    public IntegerProperty priorityProperty() { return priority; }
    public void setPriority(int value) { priority.set(value); }
    public int getPriority() { return priority.get(); }

    public StringProperty classificationProperty() { return classification; }
    public void setClassification(String value) { classification.set(value); }

    /** Дата измерения как ObjectProperty&lt;LocalDate&gt; — привязка к DatePicker. */
    public ObjectProperty<LocalDate> measurementDateProperty() { return measurementDate; }
    public void setMeasurementDate(LocalDate value) { measurementDate.set(value); }
    public LocalDate getMeasurementDate() { return measurementDate.get(); }

    public StringProperty measurementLocationProperty() { return measurementLocation; }
    public void setMeasurementLocation(String value) { measurementLocation.set(value); }

    public StringProperty notesProperty() { return notes; }
    public void setNotes(String value) { notes.set(value); }

    public BooleanProperty isPendingProperty() { return isPending; }
    public void setPending(boolean value) { isPending.set(value); }
    public boolean isPending() { return isPending.get(); }

    public StringProperty createdByUsernameProperty() { return createdByUsername; }

    public ObjectProperty<LocalDateTime> createdAtProperty() { return createdAt; }

    public ObjectProperty<LocalDateTime> updatedAtProperty() { return updatedAt; }

    // ==================== helpers ====================

    private static String nvl(String s) { return s == null ? "" : s; }
    private static BigDecimal nvlZero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String trim(String s) { return s == null ? null : s.trim(); }
    /** Пустая строка формы трактуется как «поле не задано» (null в DTO/сервисе). */
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    /** DTO-заготовка для пустой формы: все обязательные числовые поля — нули. */
    private static RadioEmissionDto emptyDto() {
        return new RadioEmissionDto(null, null, "", "", "", "",
                BigDecimal.ZERO, BigDecimal.ZERO, "", BigDecimal.ZERO, "", "",
                3, "", null, "", "", null, null, null);
    }

    @Override
    public String toString() {
        return "RadioEmissionViewModel[" + getEmissionCode() + ", " + getFrequencyMhz() + " МГц"
                + (isPending() ? ", PENDING" : "") + "]";
    }
}
