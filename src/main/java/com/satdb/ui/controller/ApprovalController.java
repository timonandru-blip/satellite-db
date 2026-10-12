package com.satdb.ui.controller;

import java.time.format.DateTimeFormatter;
import java.util.List;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;

import com.satdb.dto.EntityMapper;
import com.satdb.dto.PendingChangeDto;
import com.satdb.entity.PendingChange;
import com.satdb.service.ApprovalService;
import com.satdb.ui.UiExceptionHandler;

/**
 * Экран «Согласования» (approval-view.fxml) — очередь заявок на изменение.
 *
 * Ответственности (SRP):
 *  - отображение списка PendingChangeDto в TableView (маппинг сущность→DTO через EntityMapper);
 *  - действия ревьюера onApprove()/onReject() -> ApprovalService.reviewChange(...) (Spring DI);
 *  - отзыв собственной заявки onCancelOwn() -> ApprovalService.cancelChange(...);
 *  - после каждой операции таблица перезагружается из источника (без «ручного» патча модели).
 *
 * Права (SKILL.md 02-rbac): одобрять/отклонять могут только ADMIN и SHIFT_SUPERVISOR —
 * кнопки скрываются для OPERATOR как UX-контур, но главный контур — серверная проверка
 * PermissionService.checkCanApprove внутри ApprovalService (здесь ловится AccessDeniedException).
 * Запрет самосогласования (автор != ревьюер) также инвариант сервиса.
 *
 * Исключения: все пути ошибок проходят через UiExceptionHandler.handle(e, "Ошибка согласования")
 * — оператор никогда не увидит SQL/стектрейс.
 */
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int PAGE_SIZE = 200;

    // ==================== FXML-поля ====================

    @FXML private BorderPane sectionRoot;
    @FXML private TableView<PendingChangeDto> changesTable;
    @FXML private TableColumn<PendingChangeDto, Long> colId;
    @FXML private TableColumn<PendingChangeDto, String> colAction;
    @FXML private TableColumn<PendingChangeDto, String> colTarget;
    @FXML private TableColumn<PendingChangeDto, String> colStatus;
    @FXML private TableColumn<PendingChangeDto, String> colRequestedBy;
    @FXML private TableColumn<PendingChangeDto, String> colRequestedAt;
    @FXML private TableColumn<PendingChangeDto, String> colReason;
    @FXML private TableColumn<PendingChangeDto, String> colDiff;
    @FXML private TableColumn<PendingChangeDto, String> colReviewer;
    @FXML private TableColumn<PendingChangeDto, String> colComment;
    @FXML private Label infoLabel;
    @FXML private Label errorLabel;
    @FXML private Button approveButton;
    @FXML private Button rejectButton;
    @FXML private Button cancelButton;

    // ==================== Spring DI (конструкторная инъекция) ====================

    private final ApprovalService approvalService;
    private final EntityMapper mapper;
    private final ShellService shell;

    public ApprovalController(ApprovalService approvalService,
                              EntityMapper mapper,
                              ShellService shell) {
        this.approvalService = approvalService;   // бизнес-логика workflow
        this.mapper = mapper;                    // сущность -> DTO
        this.shell = shell;                      // навигация/сессия/async-обёртки
    }

    // ==================== инициализация ====================

    @FXML
    private void initialize() {
        bindColumns();
        changesTable.setPlaceholder(new Label("Нет заявок в выбранном режиме"));

        // Выбор строки управляет доступностью кнопок review (null-safe).
        changesTable.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldSel, sel) -> updateActionButtons(sel));

        // Режимы по умолчанию — только для тех, кто может рассматривать.
        boolean reviewer = shell.canApproveChanges();
        approveButton.setVisible(reviewer);
        approveButton.setManaged(reviewer);
        rejectButton.setVisible(reviewer);
        rejectButton.setManaged(reviewer);
    }

    /** Привязка колонок к компонентам record PendingChangeDto (read-only обёртки). */
    private void bindColumns() {
        colId.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().id()));
        colAction.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().action())));
        colTarget.setCellValueFactory(d -> new ReadOnlyStringWrapper(
                nz(d.getValue().tableName()) + " # " + d.getValue().recordId()));
        colStatus.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().status())));
        colRequestedBy.setCellValueFactory(d -> new ReadOnlyStringWrapper(
                nz(d.getValue().requestedByUsername())
                        + roleSuffix(d.getValue().requestedUserRole())));
        colRequestedAt.setCellValueFactory(d -> new ReadOnlyStringWrapper(fmt(d.getValue().requestedAt())));
        colReason.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().reason())));
        // Δ значений: компактный намёк на proposed_values без полного JSON (длинный текст режем).
        colDiff.setCellValueFactory(d -> new ReadOnlyStringWrapper(diffHint(d.getValue())));
        colReviewer.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().reviewedByUsername())));
        colComment.setCellValueFactory(d -> new ReadOnlyStringWrapper(nz(d.getValue().reviewComment())));
    }

    // ==================== публичный вход точки открытия ====================

    /** Открыть секцию в центре оболочки и загрузить указанный режим. */
    public void show(Mode mode) {
        shell.setCenter(sectionRoot);
        load(mode);
    }

    public void show() {
        show(shell.canApproveChanges() ? Mode.QUEUE : Mode.MINE);
    }

    /** Режимы просмотра — три разных источника данных, один UI. */
    public enum Mode { QUEUE, MINE, REVIEWED }

    private Mode currentMode = Mode.QUEUE;

    // ==================== кнопки панелей режимов ====================

    @FXML private void onQueue()      { load(Mode.QUEUE); }
    @FXML private void onMyRequests() { load(Mode.MINE); }
    @FXML private void onReviewed()   { load(Mode.REVIEWED); }
    @FXML private void onRefresh()    { load(currentMode); }

    // ==================== загрузка таблицы ====================

    /**
     * Тяжёлое чтение (SQLite + маппинг в DTO) выполняется на служебном потоке
     * ShellService.runAsync, обновление ObservableList — только на FX-потоке.
     * Ошибки загрузки централизованно обрабатывает runAsync -> UiExceptionHandler.
     */
    private void load(Mode mode) {
        this.currentMode = mode;
        hideError();
        Long me = shell.currentUserId();
        if (me == null) {
            shell.setStatus("Нет активной сессии — войдите заново");
            changesTable.setItems(FXCollections.emptyObservableList());
            return;
        }
        shell.runAsync(() -> {
            var page = switch (mode) {
                case QUEUE    -> approvalService.pendingQueue(PageRequest.of(0, PAGE_SIZE));
                case MINE     -> approvalService.myRequests(me, PageRequest.of(0, PAGE_SIZE));
                case REVIEWED -> approvalService.reviewedByMe(me, PageRequest.of(0, PAGE_SIZE));
            };
            List<PendingChange> entities = page.getContent();
            return mapper.toPendingChangeDtoList(entities);
        }, dtos -> {
            changesTable.setItems(FXCollections.observableArrayList(dtos));
            infoLabel.setText(switch (mode) {
                case QUEUE    -> "Ожидают рассмотрения: " + dtos.size();
                case MINE     -> "Мои запросы: " + dtos.size();
                case REVIEWED -> "Рассмотрено мной: " + dtos.size();
            });
            updateActionButtons(changesTable.getSelectionModel().getSelectedItem());
        });
    }

    // ==================== onApprove / onReject ====================

    /**
     * Одобрение выбранной заявки: confirm-диалог -> ApprovalService.reviewChange(me, id, true, comment).
     * Сервис применит proposed_values к записи, создаст запись audit_log и переведёт
     * заявку в APPROVED; мы лишь перезагружаем таблицу из источника.
     */
    @FXML
    public void onApprove() {
        PendingChangeDto selected = requirePendingSelection();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Одобрить заявку #" + selected.id() + " ("
                        + nz(selected.action()) + " " + nz(selected.tableName())
                        + " #" + selected.recordId() + ")?\n\nИзменение будет применено к записи.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Согласование изменения");
        confirm.setHeaderText(null);
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                review(selected, true, "Одобрено из десктоп-клиента"));
    }

    /**
     * Отклонение выбранной заявки: причина обязательна (инвариант сервиса),
     * запрашивается через TextInputDialog; пустой ответ отменяет операцию.
     */
    @FXML
    public void onReject() {
        PendingChangeDto selected = requirePendingSelection();
        if (selected == null) {
            return;
        }
        TextInputDialog dlg = new TextInputDialog("");
        dlg.setTitle("Отклонение заявки #" + selected.id());
        dlg.setHeaderText("Комментарий-причина обязателен:");
        dlg.setContentText("Что исправить автору заявки?");
        dlg.showAndWait()
           .filter(comment -> !comment.isBlank())
           .ifPresent(comment -> review(selected, false, comment.trim()));
        // Пустой ввод / cancel — операция молча отменена (ничего не меняем).
    }

    /** Общий вызов сервиса с обработкой ошибок и перезагрузкой таблицы. */
    private void review(PendingChangeDto selected, boolean approved, String comment) {
        Long me = shell.currentUserId();
        if (me == null) {
            showError("Сессия истекла — повторите действие после перелогина");
            return;
        }
        try {
            // Синхронный вызов допустим: SQLite-операция короткая, а диалог уже модальный.
            // Альтернатива runAsync использована для отмены — здесь важен immediate feedback.
            PendingChange result = approvalService.reviewChange(me, selected.id(), approved, comment);
            shell.setStatus("Заявка #" + selected.id() + " → " + result.getStatus());
            hideError();
            load(currentMode);                       // обновляем таблицу из источника
        } catch (Exception e) {
            // Централизованный хендлер: Validation/AccessDenied/NotFound -> ALERT/WARNING,
            // технические ошибки -> обезличенное сообщение без SQL-деталей.
            UiExceptionHandler.handle(e, "Ошибка согласования");
            showError(shortReason(e));
            log.warn("review change #{} failed: {}", selected.id(), e.getMessage());
        }
    }

    /** Отзыв собственной PENDING-заявки (для автора или админа — решает сервис). */
    @FXML
    private void onCancelOwn() {
        PendingChangeDto selected = requirePendingSelection();
        if (selected == null) {
            return;
        }
        Long me = shell.currentUserId();
        if (me == null) {
            showError("Сессия истекла");
            return;
        }
        shell.runAsync(() -> approvalService.cancelChange(me, selected.id()), pc -> {
            shell.setStatus("Заявка #" + pc.getId() + " отменена");
            load(currentMode);
        });
    }

    // ==================== вспомогательные методы (null-safe) ====================

    /** Валидация выбора: строка выбрана, статус PENDING, права на review есть. */
    private PendingChangeDto requirePendingSelection() {
        PendingChangeDto selected = changesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Выберите заявку в таблице");
            return null;
        }
        if (!"PENDING".equals(selected.status())) {
            showError("Заявка #" + selected.id() + " уже рассмотрена (статус "
                    + nz(selected.status()) + ")");
            return null;
        }
        if (!shell.canApproveChanges()) {
            showError("Одобрять/отклонять могут только Supervisor и Admin");
            return null;
        }
        return selected;
    }

    /** Кнопки активны только на PENDING-строке; «Отозвать» — автору заявки или админу. */
    private void updateActionButtons(PendingChangeDto sel) {
        boolean pendingSelected = sel != null && "PENDING".equals(sel.status());
        approveButton.setDisable(!pendingSelected);
        rejectButton.setDisable(!pendingSelected);
        if (cancelButton != null) {
            // Имя текущего пользователя сопоставляется с denormalized requestedByUsername из DTO.
            String username = currentUsername(); // null-safe хелпер ниже (username, не display-строка)
            boolean own = pendingSelected
                    && sel.requestedByUsername() != null
                    && sel.requestedByUsername().equals(username);
            boolean admin = com.satdb.entity.UserRole.ADMIN == shell.currentRole();
            boolean visible = pendingSelected && (own || admin);
            cancelButton.setVisible(visible);
            cancelButton.setManaged(visible);
            cancelButton.setDisable(!pendingSelected);
        }
    }

    /**
     * Текущий логин из сессии. ShellService.currentDisplayName() возвращает составную
     * строку "login (ФИО, ROLE)" — для сравнения с requestedByUsername нужен чистый
     * username, поэтому читаем SecurityContext напрямую (тот же принцип, что в ShellService).
     */
    private static String currentUsername() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null
                && auth.getPrincipal() instanceof com.satdb.security.SessionUserDetails d) {
            return d.getUsername();
        }
        return null;
    }

    private static String diffHint(PendingChangeDto dto) {
        String proposed = dto.proposedValues();
        if (proposed == null || proposed.isBlank()) {
            return "—";
        }
        String compact = proposed.replaceAll("\\s+", " ");
        return compact.length() <= 60 ? compact : compact.substring(0, 57) + "...";
    }

    private static String roleSuffix(String role) {
        return role == null || role.isBlank() ? "" : " (" + abbreviateRole(role) + ")";
    }

    private static String abbreviateRole(String role) {
        return switch (role) {
            case "SHIFT_SUPERVISOR" -> "supervisor";
            case "ADMIN" -> "admin";
            case "OPERATOR" -> "operator";
            default -> role.toLowerCase();
        };
    }

    private static String fmt(java.time.LocalDateTime ts) {
        return ts == null ? "—" : ts.format(TS);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String shortReason(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return m.length() > 120 ? m.substring(0, 117) + "..." : m;
    }

    private void showError(String text) {
        errorLabel.setText(text);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setText("");
    }
}
