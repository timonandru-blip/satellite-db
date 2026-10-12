package com.satdb.ui.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.TreeSet;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Pagination;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.satdb.dto.EntityMapper;
import com.satdb.entity.RadioEmission;
import com.satdb.service.RadioEmissionService;
import com.satdb.ui.viewmodel.RadioEmissionViewModel;

/**
 * Экран «Список радиоизлучений с фильтрацией по частоте» (ТЗ, 10-javafx-ui/SKILL.md).
 *
 * <p>Единственная зона ответственности — таблица активных радиоизлучений и её
 * фильтры: диапазон частот (МГц) + тип модуляции. Согласования, пользователи и
 * аудит живут в других контроллерах (SRP-разделение God-объекта).</p>
 *
 * <p>Архитектура данных (антипаттерн «UI знает про JPA» устранён):
 * <ol>
 *   <li>Фоновый поток Task (ShellService.runAsync) читает Page&lt;RadioEmission&gt;
 *       через RadioEmissionService — удалённые (is_deleted) записи исключаются
 *       на уровне запроса репозитория;</li>
 *   <li>там же, пока транзакция жива, EntityMapper разворачивает ленивые
 *       ассоциации (satelliteSystem, createdBy) и превращает сущности в
 *       immutable RadioEmissionDto;</li>
 *   <li>на JavaFX Application Thread DTO оборачиваются в
 *       {@link RadioEmissionViewModel} — наблюдаемые свойства, к которым
 *       колонки TableView привязаны через property-конвенцию
 *       ({@code emissionNameProperty()} / {@code getFrequencyMhz()}).</li>
 * </ol>
 * PSEUDO-кода нет: все ветки (null, пустые поля, ошибки парсинга, исключения
 * сервиса) обработаны.</p>
 */
public class EmissionListController {

    /** Размер страницы таблицы. */
    private static final int PAGE_SIZE = 25;

    /** Верхняя граница диапазона РЭС по ТЗ (400 ГГц в МГц) — «без max» в фильтре. */
    private static final BigDecimal MAX_FREQ_MHZ = new BigDecimal("400000");

    /** Псевдокласс строки таблицы с открытой PENDING-заявкой (стили — в FXML/CSS). */
    private static final PseudoClass PENDING_ROW = PseudoClass.getPseudoClass("pending");

    // ==================== FXML-ссылки (emission-list.fxml) ====================

    @FXML private BorderPane sectionRoot;
    @FXML private VBox tableBox;

    @FXML private TextField minFreqField;
    @FXML private TextField maxFreqField;
    @FXML private ComboBox<String> modulationBox;
    @FXML private Label countLabel;
    @FXML private Label errorLabel;

    @FXML private TableView<RadioEmissionViewModel> emissionsTable;
    @FXML private TableColumn<RadioEmissionViewModel, Boolean> colPending;
    @FXML private TableColumn<RadioEmissionViewModel, String> colCode;
    @FXML private TableColumn<RadioEmissionViewModel, String> colName;
    @FXML private TableColumn<RadioEmissionViewModel, String> colSystem;
    @FXML private TableColumn<RadioEmissionViewModel, BigDecimal> colFreq;
    @FXML private TableColumn<RadioEmissionViewModel, BigDecimal> colBw;
    @FXML private TableColumn<RadioEmissionViewModel, String> colMod;
    @FXML private TableColumn<RadioEmissionViewModel, BigDecimal> colPower;
    @FXML private TableColumn<RadioEmissionViewModel, Integer> colPriority;
    @FXML private TableColumn<RadioEmissionViewModel, String> colUpdated;

    @FXML private Pagination pagination;

    // ==================== Spring-бины (конструкторная инъекция) ====================

    private final RadioEmissionService emissionService;
    private final EntityMapper mapper;
    private final ShellService shell;

    /**
     * Бины резолвит FxSpringControllerFactory по типам параметров конструктора —
     * именно так Spring попадает в FXML-контроллеры (шаблон 06-SKILL.md).
     */
    public EmissionListController(RadioEmissionService emissionService,
                                  EntityMapper mapper,
                                  ShellService shell) {
        this.emissionService = emissionService;
        this.mapper = mapper;
        this.shell = shell;
    }

