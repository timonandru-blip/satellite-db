package com.satdb.entity;

/**
 * Роли RBAC согласно skills/02-rbac-implementation/SKILL.md.
 * Хранится как строка (VARCHAR) — совместимо с CHECK(role IN (...)) в SQLite.
 */
public enum UserRole {
    ADMIN,
    SHIFT_SUPERVISOR,
    OPERATOR;

    public boolean canEditDirectly() {
        return this == ADMIN || this == SHIFT_SUPERVISOR;
    }

    public boolean canManageUsers() {
        return this == ADMIN;
    }

    public boolean canViewAuditLog() {
        return this == ADMIN || this == SHIFT_SUPERVISOR;
    }

    public boolean canApproveChanges() {
        return this == ADMIN || this == SHIFT_SUPERVISOR;
    }
}
