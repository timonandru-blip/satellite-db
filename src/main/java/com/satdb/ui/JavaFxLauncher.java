package com.satdb.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Application;
import javafx.stage.Stage;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.satdb.SatelliteDbApplication;

/**
 * Класс-лаунчер Spring Boot + JavaFX по шаблону 06-spring-boot-architecture/SKILL.md.
 *
 * Жизненный цикл ("Spring first, then FX"):
 *
 *   main() → Application.launch(JavaFxLauncher.class)   // блокирует эту нить
 *     └─ start(stage)  [FX Application Thread]
 *          1. SpringApplication.run(...) — поднимает контекст: JPA/Flyway/
 *             Security/Caffeine + встроенный Tomcat (REST доступен параллельно GUI).
 *          2. new FxSpringViewManager(context) — единая точка загрузки FXML
 *             с передачей Spring-бинов контроллерам (constructor injection
 *             через FXMLLoader.setControllerFactory + FxSpringControllerFactory).
 *          3. viewManager.showLogin() — стартовое окно входа.
 *          4. latch.countDown() — будит main().
 *     ... пользователь работает ...
 *   Platform.exit() → launch() возвращается → stop() закрывает контекст
 *   (без этого JVM висит на нет-демонических нитях Tomcat/HikariCP).
 *
 * Запуск: mvn javafx:run  (плагин добавляет --add-modules javafx.controls,javafx.fxml)
 * или из IDE: mainClass = com.satdb.ui.JavaFxLauncher.
 */
public class JavaFxLauncher extends Application {

    /** Блокирует main() до завершения старта FX+Spring в start(). */
    private final CountDownLatch startupLatch = new CountDownLatch(1);

    /** Контекст Spring — создаётся один раз на приложение. */
    private ConfigurableApplicationContext context;

    /** Менеджер окон/сцен: все переходы UI идут через него. */
    private FxSpringViewManager viewManager;

    public static void main(String[] args) {
        // 1. Стартуем JavaFX-платформу: launch() вызовет start() на FX-нитке
        //    и заблокирует main() до Platform.exit().
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            // 2. Запускаем Spring-контекст ДО создания окон — контроллерам нужны бины.
            context = SpringApplication.run(SatelliteDbApplication.class);

            // 3. Инициализируем менеджер представлений поверх контекста
            //    и регистрируем его singleton'ом — FXML-контроллеры смогут
            //    запросить его конструкторной инъекцией (см. LoginController).
            viewManager = new FxSpringViewManager(context);
            viewManager.registerPrimaryStage(primaryStage);
            context.getBeanFactory().registerSingleton("fxSpringViewManager", viewManager);

            // 4. Стартовое окно — логин.
            viewManager.showLogin();
        } catch (Exception e) {
            // Не даём main() зависнуть в ожидании latch: освобождаем и гасим контекст.
            if (context != null) {
                context.close();
            }
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        } finally {
            startupLatch.countDown();
        }
    }

    @Override
    public void stop() {
        // Вызывается при Platform.exit()/закрытии окна — корректный shutdown
        // Spring-контекста (Tomcat, пул соединений, Flyway lock).
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    /** Ожидание полной готовности приложения (используется тестами запуска). */
    public boolean awaitStartup(long seconds) throws InterruptedException {
        return startupLatch.await(seconds, TimeUnit.SECONDS);
    }

    /** Публичный доступ к менеджеру окон (например, из LoginController после успеха). */
    public FxSpringViewManager getViewManager() {
        return viewManager;
    }

    public ConfigurableApplicationContext getContext() {
        return context;
    }
}