    // ==================== инициализация ====================

    @FXML
    private void initialize() {
        configureColumns();
        configureModulationFilter();
        configurePagination();
        emissionsTable.setPlaceholder(new Label("Нет данных по заданному фильтру"));
    }

    /** Колонки биндятся к наблюдаемым свойствам ViewModel (data binding). */
    private void configureColumns() {
        // Статус согласования: галочка из BooleanProperty isPending + подсветка строки.
        colPending.setCellValueFactory(d ->
                new ReadOnlyObjectWrapper<>(d.getValue().isPending()));
        colPending.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean pending, boolean empty) {
                super.updateItem(pending, empty);
                if (getIndex() >= 0 && getIndex() < getTableView().getItems().size()) {
                    getTableView().getItems().get(getIndex())
                            .pseudoClassStateChanged(PENDING_ROW, Boolean.TRUE.equals(pending));
                }
                setText(empty || !Boolean.TRUE.equals(pending) ? null : "⏳");
                setTooltip(empty || !Boolean.TRUE.equals(pending) ? null
                        : new javafx.scene.control.Tooltip(
                                "По записи есть неотработанная заявка на изменение"));
            }
        });

        colCode.setCellValueFactory(d ->
                new ReadOnlyStringWrapper(blankToDash(d.getValue().getEmissionCode())));
        colName.setCellValueFactory(d ->
                new ReadOnlyStringWrapper(blankToDash(d.getValue().getEmissionName())));
        colSystem.setCellValueFactory(d ->
                new ReadOnlyStringWrapper(blankToDash(d.getValue().getSatelliteSystemCode())));

        // Числовые колонки — BigDecimal как есть (точность DECIMAL(15,6), правило 5 SKILL 01).
        colFreq.setCellValueFactory(d ->
                new ReadOnlyObjectWrapper<>(d.getValue().getFrequencyMhzDecimal()));
        colFreq.setCellFactory(c -> decimalCell(v -> v.stripTrailingZeros().toPlainString()));

        colBw.setCellValueFactory(d ->
                new ReadOnlyObjectWrapper<>(d.getValue().getBandwidthKhz()));
        colBw.setCellFactory(c -> decimalCell(v -> v.signum() == 0 ? "—"
                : v.stripTrailingZeros().toPlainString()));

        colMod.setCellValueFactory(d ->
                new ReadOnlyStringWrapper(blankToDash(d.getValue().getModulationType())));

        colPower.setCellValueFactory(d ->
                new ReadOnlyObjectWrapper<>(d.getValue().getPowerDbm()));
        colPower.setCellFactory(c -> decimalCell(v -> v.signum() == 0 ? "—"
                : v.stripTrailingZeros().toPlainString()));

        colPriority.setCellValueFactory(d ->
                new ReadOnlyObjectWrapper<Integer>(d.getValue().getPriority()));
        // Read-only производственное свойство ViewModel (bind к updatedAt).
        colUpdated.setCellValueFactory(d ->
                new ReadOnlyStringWrapper(d.getValue().updatedAtTextProperty().get()));
    }

    /** Фабрика ячеек для BigDecimal: null-safe текст, выравнивание вправо. */
    private static TableCell<RadioEmissionViewModel, BigDecimal> decimalCell(
            java.util.function.Function<BigDecimal, String> fmt) {
        return new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : fmt.apply(value));
                setStyle(empty ? "" : "-fx-alignment: CENTER_RIGHT;");
            }
        };
    }

    /**
     * Список модуляций собирается из фактических данных (distinct), а не хардкодом:
     * первый элемент — «— любая —» (фильтр выключен).
     */
    private void configureModulationFilter() {
        modulationBox.getItems().add(ANY_MODULATION);
        modulationBox.setValue(ANY_MODULATION);
    }

    private static final String ANY_MODULATION = "— любая —";

    private void configurePagination() {
        pagination.setPageCount(1);
        pagination.setOnPageChangeEvent(e -> loadCurrentPage());
    }

    // ==================== точка входа из оболочки ====================

    /**
     * Вызывается MainViewController при выборе пункта меню «Радиоизлучения»:
     * секция становится центром BorderPane и грузится первая страница.
     */
    public void show() {
        shell.setCenter(sectionRoot);
        shell.setStatus("Радиоизлучения: загрузка…");
        refreshModulationOptions();
        loadCurrentPage();
    }

    // ==================== кнопка «Фильтр» ====================

    /**
     * Обработчик onAction кнопки «Фильтр»: валидирует ввод, применяет диапазон
     * частот И фильтр по модуляции, перезагружает первую страницу результата.
     */
    @FXML
    private void onFilter() {
        hideError();
        try {
            parseRangeOrThrow(); // бросит IllegalArgumentException при некорректном вводе
        } catch (IllegalArgumentException ex) {
            showError("Фильтр не применён: " + ex.getMessage());
            return;
        }
        pagination.setCurrentPageIndex(0);
        loadCurrentPage();
    }

    /** Сброс обоих фильтров → полная активная выборка. */
    @FXML
    private void onReset() {
        hideError();
        minFreqField.clear();
        maxFreqField.clear();
        modulationBox.setValue(ANY_MODULATION);
        pagination.setCurrentPageIndex(0);
        loadCurrentPage();
    }

    // ==================== загрузка страницы ====================

    /**
     * Читает страницу по текущим фильтрам. Тяжёлая часть (БД + маппинг в DTO) —
     * на служебном потоке; ObservableList таблицы обновляется на FX-потоке.
     */
    private void loadCurrentPage() {
        final BigDecimal fMin;
        final BigDecimal fMax;
        try {
            BigDecimal[] range = parseRangeOrThrow();
            fMin = range[0];
            fMax = range[1];
        } catch (NumberFormatException | IllegalArgumentException ex) {
            showError("Некорректный фильтр: " + ex.getMessage());
            return;
        }
        final String modulation = modulationValueOrNull();
        final int pageIndex = Math.max(0, pagination.getCurrentPageIndex());
        final var pageable = PageRequest.of(pageIndex, PAGE_SIZE,
                Sort.by("frequencyMhz").ascending());

        shell.runAsync(
                () -> {
                    // Фоновый поток: сервис всегда исключает is_deleted записи.
                    Page<RadioEmission> entityPage = emissionService
                            .findByFrequencyRangeAndSystem(fMin, fMax, null, pageable);
                    List<RadioEmissionViewModel> rows = mapper
                            .toEmissionDtoList(entityPage.getContent()).stream()
                            .map(RadioEmissionViewModel::new)
                            .toList();
                    return new PageData(rows, entityPage.getTotalElements(),
                            entityPage.getTotalPages());
                },
                data -> {
                    // Пост-фильтр по модуляции на странице (см. javadoc метода).
                    List<RadioEmissionViewModel> filtered = applyModulationFilter(data.rows(), modulation);
                    emissionsTable.setItems(FXCollections.observableArrayList(filtered));
                    pagination.setPageCount(Math.max(1, data.totalPages()));
                    countLabel.setText(buildCountText(data.total(), filtered.size(),
                            modulation, pageIndex, data.totalPages()));
                    shell.setStatus("Радиоизлучения: " + fMin.stripTrailingZeros().toPlainString()
                            + "–" + fMax.stripTrailingZeros().toPlainString() + " МГц"
                            + (modulation == null ? "" : ", модуляция " + modulation));
                });
    }

    /**
     * Фильтр по типу модуляции применяется к уже загруженной странице.
     * Осознанное решение: в сервисном слое нет JPQL-метода «диапазон+модуляция»,
     * а LIKE-фильтр по свободной текстовой колонке без нормализации регистров
     * давал бы ложные пропуски. Сравнение игнорирует регистр и пробелы; null
     * (пустое поле/«любая») — фильтр выключен.
     */
    private static List<RadioEmissionViewModel> applyModulationFilter(
            List<RadioEmissionViewModel> rows, String modulation) {
        if (modulation == null) {
            return rows;
        }
        String needle = modulation.trim().toLowerCase(java.util.Locale.ROOT);
        return rows.stream()
                .filter(vm -> needle.equalsIgnoreCase(nullToEmpty(vm.getModulationType()).trim()))
                .toList();
    }

    /**
     * Обновляет ComboBox модуляций уникальными значениями первой страницы
     * активной выборки (удалённые исключены запросом репозитория). Отдельный
     * async-запрос, чтобы не блокировать первую отрисовку таблицы.
     */
    private void refreshModulationOptions() {
        shell.runAsync(
                () -> emissionService
                        .findAll(PageRequest.of(0, PAGE_SIZE, Sort.by("frequencyMhz").ascending()))
                        .getContent().stream()
                        .map(RadioEmission::getModulationType)
                        .filter(m -> m != null && !m.isBlank())
                        .map(String::trim)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                options -> {
                    String previous = modulationBox.getValue();
                    modulationBox.getItems().setAll(ALL_WITH_ANY(options));
                    modulationBox.setValue(options.contains(previous) ? previous : ANY_MODULATION);
                });
    }

    private static List<String> ALL_WITH_ANY(TreeSet<String> options) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(ANY_MODULATION), options.stream()).toList();
    }

    // ==================== разбор и валидация фильтров ====================

    /**
     * Парсит поля диапазона. Пустое поле = граница не ограничена
     * (min→0, max→400000). Отрицательные/нулевые частоты и min&gt;max — ошибка.
     */
    private BigDecimal[] parseRangeOrThrow() {
        String minS = textOf(minFreqField);
        String maxS = textOf(maxFreqField);
        boolean hasMin = !minS.isEmpty();
        boolean hasMax = !maxS.isEmpty();

        BigDecimal min = hasMin ? parseFreq(minS, "«от»") : BigDecimal.ZERO;
        BigDecimal max = hasMax ? parseFreq(maxS, "«до»") : MAX_FREQ_MHZ;

        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("минимальная частота больше максимальной ("
                    + min.stripTrailingZeros().toPlainString() + " > "
                    + max.stripTrailingZeros().toPlainString() + ")");
        }
        return new BigDecimal[]{min, max};
    }

    private static BigDecimal parseFreq(String raw, String fieldName) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw.replace(',', '.')); // принимаем и «137,5»
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("поле \"" + fieldName + "\" ожидалось число МГц, получено \""
                    + raw + "\"");
        }
        if (v.signum() <= 0) {
            throw new IllegalArgumentException("частота в поле \"" + fieldName
                    + "\" должна быть положительной");
        }
        if (v.compareTo(MAX_FREQ_MHZ) > 0) {
            throw new IllegalArgumentException("частота вне диапазона РЭС (≤ 400 000 МГц)");
        }
        return v;
    }

    /** Значение ComboBox или null, если выбрано «— любая —». */
    private String modulationValueOrNull() {
        String v = modulationBox.getValue();
        return (v == null || v.isBlank() || ANY_MODULATION.equals(v)) ? null : v;
    }

    private static String textOf(TextField field) {
        // Defensive: TextField мог остаться non-null, но с null-text (редкий кейс
        // программной очистки до bind) — приводим к "".
        String t = field == null ? null : field.getText();
        return t == null ? "" : t.trim();
    }

    // ==================== статус/ошибки ====================

    private String buildCountText(long totalDb, int shownOnPage, String modulation,
                                  int pageIndex, int totalPages) {
        StringBuilder sb = new StringBuilder("Активных записей: ").append(totalDb);
        if (modulation != null) {
            sb.append(" · на странице после фильтра по модуляции: ").append(shownOnPage);
        }
        sb.append(" · стр. ").append(pageIndex + 1).append('/').append(Math.max(1, totalPages));
        return sb.toString();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        shell.setStatus(message);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private static String blankToDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Иммутабельный перенос результата фонового потока на UI-поток. */
    private record PageData(List<RadioEmissionViewModel> rows, long total, int totalPages) {}
}
