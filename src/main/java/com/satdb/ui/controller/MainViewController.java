package com.satdb.ui.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import com.satdb.entity.AuditLog;
import com.satdb.entity.PendingChange;
import com.satdb.entity.RadioEmission;
import com.satdb.entity.User;
import com.satdb.entity.UserRole;
import com.satdb.repository.AuditLogRepository;
import com.satdb.security.SessionUserDetails;
import com.satdb.service.ApprovalService;
import com.satdb.service.RadioEmissionService;
import com.satdb.service.SpectrumAnalyzerService;
import com.satdb.service.UserService;
import com.satdb.ui.FxSpringViewManager;

/**
 * Контроллер главного окна (main.fxml) по шаблону 10-javafx-ui/SKILL.md.
 *
 * Ключевая идея — {@link #buildMenuByRole()}: содержимое MenuBar собирается
 * программно из матрицы прав UserRole (SKILL.md 02), поэтому оператор
 * физически не видит пункты «Согласования»/«Администрирование», а начальник
 * смены — «Пользователи». UI-ограничение дублирует серверное (@PreAuthorize
 * в сервисах) — defense in depth: скрытый пункт меню не является защитой.
 *
 * Spring-бины передаются конструктором через FxSpringControllerFactory
 * (шаблон 06-spring-boot-architecture): сервисы, репозиторий аудита и
 * менеджер экранов. Тяжёлые чтения выполняются на служебных потоках Task,
 * маппинг сущностей в строки таблиц — до передачи на FX-поток, чтобы не
 * ловить LazyInitializationException при open-in-view=false.
 */
public class MainViewController {

    @FXML private BorderPane rootPane;
    @FXML private MenuBar menuBar;
    @FXML private Label userBadge;
    @FXML private Label statusLabel;
    @FXML private Label pendingBadge;
    @FXML private TextArea contentArea;      // мономорфная «рабочая область» отчётов
    @FXML private HBox actionbar;            // контекстные кнопки под рабочей областью
    @FXML private TableView<?> dataTable;    // табличный режим (пользователи/аудит/заявки)

    /** Навигационный стек: Back возвращает предыдущий вид рабочей области. */
    private final Deque<Runnable> viewHistory = new ArrayDeque<>();

    private final UserService userService;
    private final ApprovalService approvalService;
    private final RadioEmissionService emissionService;
    private final SpectrumAnalyzerService analyzerService;
    private final AuditLogRepository auditLogRepository;
    private final FxSpringViewManager viewManager;

    public MainViewController(UserService userService,
                              ApprovalService approvalService,
                              RadioEmissionService emissionService,
                              SpectrumAnalyzerService analyzerService,
                              AuditLogRepository auditLogRepository,
                              FxSpringViewManager viewManager) {
        this.userService = userService;
        this.approvalService = approvalService;
        this.emissionService = emissionService;
        this.analyzerService = analyzerService;
        this.auditLogRepository = auditLogRepository;
        this.viewManager = viewManager;
    }

    @FXML
    private void initialize() {
        buildMenuByRole();
        showWelcome();
        updatePendingBadge();
    }

    // ==================== Адаптивное меню (SKILL.md 10) ====================

