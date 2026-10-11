package com.satdb.service;

import com.satdb.entity.User;
import com.satdb.entity.UserRole;
import com.satdb.exception.AccessDeniedException;
import com.satdb.exception.NotFoundException;
import com.satdb.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Проверка прав пользователя по матрице RBAC (SKILL.md 02-rbac).
 * Единая точка принятия решений: сервисы спрашивают разрешения здесь,
 * а не сравнивают роли напрямую.
 */
@Service
@Transactional(readOnly = true)
public class PermissionService {

    /** Гранулярные права из SKILL.md 02 (enum Permission). */
    public enum Permission {
        VIEW_RECORDS,
        CREATE_RECORDS,
        EDIT_RECORDS_DIRECT,
        DELETE_RECORDS_DIRECT,
        RESTORE_RECORDS,
        REQUEST_CHANGES,
        APPROVE_CHANGES,
        MANAGE_USERS,
        VIEW_AUDIT_LOG,
        VIEW_OWN_PENDING
    }

    /** Матрица прав: роль -> набор разрешений (правила SKILL.md 02). */
    private static final Map<UserRole, List<Permission>> MATRIX = buildMatrix();

    private final UserRepository userRepository;

    public PermissionService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // ---- Проверки по id / объекту пользователя ----

    public boolean hasPermission(User user, Permission permission) {
        if (user == null || user.getRole() == null || !user.isActive()) {
            return false;
        }
        return MATRIX.getOrDefault(user.getRole(), List.of()).contains(permission);
    }

    public boolean hasPermission(Long userId, Permission permission) {
        return hasPermission(getUser(userId), permission);
    }

    /** Бросает AccessDeniedException без сообщения (дефолт по действию). */
    public void require(User user, Permission permission) {
        require(user, permission, defaultDenyMessage(permission));
    }

    public void require(User user, Permission permission, String message) {
        if (!hasPermission(user, permission)) {
            throw new AccessDeniedException(message);
        }
    }

    public void requireById(Long userId, Permission permission) {
        require(getUser(userId), permission);
    }

    // ---- Семантические проверки (SKILL.md 02: "Сервисная проверка") ----

    /** Оператор не может редактировать/удалять напрямую — только через согласование. */
    public void checkCanEditDirectly(Long userId) {
        User user = getUser(userId);
        if (!user.getRole().canEditDirectly()) {
            throw new AccessDeniedException(
                    "Роль " + user.getRole() + " не может редактировать напрямую. "
                            + "Создайте запрос на согласование (pending change).");
        }
    }

    public void checkCanCreate(Long userId) {
        requireById(userId, Permission.CREATE_RECORDS);
    }

    public void checkCanApprove(Long userId) {
        User user = getUser(userId);
        if (!user.getRole().canApproveChanges()) {
            throw new AccessDeniedException("Согласовывать изменения могут только Admin и Начальник смены.");
        }
    }

    public void checkCanManageUsers(Long userId) {
        User user = getUser(userId);
        if (!user.getRole().canManageUsers()) {
            throw new AccessDeniedException("Управление пользователями доступно только администратору.");
        }
    }

    public void checkCanViewAuditLog(Long userId) {
        User user = getUser(userId);
        if (!user.getRole().canViewAuditLog()) {
            throw new AccessDeniedException("Просмотр журнала аудита доступен только Admin и Начальнику смены.");
        }
    }

    /** Пользователь активен? Иначе любое действие запрещено. */
    public User getActiveUser(Long userId) {
        User user = getUser(userId);
        if (!user.isActive()) {
            throw new AccessDeniedException("Пользователь деактивирован: " + user.getUsername());
        }
        return user;
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Пользователь не найден, id=" + userId));
    }

    private static Map<UserRole, List<Permission>> buildMatrix() {
        Map<UserRole, List<Permission>> m = new EnumMap<>(UserRole.class);
        List<Permission> all = List.of(Permission.values());
        m.put(UserRole.ADMIN, all);
        m.put(UserRole.SHIFT_SUPERVISOR, List.of(
                Permission.VIEW_RECORDS,
                Permission.CREATE_RECORDS,
                Permission.EDIT_RECORDS_DIRECT,
                Permission.DELETE_RECORDS_DIRECT,
                Permission.RESTORE_RECORDS,
                Permission.REQUEST_CHANGES,
                Permission.APPROVE_CHANGES,
                Permission.VIEW_AUDIT_LOG,
                Permission.VIEW_OWN_PENDING));
        m.put(UserRole.OPERATOR, List.of(
                Permission.VIEW_RECORDS,
                Permission.CREATE_RECORDS,
                Permission.REQUEST_CHANGES,
                Permission.VIEW_OWN_PENDING));
        return m;
    }

    private static String defaultDenyMessage(Permission permission) {
        return switch (permission) {
            case EDIT_RECORDS_DIRECT, DELETE_RECORDS_DIRECT ->
                    "Прямое изменение запрещено для вашей роли — используйте запрос на согласование.";
            case MANAGE_USERS -> "Управление пользователями доступно только администратору.";
            case APPROVE_CHANGES -> "Согласовывать изменения могут только Admin и Начальник смены.";
            case VIEW_AUDIT_LOG -> "Просмотр журнала аудита недоступен для вашей роли.";
            case RESTORE_RECORDS -> "Восстановление удалённых записей доступно только Admin/Supervisor.";
            default -> "Недостаточно прав: " + permission;
        };
    }

    /** Для тестов/админ-панели: какие права есть у роли. */
    public static List<Permission> permissionsOf(UserRole role) {
        return MATRIX.getOrDefault(role, List.of());
    }
}
