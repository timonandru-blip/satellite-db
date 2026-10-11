package com.satdb.service;

import com.satdb.entity.AuditLog;
import com.satdb.entity.User;
import com.satdb.entity.UserRole;
import com.satdb.exception.ConflictException;
import com.satdb.exception.NotFoundException;
import com.satdb.exception.ValidationException;
import com.satdb.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Управление пользователями (SKILL.md 02: MANAGE_USERS — только ADMIN).
 * Все mutating-методы проверяют права администратора и пишут в audit_log.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    public static final String TABLE_NAME = "users";

    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       PermissionService permissionService,
                       AuditService auditService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    // ---- Чтение ----

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Пользователь не найден, id=" + id));
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("Пользователь не найден: " + username));
    }

    /** Список пользователей доступен только админу (управление) либо supervisor (справка). */
    public Page<User> findAll(Long actingUserId, Pageable pageable) {
        permissionService.checkCanManageUsers(actingUserId);
        return userRepository.findAll(pageable);
    }

    public List<User> findActiveUsers(Long actingUserId) {
        permissionService.checkCanManageUsers(actingUserId);
        return userRepository.findByIsActiveTrueOrderByUsernameAsc();
    }

    public List<User> findByRole(UserRole role) {
        return userRepository.findByRoleAndIsActiveTrue(role);
    }

    // ---- Создание (только ADMIN) ----

    @Transactional
    public User createUser(Long adminId, String username, String rawPassword,
                           String fullName, UserRole role, String email) {
        User admin = requireAdmin(adminId);

        validateNewUser(username, rawPassword, fullName, role);
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("Логин уже занят: " + username);
        }

        User user = new User();
        user.setUsername(username.trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(fullName.trim());
        user.setRole(role);
        user.setEmail(email);
        user.setActive(true);

        User saved = userRepository.save(user);
        auditService.record(TABLE_NAME, saved.getId(), AuditLog.AuditAction.INSERT,
                admin, null, snapshot(saved), null, "Создание пользователя");
        return saved;
    }

    // ---- Обновление (только ADMIN) ----

    /** Изменяемые поля: fullName, email, role, active. Пароль — отдельно changePassword. */
    @Transactional
    public User updateUser(Long adminId, Long userId, Map<String, Object> changes, String reason) {
        User admin = requireAdmin(adminId);
        User user = findById(userId);
        Map<String, Object> before = snapshot(user);

        for (Map.Entry<String, Object> e : changes.entrySet()) {
            switch (e.getKey()) {
                case "fullName" -> {
                    String v = requireString(e.getValue(), "fullName");
                    if (v.isBlank()) {
                        throw new ValidationException("ФИО не может быть пустым");
                    }
                    user.setFullName(v);
                }
                case "email" -> user.setEmail(e.getValue() == null ? null : requireString(e.getValue(), "email"));
                case "role" -> {
                    UserRole role = parseRole(e.getValue());
                    guardLastAdmin(user, role);
                    user.setRole(role);
                }
                case "active" -> {
                    boolean active = Boolean.TRUE.equals(e.getValue())
                            || "true".equalsIgnoreCase(String.valueOf(e.getValue()));
                    guardLastAdmin(user, active ? user.getRole() : null, !active);
                    user.setActive(active);
                }
                default -> throw new ValidationException("Неизвестное поле пользователя: " + e.getKey());
            }
        }

        User saved = userRepository.save(user);
        auditService.record(TABLE_NAME, saved.getId(), AuditLog.AuditAction.UPDATE,
                admin, before, snapshot(saved),
                auditService.diffFields(before, snapshot(saved)).stream().toList(), reason);
        return saved;
    }

    /** Смена пароля (админ — любому пользователю). */
    @Transactional
    public void changePassword(Long adminId, Long userId, String newPassword) {
        User admin = requireAdmin(adminId);
        if (newPassword == null || newPassword.length() < 8) {
            throw new ValidationException("Пароль должен содержать минимум 8 символов");
        }
        User user = findById(userId);
        Map<String, Object> before = snapshot(user);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // сам хэш в аудит не пишем — фиксируем только факт
        auditService.record(TABLE_NAME, user.getId(), AuditLog.AuditAction.UPDATE,
                admin, before, snapshot(user), List.of("password_hash"), "Смена пароля");
    }

    /** Soft-deactivate пользователя вместо физического удаления (правило soft delete SKILL.md 01). */
    @Transactional
    public User deactivateUser(Long adminId, Long userId, String reason) {
        User admin = requireAdmin(adminId);
        User user = findById(userId);

        if (user.getId().equals(adminId)) {
            throw new ValidationException("Нельзя деактивировать собственную учётную запись");
        }
        guardLastAdmin(user, null, true);

        Map<String, Object> before = snapshot(user);
        user.setActive(false);
        User saved = userRepository.save(user);

        auditService.record(TABLE_NAME, saved.getId(), AuditLog.AuditAction.DELETE,
                admin, before, snapshot(saved), List.of("is_active"), reason);
        return saved;
    }

    /** Отметка входа (вызывается из аутентификационного слоя). */
    @Transactional
    public void touchLastLogin(String username) {
        userRepository.findByUsername(username).ifPresent(u -> {
            u.setLastLogin(LocalDateTime.now());
            userRepository.save(u);
        });
    }

    // ---- helpers ----

    private User requireAdmin(Long adminId) {
        User admin = permissionService.getActiveUser(adminId);
        permissionService.checkCanManageUsers(admin.getId());
        return admin;
    }

    private void validateNewUser(String username, String rawPassword, String fullName, UserRole role) {
        if (username == null || username.isBlank()) {
            throw new ValidationException("Логин обязателен");
        }
        if (!username.matches("[A-Za-z0-9._-]{3,50}")) {
            throw new ValidationException("Логин: 3-50 символов [A-Za-z0-9._-]");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new ValidationException("Пароль должен содержать минимум 8 символов");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new ValidationException("ФИО обязательно");
        }
        if (role == null) {
            throw new ValidationException("Роль обязательна");
        }
    }

    /** Защита: в системе всегда должен остаться хотя бы один активный админ. */
    private void guardLastAdmin(User user, UserRole newRole) {
        guardLastAdmin(user, newRole, false);
    }

    private void guardLastAdmin(User user, UserRole newRole, boolean deactivating) {
        boolean wasAdmin = user.getRole() == UserRole.ADMIN && user.isActive();
        boolean remainsAdmin = !deactivating
                && (newRole == null ? user.getRole() : newRole) == UserRole.ADMIN;
        if (wasAdmin && !remainsAdmin) {
            long activeAdmins = userRepository.findByRoleAndIsActiveTrue(UserRole.ADMIN).size();
            if (activeAdmins <= 1) {
                throw new ValidationException("Нельзя понизить/деактивировать последнего активного администратора");
            }
        }
    }

    private static String requireString(Object value, String field) {
        if (!(value instanceof String s)) {
            throw new ValidationException("Поле " + field + " должно быть строкой");
        }
        return s;
    }

    private static UserRole parseRole(Object value) {
        try {
            return UserRole.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Недопустимая роль: " + value
                    + ". Допустимо: ADMIN, SHIFT_SUPERVISOR, OPERATOR");
        }
    }

    /** Снимок для аудита (без password_hash). */
    public Map<String, Object> snapshot(User u) {
        return Map.of(
                "username", nvl(u.getUsername()),
                "fullName", nvl(u.getFullName()),
                "role", u.getRole() == null ? "" : u.getRole().name(),
                "email", nvl(u.getEmail()),
                "isActive", u.isActive());
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
