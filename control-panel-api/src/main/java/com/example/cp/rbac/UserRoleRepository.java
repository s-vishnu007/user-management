package com.example.cp.rbac;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    List<UserRole> findByUserId(UUID userId);

    @Query("select ur from UserRole ur where ur.userId = :userId and (ur.orgId is null or ur.orgId = :orgId)")
    List<UserRole> findByUserIdAndOrgIdOrGlobal(@Param("userId") UUID userId, @Param("orgId") UUID orgId);

    @Modifying
    @Query("delete from UserRole ur where ur.userId = :userId and ur.roleId = :roleId and ((:orgId is null and ur.orgId is null) or ur.orgId = :orgId)")
    int deleteAssignment(@Param("userId") UUID userId, @Param("roleId") UUID roleId, @Param("orgId") UUID orgId);

    @Query("select count(ur) from UserRole ur where ur.userId = :userId and ur.roleId = :roleId and ((:orgId is null and ur.orgId is null) or ur.orgId = :orgId)")
    long countAssignment(@Param("userId") UUID userId, @Param("roleId") UUID roleId, @Param("orgId") UUID orgId);
}
