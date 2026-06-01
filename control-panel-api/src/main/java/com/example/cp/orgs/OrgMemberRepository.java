package com.example.cp.orgs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgMemberRepository extends JpaRepository<OrgMember, OrgMemberId> {

    List<OrgMember> findByOrgId(UUID orgId);

    List<OrgMember> findByUserId(UUID userId);

    Optional<OrgMember> findByOrgIdAndUserId(UUID orgId, UUID userId);

    long countByOrgIdAndRole(UUID orgId, OrgMember.Role role);

    void deleteByOrgIdAndUserId(UUID orgId, UUID userId);
}
