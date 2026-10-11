---
name: spring-boot-architecture
description: Архитектура Spring Boot приложения с JavaFX
version: 1.0
tags: [spring-boot, java, architecture, javafx, layers]
triggers:
  - структура проекта
  - архитектура
  - слои приложения
  - spring boot
---

# Spring Boot Architecture Skill

## Назначение
Стандартная архитектура Spring Boot приложения с JavaFX UI, SQLite БД и 
чётким разделением слоёв.

## Когда применять
- Создание нового модуля/пакета
- Добавление нового функционала
- Рефакторинг существующего кода
- Ревью архитектуры

## Структура проекта

```
src/main/java/com/spaceindustry/satellitedb/
├── SatelliteDbApplication.java          # @SpringBootApplication
├── JavaFxApplication.java               # @SpringBootApplication + JavaFX
│
├── config/                              # Конфигурация
│   ├── DatabaseConfig.java
│   ├── SecurityConfig.java
│   ├── CacheConfig.java
│   └── SQLiteOptimizer.java
│
├── controller/                          # REST API (локальный)
│   ├── RadioEmissionController.java
│   ├── SatelliteSystemController.java
│   ├── UserController.java
│   └── ApprovalController.java
│
├── service/                             # Бизнес-логика
│   ├── RadioEmissionService.java
│   ├── SatelliteSystemService.java
│   ├── UserService.java
│   ├── ApprovalService.java
│   ├── PermissionService.java
│   └── NotificationService.java
│
├── repository/                          # Доступ к данным
│   ├── RadioEmissionRepository.java
│   ├── SatelliteSystemRepository.java
│   ├── UserRepository.java
│   ├── AuditLogRepository.java
│   └── PendingChangeRepository.java
│
├── model/                               # JPA сущности
│   ├── User.java
│   ├── UserRole.java
│   ├── RadioEmission.java
│   ├── SatelliteSystem.java
│   ├── Comment.java
│   ├── AuditLog.java
│   └── PendingChange.java
│
├── dto/                                 # DTO для API/UI
│   ├── request/
│   │   ├── CreateEmissionDto.java
│   │   ├── UpdateEmissionDto.java
│   │   └── ChangeRequestDto.java
│   └── response/
│       ├── RadioEmissionDto.java
│       ├── UserDto.java
│       └── PendingChangeDto.java
│
├── mapper/                              # MapStruct мапперы
│   ├── EmissionMapper.java
│   ├── UserMapper.java
│   └── PendingChangeMapper.java
│
├── security/                            # Безопасность
│   ├── SecurityContext.java
│   ├── JwtTokenProvider.java
│   └── AuthFilter.java
│
├── audit/                               # Аудит
│   ├── Auditable.java
│   └── AuditAspect.java
│
├── exception/                           # Исключения
│   ├── GlobalExceptionHandler.java
│   ├── AccessDeniedException.java
│   ├── NotFoundException.java
│   └── ValidationException.java
│
└── ui/                                  # JavaFX UI
    ├── MainView.java
    ├── controller/
    │   ├── LoginController.java
    │   ├── EmissionListController.java
    │   ├── EmissionDetailController.java
    │   └── PendingChangesController.java
    ├── view/
    │   ├── EmissionTableView.java
    │   └── FilterPanel.java
    └── fxml/
        ├── main.fxml
        ├── login.fxml
        └── emission-detail.fxml
```

## Слои и их ответственность

### 1. Controller (REST API)
**Задача**: Принять HTTP-запрос, валидация, вызов сервиса, возврат ответа.
**НЕ ДОЛЖЕН**: Содержать бизнес-логику, работать с БД напрямую.

```java
@RestController
@RequestMapping("/api/radio-emissions")
@Validated
public class RadioEmissionController {
    
    @Autowired
    private RadioEmissionService service;
    
    @Autowired
    private EmissionMapper mapper;
    
    @GetMapping("/{id}")
    public RadioEmissionDto getById(@PathVariable Long id) {
        return mapper.toDto(service.findById(id));
    }
    
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RadioEmissionDto create(@Valid @RequestBody CreateEmissionDto dto) {
        Long userId = SecurityContext.getCurrentUserId();
        return mapper.toDto(service.create(dto, userId));
    }
}
```

