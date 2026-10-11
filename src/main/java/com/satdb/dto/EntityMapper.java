package com.satdb.dto;

import com.satdb.entity.Comment;
import com.satdb.entity.PendingChange;
import com.satdb.entity.RadioEmission;
import com.satdb.entity.SatelliteSystem;
import com.satdb.entity.User;
import com.satdb.entity.UserRole;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Ручной маппер JPA-сущности ⇄ DTO (антикоррупционный слой UI/REST ←→ домен).
 *
 * <p>Почему ручной, а не MapStruct: сущности содержат lazy-ассоциации и
 * денормализованные поля (username в заявках, счётчики), которые нужно
 * разворачивать guarded'ом от LazyInitializationException — генератор кода
 * здесь дал бы меньше контроля при нулевой экономии строк. Новые зависимости
 * в pom.xml не требуются.</p>
 *
 * <p>Правила:</p>
 * <ul>
 *   <li>toDto никогда не читает passwordHash;</li>
 *   <li>ассоциации раскрываются только если они уже загружены (Hibernate
 *       {@code Hibernate.isInitialized()} не используется — вместо него
 *       try/catch-free подход: контроллеры/сервисы загружают нужные связи
 *       fetch-джойнами, маппер берёт id/name из прокси безопасно через getId());</li>
 *   <li>toEntity создаёт новый объект либо обновляет переданный (update-метод),
 *       аудиторные поля (created_at/updated_at/is_deleted) не трогаем — ими
 *       владеют @CreatedDate/@LastModifiedDate и сервис soft-delete.</li>
 * </ul>
 */
@Component
public class EntityMapper {

    // ==================== RadioEmission ====================

