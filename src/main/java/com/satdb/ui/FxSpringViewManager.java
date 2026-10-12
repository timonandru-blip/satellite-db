package com.satdb.ui;

import java.io.IOException;
import java.util.Optional;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/**
 * Единая точка загрузки FXML-представлений поверх Spring-контекста.
 *
 * Каждый FXMLLoader получает {@link FxSpringControllerFactory} — благодаря
 * этому конструкторы контроллеров инъецируются Spring-бинами (сервисы,
 * репозитории, SecurityUtils-хелперы) без ручного setController().
 *
 * Экраны хранят ссылку на менеджер, чтобы управлять переходами
 * (login → dashboard, диалоги согласований).
 */
public class FxSpringViewManager {

    private static final Logger log = LoggerFactory.getLogger(FxSpringViewManager.class);

    /** Известные экраны: имя → путь ресурса + тип контроллера. */
    public enum View {
        LOGIN("/fxml/login.fxml"),
        DASHBOARD("/fxml/dashboard.fxml"),
        MAIN("/fxml/main.fxml"),
        // Секции главного окна (результат разделения MainViewController, SRP):
        // emission-list.fxml — экран ТЗ «Список радиоизлучений с фильтрацией по частоте»
        // (EmissionListController + RadioEmissionViewModel); старый emissions.fxml оставлен как резервная секция.
        EMISSION_LIST("/fxml/emission-list.fxml"),
        EMISSIONS("/fxml/emissions.fxml"),
        SYSTEMS("/fxml/systems.fxml"),
        // Экран ТЗ «Согласования»: approval-view.fxml + ApprovalController (onApprove/onReject,
        // UiExceptionHandler). Старый approvals.fxml/ApprovalUiController оставлен как резервная секция.
        APPROVAL_VIEW("/fxml/approval-view.fxml"),
        APPROVALS("/fxml/approvals.fxml"),
        USERS("/fxml/users.fxml");

        private final String resource;

        View(String resource) {
            this.resource = resource;
        }

        public String resource() {
            return resource;
        }
    }

    private final ApplicationContext context;
    private Stage primaryStage;

    public FxSpringViewManager(ApplicationContext context) {
        this.context = context;
    }

    public void registerPrimaryStage(Stage stage) {
        this.primaryStage = stage;
        // Закрытие основного окна = выход из приложения.
        stage.setOnCloseRequest(e -> Platform.exit());
    }

    /** Показывает стартовое окно логина. */
    public void showLogin() {
        show(View.LOGIN, "Satellite Database — вход", 980, 640);
    }

    /** Переход на дашборд после успешной аутентификации (вызывает LoginController). */
    public void showDashboard() {
        show(View.DASHBOARD, "Satellite Database", 1280, 800);
    }

    /** Главное окно с адаптивным меню (MainViewController, SKILL.md 10). */
    public void showMain() {
        show(View.MAIN, "Satellite Database — главное окно", 1280, 800);
    }

    /**
     * Навигация внутри главного окна: загрузить FXML секции и вставить её корень
     * в центр BorderPane через ShellService.setCenter(). Меню/статус/панель
     * действий оболочки остаются на месте — меняется только содержимое центра.
     * Возвращает контроллер секции, чтобы вызывающий мог дёргать show()/передавать контекст.
     */
    public <T> java.util.Optional<T> openSection(View section) {
        try {
            Loaded<T> loaded = load(section);
            shellService().setCenter(loaded.root());
            return java.util.Optional.ofNullable(loaded.controller());
        } catch (Exception e) {
            log.error("Секция {} не открыта: {}", section, e.getMessage(), e);
            showError("Ошибка открытия секции '" + section + "': " + e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private com.satdb.ui.controller.ShellService shellService() {
        return context.getBean(com.satdb.ui.controller.ShellService.class);
    }

    /**
     * Универсальный переход: загрузить FXML с инъекцией бинов и подменить сцену.
     * Вызывается только с FX-нитки (все UI-обработчики и так на ней).
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> show(View view, String title, double width, double height) {
        try {
            Loaded<T> loaded = (Loaded<T>) load(view);
            Scene scene = new Scene(loaded.root(), width, height);
            primaryStage.setTitle(title);
            primaryStage.setScene(scene);
            primaryStage.centerOnScreen();
            return Optional.ofNullable(loaded.controller());
        } catch (Exception e) {
            log.error("Не удалось открыть экран {}: {}", view, e.getMessage(), e);
            showError("Ошибка открытия экрана '" + view + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Загрузка FXML + контроллер с Spring-инъекцией (без смены сцены — для диалогов). */
    public <T> Loaded<T> load(View view) throws IOException {
        FXMLLoader loader = new FXMLLoader(FxSpringViewManager.class.getResource(view.resource()));
        if (loader.getLocation() == null) {
            throw new IOException("FXML-ресурс не найден: " + view.resource());
        }
        // Ключевой шаг шаблона: Spring-бины в FXML-контроллеры.
        loader.setControllerFactory(new FxSpringControllerFactory(context));
        Parent root = loader.load();
        @SuppressWarnings("unchecked")
        T controller = (T) loader.getController();
        return new Loaded<>(root, controller);
    }

    /** Открыть модальный диалог поверх primary stage. */
    public <T> Optional<T> showDialog(View view, String title, double width, double height) {
        try {
            Loaded<T> loaded = load(view);
            Stage dialog = new Stage();
            dialog.initOwner(primaryStage);
            dialog.setTitle(title);
            dialog.setScene(new Scene(loaded.root(), width, height));
            dialog.showAndWait();
            return Optional.ofNullable(loaded.controller());
        } catch (Exception e) {
            log.error("Диалог {} не открыт: {}", view, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Минимальный fallback-диалог ошибки (Alert не требует FXML). */
    public void showError(String message) {
        Platform.runLater(() -> {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR, message);
            alert.setHeaderText(null);
            alert.setTitle("Ошибка");
            alert.showAndWait();
        });
    }

    public Stage getPrimaryStage() {
        return primaryStage;
    }

    public ApplicationContext getContext() {
        return context;
    }

    /** Пара корень сцены + контроллер. */
    public record Loaded<T>(Parent root, T controller) {}
}
