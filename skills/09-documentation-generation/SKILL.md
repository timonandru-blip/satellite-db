---
name: documentation-generation
description: Автогенерация документации API и схемы БД
version: 1.0
tags: [documentation, swagger, openapi, schema]
triggers:
  - документация
  - swagger
  - openapi
  - описание API
---

# Documentation Generation Skill

## Назначение
Автоматическая генерация документации API (OpenAPI/Swagger) и схемы БД.

## Когда применять
- Настройка нового проекта
- Добавление новых endpoint'ов
- Генерация документации для пользователей
- Ревью API

## OpenAPI/Swagger для REST API

### Зависимость

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.2.0</version>
</dependency>
```

### Конфигурация

```java
@Configuration
public class OpenApiConfig {
    
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Satellite Radio Emission Database API")
                .version("1.0.0")
                .description("API для управления базой данных спутниковых систем и радиоизлучений")
                .contact(new Contact()
                    .name("Space Industry Team")
                    .email("dev@space-industry.local")))
            .addSecurityItem(new SecurityRequirement().addList("Bearer"))
            .components(new Components()
                .addSecuritySchemes("Bearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
```

### Аннотации для Controllers

```java
@RestController
@RequestMapping("/api/radio-emissions")
@Tag(name = "Radio Emissions", description = "Управление радиоизлучениями")
public class RadioEmissionController {
    
    @Operation(
        summary = "Поиск радиоизлучений",
        description = "Поиск и фильтрация по частоте, спутниковой системе, дате"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Успешный поиск"),
        @ApiResponse(responseCode = "400", description = "Неверные параметры"),
        @ApiResponse(responseCode = "403", description = "Доступ запрещён")
    })
    @GetMapping("/search")
    public Page<RadioEmissionDto> search(
        @Parameter(description = "Частота от (MHz)") 
        @RequestParam(required = false) BigDecimal frequencyFrom,
        
        @Parameter(description = "Частота до (MHz)")
        @RequestParam(required = false) BigDecimal frequencyTo,
        
        @Parameter(description = "ID спутниковой системы")
        @RequestParam(required = false) Long satelliteSystemId,
        
        Pageable pageable
    ) { ... }
    
    @Operation(summary = "Создание радиоизлучения")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RadioEmissionDto create(
        @RequestBody @Valid CreateEmissionDto dto
    ) { ... }
}
```

### Аннотации для DTO

```java
@Schema(description = "DTO для создания радиоизлучения")
public class CreateEmissionDto {
    
    @Schema(description = "ID спутниковой системы", example = "1", required = true)
    @NotNull
    private Long satelliteSystemId;
    
    @Schema(description = "Название излучения", example = "GPS L1 C/A")
    @NotBlank
    @Size(max = 200)
    private String emissionName;
    
    @Schema(description = "Частота в MHz", example = "1575.42")
    @NotNull
    @DecimalMin("0.0")
    private BigDecimal frequencyMhz;
    
    @Schema(description = "Тип модуляции", example = "CDMA")
    private String modulationType;
}
```

## Документация схемы БД

### Генератор схемы

```java
@Component
public class SchemaDocumentationGenerator {
    
    @Autowired
    private DataSource dataSource;
    
    public void generate() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            
            StringBuilder doc = new StringBuilder();
            doc.append("# Database Schema Documentation\n\n");
            doc.append("Generated: ").append(LocalDateTime.now()).append("\n\n");
            
            ResultSet tables = meta.getTables(null, null, "%", new String[]{"TABLE"});
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                doc.append("## ").append(tableName).append("\n\n");
                
                // Columns
                ResultSet columns = meta.getColumns(null, null, tableName, "%");
                doc.append("| Column | Type | Nullable | Default | Description |\n");
                doc.append("|--------|------|----------|---------|-------------|\n");
                
                while (columns.next()) {
                    doc.append(String.format("| %s | %s | %s | %s | %s |\n",
                        columns.getString("COLUMN_NAME"),
                        columns.getString("TYPE_NAME"),
                        columns.getString("IS_NULLABLE"),
                        columns.getString("COLUMN_DEF"),
                        columns.getString("REMARKS")));
                }
                
                // Indexes
                ResultSet indexes = meta.getIndexInfo(null, null, tableName, false, false);
                doc.append("\n### Indexes\n\n");
                doc.append("| Index Name | Column | Unique |\n");
                doc.append("|------------|--------|--------|\n");
                
                while (indexes.next()) {
                    doc.append(String.format("| %s | %s | %s |\n",
                        indexes.getString("INDEX_NAME"),
                        indexes.getString("COLUMN_NAME"),
                        !indexes.getBoolean("NON_UNIQUE")));
                }
                
                doc.append("\n---\n\n");
            }
            
            Files.writeString(Path.of("docs/DATABASE_SCHEMA.md"), doc.toString());
        }
    }
}
```

### Запуск генерации

```java
@Component
public class DocumentationRunner implements ApplicationRunner {
    
    @Autowired
    private SchemaDocumentationGenerator generator;
    
    @Value("${docs.generate-on-start:false}")
    private boolean generateOnStart;
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (generateOnStart) {
            generator.generate();
        }
    }
}
```

## Доступ к документации

- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON**: `http://localhost:8080/v3/api-docs`
- **Схема БД**: `docs/DATABASE_SCHEMA.md`

## Чек-лист
- [ ] Все controllers описаны @Tag и @Operation?
- [ ] Все DTO имеют @Schema?
- [ ] Все параметры имеют @Parameter?
- [ ] Все ответы описаны в @ApiResponses?
- [ ] Swagger UI доступен локально?
- [ ] Генератор схемы БД работает?
- [ ] Документация обновляется при изменениях?

## Связанные skills
- [06-spring-boot-architecture](../06-spring-boot-architecture/SKILL.md)