    public RadioEmissionDto toDto(RadioEmission e) {
        if (e == null) {
            return null;
        }
        SatelliteSystem sys = e.getSatelliteSystem();
        return new RadioEmissionDto(
                e.getId(),
                sys != null ? sys.getId() : null,
                sys != null ? sys.getSystemName() : null,
                sys != null ? sys.getSystemCode() : null,
                e.getEmissionName(),
                e.getEmissionCode(),
                e.getFrequencyMhz(),
                e.getBandwidthKhz(),
                e.getModulationType(),
                e.getPowerDbm(),
                e.getPolarization(),
                e.getSignalType(),
                e.getPriority(),
                e.getClassification() != null ? e.getClassification().name() : null,
                e.getMeasurementDate(),
                e.getMeasurementLocation(),
                e.getNotes(),
                usernameOf(e.getCreatedBy()),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    /** Новый entity из формы создания (id/аудит проставят БД и Spring Data). */
    public RadioEmission toEntity(RadioEmissionDto.Request req) {
        if (req == null) {
            return null;
        }
        RadioEmission e = new RadioEmission();
        applyTo(e, req);
        return e;
    }

    /** Обновление существующей сущности полями формы (для PUT / request-change). */
    public void updateEntity(RadioEmission target, RadioEmissionDto.Request req) {
        if (target == null || req == null) {
            throw new IllegalArgumentException("target и request не могут быть null");
        }
        applyTo(target, req);
    }

    private void applyTo(RadioEmission e, RadioEmissionDto.Request req) {
        e.setEmissionName(req.emissionName());
        e.setEmissionCode(req.emissionCode());
        e.setFrequencyMhz(req.frequencyMhz());
        e.setBandwidthKhz(req.bandwidthKhz());
        e.setModulationType(req.modulationType());
        e.setPowerDbm(req.powerDbm());
        e.setPolarization(req.polarization());
        e.setSignalType(req.signalType());
        e.setPriority(req.priority() != null ? req.priority() : 0);
        if (req.classification() != null && !req.classification().isBlank()) {
            e.setClassification(RadioEmission.Classification.valueOf(req.classification()));
        }
        e.setMeasurementDate(req.measurementDate());
        e.setMeasurementLocation(req.measurementLocation());
        e.setNotes(req.notes());
        if (req.technicalParameters() != null) {
            e.setTechnicalParameters(req.technicalParameters());
        }
        // satelliteSystem связывает сервис (нужен репозиторий для поиска по id) —
        // маппер остаётся чистым преобразователем без доступа к данным.
    }

    public List<RadioEmissionDto> toEmissionDtoList(List<RadioEmission> list) {
        return list == null ? List.of() : list.stream().map(this::toDto).toList();
    }

    // ==================== SatelliteSystem ====================

    public SatelliteSystemDto toDto(SatelliteSystem s, long activeEmissionsCount) {
        if (s == null) {
            return null;
        }
        LocalDate createdOn = s.getCreatedAt() != null ? s.getCreatedAt().toLocalDate() : null;
        return new SatelliteSystemDto(
                s.getId(),
                s.getSystemName(),
                s.getSystemCode(),
                s.getDescription(),
                s.getOrbitalParameters(),
                s.getFrequencyBand(),
                s.getLaunchDate(),
                s.getStatus() != null ? s.getStatus().name() : null,
                s.getCountry(),
                s.getOperator(),
                usernameOf(s.getCreatedBy()),
                createdOn,
                activeEmissionsCount
        );
    }

    public SatelliteSystem toEntity(SatelliteSystemDto.Request req) {
        if (req == null) {
            return null;
        }
        SatelliteSystem s = new SatelliteSystem();
        applyTo(s, req);
        return s;
    }

    public void updateEntity(SatelliteSystem target, SatelliteSystemDto.Request req) {
        if (target == null || req == null) {
            throw new IllegalArgumentException("target и request не могут быть null");
        }
        applyTo(target, req);
    }

    private void applyTo(SatelliteSystem s, SatelliteSystemDto.Request req) {
        s.setSystemName(req.systemName());
        s.setSystemCode(req.systemCode());
        s.setDescription(req.description());
        s.setOrbitalParameters(req.orbitalParameters());
        s.setFrequencyBand(req.frequencyBand());
        s.setLaunchDate(req.launchDate());
        if (req.status() != null && !req.status().isBlank()) {
            s.setStatus(SatelliteSystem.Status.valueOf(req.status()));
        }
        s.setCountry(req.country());
        s.setOperator(req.operatorName());
    }

    // ==================== User ====================

    /** Безопасный outward-mapper: без passwordHash. */
    public UserDto toDto(User u) {
        if (u == null) {
            return null;
        }
        return new UserDto(
                u.getId(),
                u.getUsername(),
                u.getFullName(),
                u.getRole() != null ? u.getRole().name() : null,
                u.getEmail(),
                u.isActive(),
                u.getLastLogin(),
                u.getCreatedAt(),
                u.getUpdatedAt()
        );
    }

    /**
     * Создание пользователя из формы. Поле пароля возвращать наружу нельзя —
     * хэширование (BCrypt) выполняет UserService, сюда передаётся сырой пароль
     * только на время сборки сущности внутри транзакции сервиса.
     */
    public User toEntity(UserDto.CreateRequest req) {
        if (req == null) {
            return null;
        }
        User u = new User();
        u.setUsername(req.username());
        u.setFullName(req.fullName());
        u.setEmail(req.email());
        u.setRole(UserRole.valueOf(req.role()));
        u.setActive(true);
        return u;
    }

    /** Частичное обновление профиля: null-поля формы означают «не менять». */
    public void updateEntity(User target, UserDto.UpdateRequest req) {
        if (target == null || req == null) {
            throw new IllegalArgumentException("target и request не могут быть null");
        }
        if (req.fullName() != null && !req.fullName().isBlank()) {
            target.setFullName(req.fullName());
        }
        if (req.role() != null && !req.role().isBlank()) {
            target.setRole(UserRole.valueOf(req.role()));
        }
        if (req.email() != null) {
            target.setEmail(req.email());
        }
        if (req.active() != null) {
            target.setActive(req.active());
        }
    }

    // ==================== Comment ====================

    public CommentDto toDto(Comment c) {
        return toDto(c, true);
    }

    /** @param withChildren false — плоский вывод без рекурсии по тредам. */
    public CommentDto toDto(Comment c, boolean withChildren) {
        if (c == null) {
            return null;
        }
        List<CommentDto> children = withChildren && c.getChildComments() != null
                ? c.getChildComments().stream().map(ch -> toDto(ch, false)).toList()
                : List.of();
        User author = c.getCreatedBy();
        return new CommentDto(
                c.getId(),
                c.getTableName(),
                c.getRecordId(),
                c.getCommentType() != null ? c.getCommentType().name() : null,
                c.getTitle(),
                c.getContent(),
                c.getParentComment() != null ? c.getParentComment().getId() : null,
                usernameOf(author),
                author != null && author.getRole() != null ? author.getRole().name() : null,
                c.getCreatedAt(),
                children
        );
    }

    public Comment toEntity(CommentDto.Request req) {
        if (req == null) {
            return null;
        }
        Comment c = new Comment();
        c.setTableName(req.tableName());
        c.setRecordId(req.recordId());
        c.setTitle(req.title());
        c.setContent(req.content());
        c.setCommentType(req.commentType() != null && !req.commentType().isBlank()
                ? Comment.CommentType.valueOf(req.commentType())
                : Comment.CommentType.INFORMATIONAL);
        // createdBy и parentComment связывает сервис (нужны User/репозиторий).
        return c;
    }

    // ==================== PendingChange ====================

    public PendingChangeDto toDto(PendingChange p) {
        if (p == null) {
            return null;
        }
        User requester = p.getRequestedBy();
        User reviewer = p.getReviewedBy();
        return new PendingChangeDto(
                p.getId(),
                p.getTableName(),
                p.getRecordId(),
                p.getAction() != null ? p.getAction().name() : null,
                p.getProposedValues(),
                p.getCurrentValues(),
                p.getReason(),
                p.getStatus() != null ? p.getStatus().name() : null,
                usernameOf(requester),
                requester != null && requester.getRole() != null ? requester.getRole().name() : null,
                p.getRequestedAt(),
                usernameOf(reviewer),
                p.getReviewedAt(),
                p.getReviewComment(),
                p.getExecutedAt()
        );
    }

    public List<PendingChangeDto> toPendingChangeDtoList(List<PendingChange> list) {
        return list == null ? List.of() : list.stream().map(this::toDto).toList();
    }

    // ==================== helpers ====================

    /**
     * Имя пользователя без риска LazyInitializationException: getId() у
     * прокси безопасен, но getUsername() требует загрузки — используем его
     * только когда ассоциация реально загружена (проверяем по классу прокси).
     */
    private static String usernameOf(User u) {
        if (u == null) {
            return null;
        }
        // Hibernate-прокси имеют имя класса с маркером $HibernateProxy$
        String cn = u.getClass().getName();
        if (cn.contains("$HibernateProxy$")) {
            return null; // связь не загружена — UI покажет прочерк вместо падения
        }
        return u.getUsername();
    }
}
