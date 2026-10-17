package com.itms.repository;

import com.itms.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户数据访问层
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** 根据用户名查找用户 */
    Optional<User> findByUsername(String username);

    /** 判断用户名是否存在 */
    boolean existsByUsername(String username);

    /** 判断邮箱是否存在 */
    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);
    long countByRoleAndEnabledTrue(String role);

    /** Update only the timestamp; stale login state must not overwrite account edits. */
    @Transactional @Modifying
    @Query("update User u set u.lastLoginTime = :time where u.id = :id and u.enabled = true and u.authVersion = :version")
    int recordLogin(@Param("id") Long id, @Param("version") long version, @Param("time") LocalDateTime time);
}