### 2. Service (Бизнес-логика)
**Задача**: Бизнес-правила, транзакции, проверки прав.
**НЕ ДОЛЖЕН**: Знать о HTTP, формировать DTO для UI.

```java
@Service
@Transactional
public class RadioEmissionService {
    
    @Autowired
    private RadioEmissionRepository repository;
    
    @Autowired
    private PermissionService permissionService;
    
    public RadioEmission findById(Long id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Запись не найдена: " + id));
    }
    
    @Auditable(tableName = "radio_emissions", action = "INSERT")
    public RadioEmission create(CreateEmissionDto dto, Long userId) {
        // Валидация бизнес-правил
        validateFrequency(dto.getFrequencyMhz());
        
        RadioEmission entity = mapper.toEntity(dto);
        entity.setCreatedBy(userId);
        return repository.save(entity);
    }
    
    private void validateFrequency(BigDecimal frequency) {
        if (frequency.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Частота должна быть положительной");
        }
    }
}
```

### 3. Repository (Доступ к данным)
**Задача**: SQL-запросы, Spring Data JPA.
**НЕ ДОЛЖЕН**: Содержать бизнес-логику.

```java
@Repository
public interface RadioEmissionRepository extends JpaRepository<RadioEmission, Long> {
    
    Optional<RadioEmission> findByIdAndIsDeletedFalse(Long id);
    
    @Query("SELECT r FROM RadioEmission r WHERE r.isDeleted = false " +
           "AND r.frequencyMhz BETWEEN :from AND :to")
    Page<RadioEmission> findByFrequencyRange(
        @Param("from") BigDecimal from,
        @Param("to") BigDecimal to,
        Pageable pageable
    );
    
    @Query(value = "SELECT r.* FROM radio_emissions r " +
                   "JOIN radio_emissions_fts fts ON r.id = fts.rowid " +
                   "WHERE radio_emissions_fts MATCH :term AND r.is_deleted = 0",
           nativeQuery = true)
    List<RadioEmission> fullTextSearch(@Param("term") String term);
}
```

### 4. UI Layer (JavaFX)
**Задача**: Отображение данных, взаимодействие с пользователем.
**Взаимодействует**: Только с Controller (через HTTP) или напрямую с Service.

```java
@Component
public class EmissionListController {
    
    @Autowired
    private RadioEmissionService service;
    
    @Autowired
    private EmissionMapper mapper;
    
    @FXML
    private TableView<RadioEmissionDto> tableView;
    
    @FXML
    public void initialize() {
        loadEmissions();
    }
    
    @FXML
    private void onSearch() {
        BigDecimal from = filterPanel.getFrequencyFrom();
        BigDecimal to = filterPanel.getFrequencyTo();
        
        Page<RadioEmission> page = service.findByFrequencyRange(
            from, to, PageRequest.of(0, 50));
        
        tableView.setItems(FXCollections.observableArrayList(
            page.getContent().stream()
                .map(mapper::toDto)
                .toList()
        ));
    }
}
```

## Интеграция Spring Boot + JavaFX

```java
@SpringBootApplication
public class JavaFxApplication extends Application {
    
    private ConfigurableApplicationContext applicationContext;
    
    @Override
    public void init() {
        // Запускаем Spring контекст
        this.applicationContext = new SpringApplicationBuilder()
            .sources(SatelliteDbApplication.class)
            .run(getParameters().getRaw().toArray(new String[0]));
    }
    
    @Override
    public void start(Stage primaryStage) throws Exception {
        // Загружаем FXML через Spring
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        loader.setControllerFactory(applicationContext::getBean);
        
        Parent root = loader.load();
        primaryStage.setScene(new Scene(root));
        primaryStage.setTitle("Satellite Radio Emission Database");
        primaryStage.show();
    }
    
    @Override
    public void stop() {
        applicationContext.close();
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
```

## Чек-лист
- [ ] Чёткое разделение слоёв (Controller / Service / Repository)?
- [ ] DTO отделены от JPA-сущностей?
- [ ] Используется MapStruct для маппинга?
- [ ] Транзакции на уровне Service?
- [ ] Валидация на уровне Controller (@Valid)?
- [ ] Глобальный обработчик исключений?
- [ ] JavaFX контроллеры — Spring-бины?

## Связанные skills
- [02-rbac-implementation](../02-rbac-implementation/SKILL.md)
- [08-testing-strategy](../08-testing-strategy/SKILL.md)