package com.satdb.ui.controller;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

import com.satdb.security.SessionUserDetails;
import com.satdb.service.UserService;
import com.satdb.ui.FxSpringViewManager;

/**
 * FXML-контроллер окна входа (login.fxml).
 *
 * Демонстрирует шаблон интеграции: бины передаются конструктором —
 * FXMLLoader создаёт контроллер через FxSpringControllerFactory, которая
 * разрешает AuthenticationManager/UserService/FxSpringViewManager из
 * Spring-контекста. Никакого @Autowired/field injection не нужно.
 *
 * Аутентификация локальная (SKILL.md 02): DaoAuthenticationProvider →
 * DatabaseUserDetailsService (users в SQLite, BCrypt) — без обращения к REST.
 */
public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;
    @FXML private Label errorLabel;

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final FxSpringViewManager viewManager;

    /** Конструктор инъецируется фабрикой Spring-бинах — единственный ctor. */
    public LoginController(AuthenticationManager authenticationManager,
                           UserService userService,
                           FxSpringViewManager viewManager) {
        this.authenticationManager = authenticationManager;
        this.userService = userService;
        this.viewManager = viewManager;
    }

    @FXML
    private void initialize() {
        // Enter в любом поле = кнопка "Войти".
        usernameField.setOnAction(e -> onLogin());
        passwordField.setOnAction(e -> onLogin());
        Platform.runLater(usernameField::requestFocus);
    }

    @FXML
    public void onLogin() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        if (username.isEmpty() || password.isEmpty()) {
            showError("Укажите имя пользователя и пароль.");
            return;
        }

        setBusy(true);
        hideError();

        // БД-запрос вне FX-потока: иначе UI подвиснет на блокировке HikariCP.
        Task<Authentication> task = new Task<>() {
            @Override protected Authentication call() {
                return authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(username, password));
            }
        };

        task.setOnSucceeded(e -> {
            Authentication auth = task.getValue();
            SecurityContextHolder.getContext().setAuthentication(auth);

            // last_login обновляем на служебной транзакции (не блокируем FX-поток).
            if (auth.getPrincipal() instanceof SessionUserDetails details) {
                try {
                    userService.touchLastLogin(details.getUsername());
                } catch (RuntimeException ex) {
                    // не критично для входа
                }
                System.setProperty("satdb.user", details.getUsername());
                System.setProperty("satdb.role", details.getRole().name());
            }
            setBusy(false);
            viewManager.showDashboard();
        });

        task.setOnFailed(e -> {
            setBusy(false);
            Throwable ex = task.getException();
            if (ex instanceof BadCredentialsException) {
                showError("Неверное имя пользователя или пароль.");
            } else if (ex instanceof DisabledException) {
                showError("Учётная запись отключена. Обратитесь к администратору.");
            } else if (ex instanceof LockedException) {
                showError(ex.getMessage() != null ? ex.getMessage()
                        : "Учётная запись временно заблокирована после серии неверных попыток.");
            } else if (ex instanceof AuthenticationException) {
                showError("Ошибка аутентификации: " + ex.getMessage());
            } else {
                showError("Внутренняя ошибка: " + ex.getMessage());
            }
            passwordField.clear();
            passwordField.requestFocus();
        });

        Thread t = new Thread(task, "fx-login");
        t.setDaemon(true);
        t.start();
    }

    private void setBusy(boolean busy) {
        loginButton.setDisable(busy);
        loginButton.setText(busy ? "Проверяю…" : "Войти");
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
