---
name: javafx-ui
description: Десктопный интерфейс на JavaFX с учётом ролей пользователей
version: 1.0
tags: [javafx, ui, desktop, fxml, user-interface]
triggers:
  - интерфейс
  - UI
  - окно
  - форма
  - javafx
---

# JavaFX UI Skill

## Назначение
Создание десктопного интерфейса на JavaFX с адаптацией под роли пользователей 
и интеграцией со Spring Boot backend.

## Когда применять
- Создание нового экрана/окна
- Добавление UI-элемента
- Реализация формы ввода
- Настройка таблиц и фильтров
- Адаптация интерфейса под роль (Админ, Начальник смены, Оператор)

## Структура UI

```
src/main/java/.../ui/
├── MainView.java                    # Главное окно (каркас)
├── controller/
│   ├── LoginController.java         # Экран входа в систему
│   ├── DashboardController.java     # Главный дашборд
│   ├── EmissionListController.java  # Список радиоизлучений
│   ├── EmissionDetailController.java# Детали излучения
│   ├── PendingChangesController.java# Запросы на согласование
│   └── UserController.java          # Управление пользователями (только Admin)
├── component/
│   ├── FilterPanel.java             # Переиспользуемая панель фильтров
│   └── AuditLogView.java            # Компонент просмотра истории
└── dialog/
    ├── ChangeRequestDialog.java     # Диалог запроса изменения (для Оператора)
    └── ApprovalDialog.java          # Диалог согласования (для Начальника)
```

## Главное окно (Адаптация меню под роли)

```java
@Component
public class MainViewController {
    
    @Autowired
    private SecurityContext securityContext;
    
    @FXML
    private MenuBar menuBar;
    
    @FXML
    public void initialize() {
        buildMenuByRole();
    }
    
    private void buildMenuByRole() {
        UserRole role = securityContext.getCurrentUserRole();
        
        // Общие пункты меню для всех
        Menu dataMenu = new Menu("Данные");
        dataMenu.getItems().addAll(
            createMenuItem("Спутниковые системы", this::showSatelliteSystems),
            createMenuItem("Радиоизлучения", this::showEmissions),
            createMenuItem("Поиск", this::showSearch)
        );
        menuBar.getMenus().add(dataMenu);
        
        // Специфичные для ролей пункты
        if (role == UserRole.ADMIN || role == UserRole.SHIFT_SUPERVISOR) {
            Menu approvalMenu = new Menu("Согласования");
            approvalMenu.getItems().add(
                createMenuItem("Ожидают рассмотрения", this::showPendingChanges)
            );
            menuBar.getMenus().add(approvalMenu);
        }
        
        if (role == UserRole.OPERATOR) {
            Menu myRequestsMenu = new Menu("Мои запросы");
            myRequestsMenu.getItems().add(
                createMenuItem("История моих запросов", this::showMyRequests)
            );
            menuBar.getMenus().add(myRequestsMenu);
        }
        
        if (role == UserRole.ADMIN) {
            Menu adminMenu = new Menu("Администрирование");
            adminMenu.getItems().addAll(
                createMenuItem("Пользователи", this::showUsers),
                createMenuItem("Журнал аудита", this::showAuditLog)
            );
            menuBar.getMenus().add(adminMenu);
        }
    }
}
```

## Таблица радиоизлучений (Адаптация кнопок)

```java
@Component
public class EmissionListController {
    
    @Autowired
    private SecurityContext securityContext;
    
    @FXML private TableView<RadioEmissionDto> tableView;
    @FXML private Button editButton;
    @FXML private Button deleteButton;
    @FXML private Button requestChangeButton;
    
    @FXML
    public void initialize() {
        configureButtonsByRole();
        loadEmissions();
    }
    
    private void configureButtonsByRole() {
        UserRole role = securityContext.getCurrentUserRole();
        
        if (role.canEditDirectly()) {
            // Admin и Начальник смены
            editButton.setText("Редактировать");
            editButton.setOnAction(e -> openEditDialog());
            editButton.setVisible(true);
            
            deleteButton.setVisible(true);
            deleteButton.setOnAction(e -> deleteSelected());
            
            requestChangeButton.setVisible(false);
            requestChangeButton.setManaged(false);
        } else {
            // Оператор
            editButton.setVisible(false);
            editButton.setManaged(false);
            deleteButton.setVisible(false);
            deleteButton.setManaged(false);
            
            requestChangeButton.setText("Запросить изменение");
            requestChangeButton.setVisible(true);
            requestChangeButton.setOnAction(e -> openChangeRequestDialog());
        }
    }
    
    private void openChangeRequestDialog() {
        RadioEmissionDto selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        new ChangeRequestDialog(selected).showAndWait();
    }
}
```

