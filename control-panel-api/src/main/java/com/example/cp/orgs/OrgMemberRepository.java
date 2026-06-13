package com.example.cp.orgs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Deletes a member only when it is NOT the org's last OWNER, atomically: the delete is gated by a
     * correlated subquery so the last-OWNER count is evaluated against the same statement that performs
     * the delete (no count-then-delete window). Returns the number of rows removed (0 = either the
     * member did not exist, or it was the last remaining OWNER and was therefore left in place). This
     * closes the count-then-delete race (P3) where two concurrent removals could each see {@code >1}
     * owners and both delete, zeroing out the owners. The OWNER role is passed as a bound parameter to
     * keep the JPQL free of fully-qualified enum literals.
     */
    @Modifying
    @Query("""
            delete from OrgMember m
            where m.orgId = :orgId and m.userId = :userId
              and not (
                m.role = :ownerRole
                and (select count(o) from OrgMember o where o.orgId = :orgId and o.role = :ownerRole) <= 1
              )
            """)
    int deleteMemberUnlessLastOwner(@Param("orgId") UUID orgId,
                                    @Param("userId") UUID userId,
                                    @Param("ownerRole") OrgMember.Role ownerRole);
}
