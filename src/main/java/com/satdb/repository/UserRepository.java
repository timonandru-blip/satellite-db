package com.satdb.repository;

import com.satdb.entity.User;
import com.satdb.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий пользователей (SKILL.md 01: таблица users).
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    List<User> findByRoleAndIsActiveTrue(UserRole role);

    List<User> findByIsActiveTrueOrderByUsernameAsc();
}