## Диалог запроса изменения (Для оператора)

```java
public class ChangeRequestDialog extends Dialog<ChangeRequestDto> {
    
    public ChangeRequestDialog(RadioEmissionDto current) {
        setTitle("Запрос на изменение");
        setHeaderText("Укажите новые значения и обоснование");
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        TextField nameField = new TextField(current.getEmissionName());
        TextField frequencyField = new TextField(current.getFrequencyMhz().toString());
        
        // Обязательное обоснование
        TextArea reasonArea = new TextArea();
        reasonArea.setPromptText("Обоснуйте необходимость изменения...");
        reasonArea.setPrefRowCount(4);
        
        int row = 0;
        grid.add(new Label("Название:"), 0, row); grid.add(nameField, 1, row++);
        grid.add(new Label("Частота (MHz):"), 0, row); grid.add(frequencyField, 1, row++);
        grid.add(new Label("Обоснование:*"), 0, row); grid.add(reasonArea, 1, row++);
        
        getDialogPane().setContent(grid);
        
        ButtonType submitType = new ButtonType("Отправить на согласование", ButtonData.APPLY);
        getDialogPane().getButtonTypes().addAll(submitType, ButtonType.CANCEL);
        
        // Валидация и маппинг результата
        setResultConverter(button -> {
            if (button == submitType) {
                if (reasonArea.getText().isBlank()) {
                    showError("Обоснование обязательно для запроса!");
                    return null;
                }
                ChangeRequestDto dto = new ChangeRequestDto();
                dto.setEmissionName(nameField.getText());
                dto.setFrequencyMhz(new BigDecimal(frequencyField.getText()));
                dto.setReason(reasonArea.getText());
                return dto;
            }
            return null;
        });
    }
}
```

## Панель фильтров

```java
@Component
public class FilterPanelController {
    
    @FXML private TextField frequencyFrom;
    @FXML private TextField frequencyTo;
    @FXML private ComboBox<SatelliteSystemDto> satelliteSystemCombo;
    @FXML private DatePicker dateFrom;
    @FXML private DatePicker dateTo;
    @FXML private TextField searchText;  // Для FTS (полнотекстового поиска)
    
    public FilterCriteria getCriteria() {
        FilterCriteria c = new FilterCriteria();
        c.setFrequencyFrom(parseBigDecimal(frequencyFrom.getText()));
        c.setFrequencyTo(parseBigDecimal(frequencyTo.getText()));
        c.setSatelliteSystemId(getSelectedSystemId());
        c.setDateFrom(dateFrom.getValue());
        c.setDateTo(dateTo.getValue());
        c.setSearchText(searchText.getText());
        return c;
    }
    
    @FXML
    private void onClear() {
        frequencyFrom.clear();
        frequencyTo.clear();
        satelliteSystemCombo.getSelectionModel().clearSelection();
        dateFrom.setValue(null);
        dateTo.setValue(null);
        searchText.clear();
    }
}
```

## Стилизация (CSS)

```css
/* styles.css */
.root {
    -fx-font-family: "Segoe UI", sans-serif;
    -fx-font-size: 13px;
}

.table-view .column-header {
    -fx-background-color: #2c3e50;
    -fx-text-fill: white;
}

.table-view .table-row-cell:odd {
    -fx-background-color: #f9f9f9;
}

.button-primary {
    -fx-background-color: #3498db;
    -fx-text-fill: white;
}

.button-danger {
    -fx-background-color: #e74c3c;
    -fx-text-fill: white;
}

/* Статусы согласования */
.status-pending { -fx-text-fill: #f39c12; -fx-font-weight: bold; }
.status-approved { -fx-text-fill: #27ae60; }
.status-rejected { -fx-text-fill: #c0392b; }
```

## Чек-лист
- [ ] UI адаптируется под роль пользователя (скрыты недоступные кнопки)?
- [ ] Оператор видит только кнопку "Запросить изменение"?
- [ ] Есть валидация на клиенте (до отправки запроса)?
- [ ] Таблицы используют pagination (не грузят 1 млн записей сразу)?
- [ ] Есть индикаторы загрузки (ProgressIndicator) при долгих запросах?
- [ ] Обработаны ошибки (показ Alert при падении API)?
- [ ] Интерфейс полностью на русском языке?
- [ ] Поддерживаются горячие клавиши (Ctrl+F для поиска, Esc для закрытия)?

## Связанные skills
- [02-rbac-implementation](../02-rbac-implementation/SKILL.md)
- [04-approval-workflow](../04-approval-workflow/SKILL.md)
- [06-spring-boot-architecture](../06-spring-boot-architecture/SKILL.md)