---
name: testing-strategy
description: Стратегия тестирования приложения (unit, integration, UI)
version: 1.0
tags: [testing, junit, mockito, integration, ui-tests]
triggers:
  - тесты
  - junit
  - проверка
  - покрытие
---

# Testing Strategy Skill

## Назначение
Обеспечить качество кода через многоуровневое тестирование: unit, integration, 
UI, security.

## Пирамида тестов

```
        /\
       /  \       UI Tests (10%)
      /----\      — JavaFX TestFX
     /      \     
    /--------\    Integration Tests (20%)
   /          \   — @SpringBootTest, @DataJpaTest
  /------------\  
 /              \  Unit Tests (70%)
/________________\ — JUnit 5, Mockito
```

## Unit-тесты (Service layer)

```java
@ExtendWith(MockitoExtension.class)
class RadioEmissionServiceTest {
    
    @Mock
    private RadioEmissionRepository repository;
    
    @Mock
    private PermissionService permissionService;
    
    @InjectMocks
    private RadioEmissionService service;
    
    @Test
    void shouldCreateEmissionSuccessfully() {
        // Given
        CreateEmissionDto dto = new CreateEmissionDto();
        dto.setFrequencyMhz(BigDecimal.valueOf(1575.42));
        when(repository.save(any())).thenAnswer(inv -> {
            RadioEmission e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        
        // When
        RadioEmission result = service.create(dto, 1L);
        
        // Then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getCreatedBy()).isEqualTo(1L);
        verify(repository).save(any());
    }
    
    @Test
    void shouldRejectNegativeFrequency() {
        // Given
        CreateEmissionDto dto = new CreateEmissionDto();
        dto.setFrequencyMhz(BigDecimal.valueOf(-100));
        
        // When/Then
        assertThatThrownBy(() -> service.create(dto, 1L))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Частота должна быть положительной");
    }
    
    @Test
    void shouldThrowNotFoundWhenEmissionMissing() {
        // Given
        when(repository.findByIdAndIsDeletedFalse(999L)).thenReturn(Optional.empty());
        
        // When/Then
        assertThatThrownBy(() -> service.findById(999L))
            .isInstanceOf(NotFoundException.class);
    }
}
```

## Integration-тесты (Repository)

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class RadioEmissionRepositoryTest {
    
    @Autowired
    private RadioEmissionRepository repository;
    
    @Autowired
    private TestEntityManager entityManager;
    
    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }
    
    @Test
    void shouldFindByFrequencyRange() {
        // Given
        createEmission(100.5);
        createEmission(200.5);
        createEmission(300.5);
        
        // When
        Page<RadioEmission> results = repository.findByFrequencyRange(
            BigDecimal.valueOf(150.0),
            BigDecimal.valueOf(250.0),
            PageRequest.of(0, 10)
        );
        
        // Then
        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getFrequencyMhz())
            .isEqualByComparingTo(BigDecimal.valueOf(200.5));
    }
    
    @Test
    void shouldNotReturnDeletedEmissions() {
        // Given
        RadioEmission deleted = createEmission(100.5);
        deleted.setIsDeleted(true);
        entityManager.persist(deleted);
        
        // When
        List<RadioEmission> results = repository.findByFrequencyRange(
            BigDecimal.valueOf(50.0),
            BigDecimal.valueOf(150.0),
            PageRequest.of(0, 10)
        ).getContent();
        
        // Then
        assertThat(results).isEmpty();
    }
    
    private RadioEmission createEmission(double frequency) {
        SatelliteSystem system = new SatelliteSystem();
        system.setSystemName("Test");
        system.setSystemCode("TEST");
        entityManager.persist(system);
        
        RadioEmission emission = new RadioEmission();
        emission.setSatelliteSystem(system);
        emission.setEmissionName("Test Emission");
        emission.setEmissionCode("TE1");
        emission.setFrequencyMhz(BigDecimal.valueOf(frequency));
        return entityManager.persist(emission);
    }
}
```

## Security-тесты

```java
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void operatorShouldNotEditDirectly() throws Exception {
        mockMvc.perform(put("/api/radio-emissions/1")
                .with(user("operator").roles("OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{...}"))
            .andExpect(status().isForbidden());
    }
    
    @Test
    void operatorShouldCreatePendingChange() throws Exception {
        mockMvc.perform(post("/api/radio-emissions/1/request-change")
                .with(user("operator").roles("OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{...}"))
            .andExpect(status().isOk());
    }
    
    @Test
    void supervisorShouldApproveChanges() throws Exception {
        mockMvc.perform(post("/api/approval/1/review")
                .with(user("supervisor").roles("SHIFT_SUPERVISOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\": true}"))
            .andExpect(status().isOk());
    }
    
    @Test
    void operatorShouldNotManageUsers() throws Exception {
        mockMvc.perform(post("/api/users")
                .with(user("operator").roles("OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{...}"))
            .andExpect(status().isForbidden());
    }
}
```

## UI-тесты (TestFX)

```java
@ExtendWith(ApplicationExtension.class)
class LoginViewTest {
    
    @Test
    void shouldLoginSuccessfully(FXMLLoader loader) {
        // Given
        TextField usernameField = lookup("#username").query();
        PasswordField passwordField = lookup("#password").query();
        Button loginButton = lookup("#loginButton").query();
        
        // When
        clickOn(usernameField).write("admin");
        clickOn(passwordField).write("password");
        clickOn(loginButton);
        
        // Then
        verify(service).login("admin", "password");
    }
}
```

## Покрытие кода (JaCoCo)

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
    </executions>
</plugin>
```

## Целевое покрытие
- Service layer: не менее 80%
- Repository: не менее 70%
- Controller: не менее 60%
- Общее: не менее 70%

## Чек-лист
- [ ] Написаны unit-тесты для всех сервисных методов?
- [ ] Есть integration-тесты для критичных запросов?
- [ ] Покрыты security-сценарии (роли, права)?
- [ ] Покрытие кода не менее 70%?
- [ ] Тесты запускаются в CI?
- [ ] Есть тесты для миграций?

## Связанные skills
- [06-spring-boot-architecture](../06-spring-boot-architecture/SKILL.md)
- [02-rbac-implementation](../02-rbac-implementation/SKILL.md)