    private UserRole currentRole() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionUserDetails d) {
            return d.getRole();
        }
        return null;
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionUserDetails d) {
            return d.getUserId();
        }
        return null;
    }

    /** Полное имя пользователя для бейджа и сообщений. */
    private String currentDisplayName() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionUserDetails d) {
            return d.getUsername() + " (" + d.getFullName() + ", " + d.getRole() + ")";
        }
        return "не аутентифицирован";
    }

    /**
     * Динамическое меню: общие пункты для всех + специфичные для ролей.
     * Матрица прав SKILL.md 02:
     *  - все роли      → Данные / Поиск / Спектр / Согласования(мои заявки)
     *  - ADMIN+SUPER   → очередь согласований, журнал аудита (кроме users)
     *  - OPERATOR      → «Мои запросы» (workflow вместо прямого редактирования)
     *  - ADMIN         → Администрирование (пользователи)
     */
    private void buildMenuByRole() {
        UserRole role = currentRole();
        if (role == null) {
            userBadge.setText("не аутентифицирован");
            return;
        }
        userBadge.setText(role.name() + " · " + currentDisplayName());
        menuBar.getMenus().clear();

        // ---- Файл (общий для всех) ----
        Menu fileMenu = new Menu("Файл");
        fileMenu.getItems().addAll(
                item("Обновить", this::showWelcome),
                item("Свернуть окно", () -> viewManager.getPrimaryStage().setIconified(true)),
                new SeparatorMenuItem(),
                item("Выход", this::onLogout));
        menuBar.getMenus().add(fileMenu);

        // ---- Данные (общие для всех ролей) ----
        Menu dataMenu = new Menu("Данные");
        dataMenu.getItems().addAll(
                item("Радиоизлучения (активные)", this::showActiveEmissions),
                item("Поиск по спектру (FTS5)…", this::showFtsSearchPrompt),
                item("Очередь радиоизлучений", this::showRecentAuditForEmissions));
        menuBar.getMenus().add(dataMenu);

        // ---- Аналитика спектра (общая: только чтение) ----
        Menu spectrumMenu = new Menu("Спектр");
        spectrumMenu.getItems().addAll(
                item("Риски интерференции", this::showInterferenceRisks),
                item("Пульс данных (живучесть БД)", this::showDataPulse),
                item("Дубликаты конфигураций сигналов", this::showDuplicates));
        menuBar.getMenus().add(spectrumMenu);

        // ---- Согласования: зависит от роли ----
        Menu approvalMenu = new Menu("Согласования");
        if (role.canApproveChanges()) {                       // ADMIN, SHIFT_SUPERVISOR
            approvalMenu.getItems().add(item("⚠ Ожидают рассмотрения", this::showPendingQueue));
            approvalMenu.getItems().add(item("Рассмотренные мной", this::showReviewedByMe));
        } else {                                              // OPERATOR
            approvalMenu.getItems().add(item("Мои запросы", this::showMyRequests));
        }
        if (role == UserRole.OPERATOR) {
            approvalMenu.getItems().add(new SeparatorMenuItem());
            approvalMenu.getItems().add(item("Новый запрос на изменение…",
                    this::showRequestChangeHelp));
        }
        menuBar.getMenus().add(approvalMenu);

        // ---- Журнал аудита: ADMIN + SHIFT_SUPERVISOR (SKILL.md 03) ----
        if (role.canViewAuditLog()) {
            Menu auditMenu = new Menu("Аудит");
            auditMenu.getItems().addAll(
                    item("Последние изменения", this::showRecentAudit),
                    item("История записи (по id)…", this::showRecordHistoryPrompt));
            menuBar.getMenus().add(auditMenu);
        }

        // ---- Администрирование: только ADMIN (SKILL.md 02, правило 3) ----
        if (role.canManageUsers()) {
            Menu adminMenu = new Menu("Администрирование");
            adminMenu.getItems().add(item("Пользователи", this::showUsers));
            menuBar.getMenus().add(adminMenu);
        }

        // ---- Справка (общая) ----
        Menu helpMenu = new Menu("Справка");
        helpMenu.getItems().add(item("О программе", this::showAbout));
        menuBar.getMenus().add(helpMenu);
    }

    private MenuItem item(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> pushAndRun(action));
        return mi;
    }

    /** Навигация с историей: каждый переход кладём в стек — работает «Назад». */
    private void pushAndRun(Runnable view) {
        viewHistory.push(this::currentSnapshotRestorer);
        view.run();
    }

    /** Back: восстановить последний снимок (текст+таблица) или выйти. */
    private String lastContentText = "";
    private Runnable lastTableLoader = () -> { };

    private void currentSnapshotRestorer() {
        contentArea.setText(lastContentText);
        lastTableLoader.run();
    }

    @FXML
    private void onBack() {
        Runnable prev = viewHistory.poll();
        if (prev != null) {
            prev.run();
            statusLabel.setText("Навигация: назад");
        } else {
            showWelcome();
        }
    }

    // ==================== Рабочая область ====================

    private void showText(String title, String report, Runnable tableLoader) {
        lastContentText = report;
        lastTableLoader = tableLoader;
        contentArea.setText(report);
        statusLabel.setText(title);
        clearTable();
        if (tableLoader != null) {
            tableLoader.run();
        }
    }

    private void showText(String title, String report) {
        showText(title, report, null);
    }

    private void showWelcome() {
        UserRole role = currentRole();
        String hint = switch (role == null ? UserRole.OPERATOR : role) {
            case ADMIN -> "Полные права: редактирование напрямую, пользователи, аудит.";
            case SHIFT_SUPERVISOR -> "Редактирование напрямую + согласование заявок. Пользователи — только админу.";
            case OPERATOR -> "Изменения и удаления — только через «Согласования → Новый запрос».";
        };
        showText("Главная",
                """
                Satellite Database — база спутниковых систем и радиоизлучений.

                Текущий пользователь: %s

                %s

                Меню адаптировано под вашу роль (SKILL.md 02-rbac).
                Выберите раздел в MenuBar или используйте кнопки панели действий.
                """.formatted(currentDisplayName(), hint));
        setActionbarButtons(List.of(
                button("Активные излучения", this::showActiveEmissions),
                button("Риски интерференции", this::showInterferenceRisks),
                button("← Назад", this::onBack)));
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> pushAndRun(action));
        return b;
    }

    private void setActionbarButtons(List<Button> buttons) {
        Platform.runLater(() -> {
            actionbar.getChildren().setAll(buttons);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            actionbar.getChildren().add(spacer);
        });
    }

    // ==================== Данные ====================

    /**
     * Пункт меню «Радиоизлучения (активные)»: вместо текстового отчёта God-объекта
     * открывает специализированную секцию emission-list.fxml (BorderPane.setCenter()
     * через FxSpringViewManager.openSection -> ShellService.setCenter) и вызывает
     * EmissionListController.show() — таблица + фильтры по частоте/модуляции.
     */
    private void showActiveEmissions() {
        viewManager.<EmissionListController>openSection(FxSpringViewManager.View.EMISSION_LIST)
                .ifPresent(EmissionListController::show);
    }

    /** Текстовый отчёт по активным излучениям (резервный вид, вызывается из «Файл → Обновить»). */
    private void showActiveEmissionsReport() {
        setStatus("Загрузка активных излучений…");
        runAsync(() -> emissionService.findAll(PageRequest.of(0, 300)).getContent(),
                page -> {
                    StringBuilder sb = new StringBuilder("Радиоизлучения (top ").append(page.size())
                            .append(", без is_deleted):\n\n");
                    for (RadioEmission e : page) {
                        sb.append(String.format("#%-4d %-22s %-28s %12s МГц  %9s кГц  %s%n",
                                e.getId(), nz(e.getEmissionCode()), nz(e.getEmissionName()),
                                e.getFrequencyMhz(), e.getBandwidthKhz(),
                                e.getClassification()));
                    }
                    loadEmissionsTable(page);
                    showText("Радиоизлучения", sb.toString(), () -> loadEmissionsTable(page));
                });
    }

    /** Таблица излучений: колонки типизированы, значения читаются на FX-потоке из already-loaded сущностей. */
    private void loadEmissionsTable(List<RadioEmission> rows) {
        Platform.runLater(() -> {
            @SuppressWarnings("unchecked")
            TableView<RadioEmission> t = (TableView<RadioEmission>) ensureTable();
            TableColumn<RadioEmission, Long> id = new TableColumn<>("ID");
            id.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().getId()));
            TableColumn<RadioEmission, String> code = new TableColumn<>("Код");
            code.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().getEmissionCode())));
            TableColumn<RadioEmission, String> name = new TableColumn<>("Наименование");
            name.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().getEmissionName())));
            TableColumn<RadioEmission, BigDecimal> freq = new TableColumn<>("Частота, МГц");
            freq.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().getFrequencyMhz()));
            TableColumn<RadioEmission, BigDecimal> bw = new TableColumn<>("Полоса, кГц");
            bw.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().getBandwidthKhz()));
            TableColumn<RadioEmission, String> mod = new TableColumn<>("Модуляция");
            mod.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().getModulationType())));
            t.getColumns().setAll(List.of(id, code, name, freq, bw, mod));
            t.setItems(FXCollections.observableArrayList(rows));
        });
    }

    private void showFtsSearchPrompt() {
        TextInput prompt = new TextInput(contentArea, statusLabel);
        prompt.ask("Поиск по спектру (FTS5)",
                "Запрос MATCH: 'gauss' OR 'beacon', частоты '145 MHz' AND near('downlink')…",
                q -> {
                    if (q == null || q.isBlank()) {
                        return;
                    }
                    setStatus("FTS5-поиск: " + q);
                    runAsync(() -> emissionService.fullTextSearch(q.trim()),
                            list -> {
                                StringBuilder sb = new StringBuilder("FTS5: \"" + q + "\" → "
                                        + list.size() + " совпадений:\n\n");
                                for (RadioEmission e : list) {
                                    sb.append(String.format("#%-4d %-22s %-12s МГц  %s%n",
                                            e.getId(), nz(e.getEmissionCode()),
                                            e.getFrequencyMhz(), nz(e.getEmissionName())));
                                }
                                showText("Полнотекстовый поиск", sb.toString(),
                                        () -> loadEmissionsTable(list));
                            });
                });
    }

    private void showRecentAuditForEmissions() {
        setStatus("Последние изменения радиоизлучений…");
        runAsync(() -> auditLogRepository.findByTableNameAndRecordIdOrderByChangedAtDesc(
                        "radio_emissions", firstEmissionIdOrNull()),
                logs -> showAudit("История первого доступного излучения", logs));
    }

    private Long firstEmissionIdOrNull() {
        try {
            List<RadioEmission> all = emissionService.findAll(PageRequest.of(0, 1)).getContent();
            return all.isEmpty() ? null : all.get(0).getId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ==================== Спектр (creative analytics) ====================

    private void showInterferenceRisks() {
        setStatus("Расчёт рисков интерференции…");
        runAsync(analyzerService::findInterferenceRisks, risks -> {
            StringBuilder sb = new StringBuilder("Межсистемные риски перекрытия каналов"
                    + " (доплеровский буфер по орбитальным параметрам):\n\n");
            if (risks.isEmpty()) {
                sb.append("Рисков не обнаружено — спектр чист.\n");
            }
            for (SpectrumAnalyzerService.InterferenceRisk r : risks) {
                sb.append(String.format("[%s] %s ↔ %s, overlap %.1f%%%n→ %s%n%n",
                        r.severity(), r.emissionA(), r.emissionB(),
                        r.overlapPct(), r.recommendation()));
            }
            showText("Риски интерференции", sb.toString());
        });
    }

    private void showDataPulse() {
        setStatus("Метрики живучести данных…");
        runAsync(() -> analyzerService.dataPulse(LocalDateTime.now().minusDays(7)), p -> {
            String report = """
                    Пульс базы за 7 дней:
                      событий аудита:        %d (%.1f/час)
                      среднее время согласования: %.1f ч
                      PENDING в очереди:     %d
                      активных излучений:    %d | удалённых: %d
                      гигиена данных:        %.1f%%
                    По таблицам: %s
                    По действиям: %s
                    """.formatted(p.auditEvents(), p.eventsPerHour(), p.avgApprovalHours(),
                    p.pendingQueueSize(), p.activeEmissions(), p.deletedEmissions(),
                    p.hygieneScorePct(), p.byTable(), p.byAction());
            showText("Пульс данных", report);
        });
    }

    private void showDuplicates() {
        setStatus("Поиск дубликатов конфигураций (FNV-1a отпечатки)…");
        runAsync(analyzerService::duplicateReport, dupes -> {
            StringBuilder sb = new StringBuilder("Группы одинаковых конфигураций сигнала"
                    + " (частота+полоса+мощность+модуляция+поляризация):\n\n");
            if (dupes.isEmpty()) {
                sb.append("Дубликатов нет.\n");
            }
            dupes.forEach((fp, codes) -> sb.append(fp).append(": ").append(codes).append('\n'));
            showText("Дубликаты", sb.toString());
        });
    }

    // ==================== Согласования ====================

    private void showPendingQueue() {
        setStatus("Очередь согласований…");
        runAsync(() -> approvalService.pendingQueue(PageRequest.of(0, 100)).getContent(),
                list -> showChanges("Ожидают рассмотрения (PENDING)", list, true));
    }

    private void showMyRequests() {
        Long me = currentUserId();
        runAsync(() -> approvalService.myRequests(me, PageRequest.of(0, 100)).getContent(),
                list -> showChanges("Мои запросы", list, false));
    }

    private void showReviewedByMe() {
        Long me = currentUserId();
        runAsync(() -> approvalService.reviewedByMe(me, PageRequest.of(0, 100)).getContent(),
                list -> showChanges("Рассмотренные мной", list, false));
    }

    private void showChanges(String title, List<PendingChange> list, boolean actionable) {
        StringBuilder sb = new StringBuilder(title + ": " + list.size() + " шт.\n\n");
        for (PendingChange c : list) {
            sb.append(String.format("#%-4d %s %s.%d [%s]%n   запрошено: %s %s%n   обоснование: %s%n%n",
                    c.getId(), c.getAction(), c.getTableName(), c.getRecordId(), c.getStatus(),
                    c.getRequestedBy() == null ? "?" : c.getRequestedBy().getUsername(),
                    c.getRequestedAt(), nz(c.getReason())));
        }
        if (actionable && !list.isEmpty()) {
            PendingChange first = list.get(0);
            setActionbarButtons(List.of(
                    button("✓ Одобрить #" + first.getId(), () -> review(first.getId(), true)),
                    button("✗ Отклонить #" + first.getId(), () -> review(first.getId(), false)),
                    button("← Назад", this::onBack)));
        } else {
            setActionbarButtons(List.of(button("← Назад", this::onBack)));
        }
        showText(title, sb.toString());
    }

    private void review(Long changeId, boolean approve) {
        String comment = approve ? "Одобрено из десктоп-клиента" : "Отклонено: требуется уточнение";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                (approve ? "Одобрить" : "Отклонить") + " заявку #" + changeId + "?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                runAsync(() -> approvalService.reviewChange(currentUserId(), changeId, approve, comment),
                        pc -> {
                            updatePendingBadge();
                            showStatusToast("Заявка #" + changeId + " → " + pc.getStatus());
                            showPendingQueue();
                        }));
    }

    private void showRequestChangeHelp() {
        showText("Запрос на изменение",
                """
                Как оператору изменить запись (SKILL.md 04-approval-workflow):

                  1. REST POST /api/radio-emissions/{id}/request-change
                     тело: {"changes": {...}, "reason": "обязательное обоснование"}
                  2. Заявка попадает в pending_changes со статусом PENDING.
                  3. Начальник смены рассматривает её в меню «Согласования».
                  4. При одобрении изменение применяется и фиксируется в audit_log.

                Прямые PUT/DELETE для роли OPERATOR запрещены на уровне сервиса.""");
    }

    // ==================== Аудит ====================

    private void showRecentAudit() {
        setStatus("Журнал аудита…");
        runAsync(() -> auditLogRepository.findByChangedAtBetweenOrderByChangedAtDesc(
                        LocalDateTime.now().minusDays(30), LocalDateTime.now(),
                        PageRequest.of(0, 50)).getContent(),
                logs -> showAudit("Изменения за 30 дней (top 50)", logs));
    }

    private void showRecordHistoryPrompt() {
        new TextInput(contentArea, statusLabel).ask("История записи",
                "Формат: таблица:id — например radio_emissions:1", input -> {
                    if (input == null || !input.contains(":")) {
                        return;
                    }
                    String table = input.substring(0, input.indexOf(':')).trim();
                    Long id = Long.parseLong(input.substring(input.indexOf(':') + 1).trim());
                    runAsync(() -> auditLogRepository.findRecordHistory(table, id,
                                    LocalDateTime.now().minusYears(5), LocalDateTime.now()),
                            logs -> showAudit("История " + table + "#" + id, logs));
                });
    }

    private void showAudit(String title, List<AuditLog> logs) {
        StringBuilder sb = new StringBuilder(title + ": " + logs.size() + " записей (append-only).\n\n");
        for (AuditLog a : logs) {
            sb.append(String.format("%s  %-8s %-18s#%-5d %s by %s (%s)%n",
                    a.getChangedAt(), a.getAction(), a.getTableName(), a.getRecordId(),
                    nz(a.getChangedFields()), nz(a.getUserName()), nz(a.getUserRole())));
        }
        showText(title, sb.toString());
    }

    // ==================== Пользователи (ADMIN) ====================

    private void showUsers() {
        Long me = currentUserId();
        runAsync(() -> userService.findActiveUsers(me), users -> {
            StringBuilder sb = new StringBuilder("Активные пользователи (только ADMIN):\n\n");
            for (User u : users) {
                sb.append(String.format("#%-3d %-15s %-20s %-18s %s%n",
                        u.getId(), u.getUsername(), nz(u.getFullName()), u.getRole(),
                        nz(u.getEmail())));
            }
            loadUsersTable(users);
            showText("Пользователи", sb.toString(), () -> loadUsersTable(users));
        });
    }

    private void loadUsersTable(List<User> users) {
        Platform.runLater(() -> {
            @SuppressWarnings("unchecked")
            TableView<User> t = (TableView<User>) ensureTable();
            TableColumn<User, Long> id = new TableColumn<>("ID");
            id.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().getId()));
            TableColumn<User, String> login = new TableColumn<>("Логин");
            login.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().getUsername()));
            TableColumn<User, String> fio = new TableColumn<>("ФИО");
            fio.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().getFullName())));
            TableColumn<User, UserRole> role = new TableColumn<>("Роль");
            role.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().getRole()));
            TableColumn<User, String> email = new TableColumn<>("Email");
            email.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().getEmail())));
            t.getColumns().setAll(List.of(id, login, fio, role, email));
            t.setItems(FXCollections.observableArrayList(users));
        });
    }

    // ==================== Утилиты ====================

    private void updatePendingBadge() {
        UserRole role = currentRole();
        if (role == null || !role.canApproveChanges()) {
            Platform.runLater(() -> pendingBadge.setText(""));
            return;
        }
        runAsync(() -> approvalService.pendingQueue(PageRequest.of(0, 1)).getTotalElements(),
                n -> Platform.runLater(() -> pendingBadge.setText(
                        n > 0 ? "⚠ PENDING: " + n : "✓ очередь пуста")));
    }

    private TableView<?> ensureTable() {
        if (dataTable == null) {
            dataTable = new TableView<>();
            rootPane.setBottom(dataTable);
        }
        return dataTable;
    }

    private void clearTable() {
        Platform.runLater(() -> {
            if (dataTable != null) {
                dataTable.setItems(FXCollections.emptyObservableList());
            }
        });
    }

    /** Чтение/запись вне FX-потока; ошибки показываем в statusLabel, не роняя UI. */
    private <T> void runAsync(Background<T> work, java.util.function.Consumer<T> onUi) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception {
                return work.get();
            }
        };
        task.setOnSucceeded(e -> onUi.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            setStatus("Ошибка: " + ex.getMessage());
            ex.printStackTrace();
        });
        Thread th = new Thread(task, "fx-main-load");
        th.setDaemon(true);
        th.start();
    }

    @FunctionalInterface
    private interface Background<T> {
        T get() throws Exception;
    }

    private void setStatus(String s) {
        Platform.runLater(() -> statusLabel.setText(s));
    }

    private void showStatusToast(String s) {
        new Alert(Alert.AlertType.INFORMATION, s, ButtonType.OK).showAndWait();
    }

    private static String nz(String s) {
        return s == null ? "—" : s;
    }

    @FXML
    public void onLogout() {
        SecurityContextHolder.clearContext();
        viewManager.showLogin();
    }

    private void showAbout() {
        Map<String, String> info = Map.of(
                "Приложение", "Satellite DB Desktop (JavaFX + Spring Boot 3.2)",
                "БД", "SQLite + Flyway + FTS5",
                "Роли", "ADMIN / SHIFT_SUPERVISOR / OPERATOR (SKILL.md 02)");
        showText("О программе", "О программе:\n" + info.entrySet().stream()
                .map(e -> "  " + e.getKey() + ": " + e.getValue())
                .reduce("", (a, b) -> a + b + "\n"));
    }

    /** Мини-диалог ввода поверх TextArea (без модальных окон-фабрик). */
    private static final class TextInput {
        private final TextArea target;
        private final Label status;

        TextInput(TextArea target, Label status) {
            this.target = target;
            this.status = status;
        }

        void ask(String title, String prompt, java.util.function.Consumer<String> handler) {
            TextInputDialogShim dialog = new TextInputDialogShim(title, prompt);
            dialog.showAndWait().ifPresent(v -> {
                status.setText(title + ": " + v);
                handler.accept(v);
            });
        }
    }

    /** Обёртка над javafx TextInputDialog (ленивая — диалоги не требуют Spring). */
    private static final class TextInputDialogShim {
        private final javafx.scene.control.TextInputDialog delegate;

        TextInputDialogShim(String title, String prompt) {
            delegate = new javafx.scene.control.TextInputDialog();
            delegate.setTitle(title);
            delegate.setHeaderText(null);
            delegate.setContentText(prompt);
            delegate.getEditor().setPrefColumnCount(40);
        }

        java.util.Optional<String> showAndWait() {
            return delegate.showAndWait();
        }
    }
}
