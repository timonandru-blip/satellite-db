package com.satdb.ui.controller;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import com.satdb.entity.RadioEmission;
import com.satdb.entity.SatelliteSystem;
import com.satdb.entity.UserRole;
import com.satdb.repository.SatelliteSystemRepository;
import com.satdb.security.SessionUserDetails;
import com.satdb.service.ApprovalService;
import com.satdb.service.RadioEmissionService;
import com.satdb.ui.FxSpringViewManager;
import com.satdb.ui.viewmodel.EmissionRow;

/**
 * Главный экран после входа: таблица активных излучений + элементы,
 * зависящие от роли (RBAC из SKILL.md 02).
 *
 * Все бины приходят конструктором через FxSpringControllerFactory —
 * это и есть демонстрация передачи Spring-бинов в FXML-контроллеры.
 * Тяжёлые чтения выполняются вне FX-потока (Task), колонки типизированы
 * JavaFX-свойствами вместо ручных cellValueFactory.
 */
public class DashboardController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private Label userBadge;
    @FXML private Label statusLabel;
    @FXML private Label pendingBadge;
    @FXML private MenuButton navMenuButton;
    @FXML private TableView<EmissionRow> emissionsTable;

    private final RadioEmissionService emissionService;
    private final SatelliteSystemRepository systemRepository;
    private final ApprovalService approvalService;
    private final FxSpringViewManager viewManager;

    public DashboardController(RadioEmissionService emissionService,
                               SatelliteSystemRepository systemRepository,
                               ApprovalService approvalService,
                               FxSpringViewManager viewManager) {
        this.emissionService = emissionService;
        this.systemRepository = systemRepository;
        this.approvalService = approvalService;
        this.viewManager = viewManager;
    }

    @FXML
    private void initialize() {
        buildColumns();
        configureNavigationByRole();
        loadEmissions();
    }

    // ---- RBAC: меню зависит от роли текущего пользователя ----

    private void configureNavigationByRole() {
        SessionUserDetails me = currentSession();
        if (me == null) {
            userBadge.setText("не аутентифицирован");
            return;
        }
        UserRole role = me.getRole();
        userBadge.setText(role.name() + " · " + me.getUsername());
        navMenuButton.getItems().clear();

        if (role.canApproveChanges()) {          // ADMIN, SHIFT_SUPERVISOR
            navMenuButton.getItems().add(new MenuItem("Очередь согласований"));
        }
        if (role.canViewAuditLog()) {            // ADMIN, SHIFT_SUPERVISOR
            navMenuButton.getItems().add(new MenuItem("Журнал аудита"));
        }
        if (role.canManageUsers()) {             // только ADMIN
            navMenuButton.getItems().add(new MenuItem("Пользователи"));
        }
        navMenuButton.getItems().add(new MenuItem("Поиск по спектру (FTS5)"));
        if (role == UserRole.OPERATOR) {
            statusLabel.setText("Роль OPERATOR: изменения — через запрос на согласование.");
        }
    }

    private void buildColumns() {
        TableColumn<EmissionRow, String> code = new TableColumn<>("Код");
        code.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().code()));

        TableColumn<EmissionRow, String> name = new TableColumn<>("Наименование");
        name.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().name()));

        TableColumn<EmissionRow, String> system = new TableColumn<>("Система");
        system.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().systemName()));

        TableColumn<EmissionRow, BigDecimal> freq = new TableColumn<>("Частота, МГц");
        freq.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().frequencyMhz()));

        TableColumn<EmissionRow, BigDecimal> bw = new TableColumn<>("Полоса, кГц");
        bw.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().bandwidthKhz()));

        TableColumn<EmissionRow, String> mod = new TableColumn<>("Модуляция");
        mod.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().modulation()));

        TableColumn<EmissionRow, String> created = new TableColumn<>("Обновлено");
        created.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().updatedAt()));

        emissionsTable.getColumns().setAll(List.of(code, name, system, freq, bw, mod, created));
    }

    /** Чтение БД на служебном потоке, сеттинг модели — на FX-потоке. */
    @FXML
    public void onRefresh() {
        loadEmissions();
    }

    private void loadEmissions() {
        statusLabel.setText("Загрузка…");
        Task<List<EmissionRow>> task = new Task<>() {
            @Override protected List<EmissionRow> call() {
                var page = emissionService.findAll(PageRequest.of(0, 200));
                return page.getContent().stream().map(DashboardController::toRow).toList();
            }
        };
        task.setOnSucceeded(e -> {
            emissionsTable.setItems(FXCollections.observableArrayList(task.getValue()));
            updatePendingBadge();
            statusLabel.setText("Записей: " + task.getValue().size()
                    + " (активные, без is_deleted)");
        });
        task.setOnFailed(e -> statusLabel.setText(
                "Ошибка загрузки: " + task.getException().getMessage()));
        Thread t = new Thread(task, "fx-dashboard-load");
        t.setDaemon(true);
        t.start();
    }

    private void updatePendingBadge() {
        try {
            long pending = approvalService.pendingQueue(PageRequest.of(0, 1)).getTotalElements();
            pendingBadge.setText(pending > 0 ? "⚠ PENDING заявок: " + pending : "");
        } catch (RuntimeException ex) {
            pendingBadge.setText("");   // оператору очередь может быть недоступна
        }
    }

    private static EmissionRow toRow(RadioEmission e) {
        SatelliteSystem s = e.getSatelliteSystem();
        return new EmissionRow(
                e.getId(),
                e.getEmissionCode(),
                e.getEmissionName(),
                s != null ? s.getSystemName() : "—",
                e.getFrequencyMhz(),
                e.getBandwidthKhz(),
                e.getModulationType() == null ? "" : e.getModulationType(),
                e.getUpdatedAt() == null ? "" : e.getUpdatedAt().format(TS));
    }

    @FXML
    public void onLogout() {
        SecurityContextHolder.clearContext();
        viewManager.showLogin();
    }

    private static SessionUserDetails currentSession() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionUserDetails d) {
            return d;
        }
        return null;
    }
}
