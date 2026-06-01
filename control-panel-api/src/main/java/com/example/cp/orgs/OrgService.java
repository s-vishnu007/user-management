package com.example.cp.orgs;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class OrgService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");

    private final OrganizationRepository orgRepository;
    private final OrgMemberRepository memberRepository;
    private final UserRepository userRepository;

    public OrgService(OrganizationRepository orgRepository, OrgMemberRepository memberRepository, UserRepository userRepository) {
        this.orgRepository = orgRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Organization createOrg(String slug, String name, UUID ownerUserId) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw ApiException.badRequest("Invalid slug; use lowercase letters, digits and dashes (3-64 chars)");
        }
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("Name is required");
        }
        if (orgRepository.existsBySlug(slug)) {
            throw ApiException.conflict("Slug already taken");
        }
        OffsetDateTime now = OffsetDateTime.now();
        Organization o = Organization.builder()
                .id(Ids.newId())
                .slug(slug)
                .name(name)
                .status(Organization.Status.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        Organization saved = orgRepository.save(o);
        if (ownerUserId != null) {
            memberRepository.save(OrgMember.builder()
                    .orgId(saved.getId())
                    .userId(ownerUserId)
                    .role(OrgMember.Role.OWNER)
                    .addedAt(now)
                    .build());
        }
        AuditContext.set("org.created");
        AuditContext.setTarget("organization", saved.getId().toString());
        return saved;
    }

    @Transactional(readOnly = true)
    public Organization get(UUID orgId) {
        return orgRepository.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization not found"));
    }

    @Transactional(readOnly = true)
    public List<Organization> listOrgsForUser(UUID userId) {
        List<OrgMember> memberships = memberRepository.findByUserId(userId);
        return memberships.stream()
                .map(m -> orgRepository.findById(m.getOrgId()).orElse(null))
                .filter(o -> o != null)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrgMember> listMembers(UUID orgId) {
        return memberRepository.findByOrgId(orgId);
    }

    @Transactional(readOnly = true)
    public boolean isMember(UUID orgId, UUID userId) {
        return memberRepository.findByOrgIdAndUserId(orgId, userId).isPresent();
    }

    @Transactional(readOnly = true)
    public Optional<OrgMember.Role> roleOf(UUID orgId, UUID userId) {
        return memberRepository.findByOrgIdAndUserId(orgId, userId).map(OrgMember::getRole);
    }

    @Transactional
    public OrgMember addMember(UUID orgId, String email, OrgMember.Role role) {
        if (role == null) {
            throw ApiException.badRequest("Role is required");
        }
        Organization org = get(orgId);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> ApiException.notFound("User with that email not found"));
        if (memberRepository.findByOrgIdAndUserId(orgId, user.getId()).isPresent()) {
            throw ApiException.conflict("User is already a member");
        }
        OrgMember m = OrgMember.builder()
                .orgId(org.getId())
                .userId(user.getId())
                .role(role)
                .addedAt(OffsetDateTime.now())
                .build();
        OrgMember saved = memberRepository.save(m);
        AuditContext.set("org.member.added");
        AuditContext.setTarget("org_member", orgId + ":" + user.getId());
        AuditContext.putPayload("role", role.name());
        return saved;
    }

    @Transactional
    public void removeMember(UUID orgId, UUID userId) {
        OrgMember m = memberRepository.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(() -> ApiException.notFound("Member not found"));
        if (m.getRole() == OrgMember.Role.OWNER) {
            long owners = memberRepository.countByOrgIdAndRole(orgId, OrgMember.Role.OWNER);
            if (owners <= 1) {
                throw ApiException.badRequest("Cannot remove the last OWNER");
            }
        }
        memberRepository.deleteByOrgIdAndUserId(orgId, userId);
        AuditContext.set("org.member.removed");
        AuditContext.setTarget("org_member", orgId + ":" + userId);
    }

    @Transactional
    public void transferOwner(UUID orgId, UUID newOwnerUserId) {
        OrgMember newOwner = memberRepository.findByOrgIdAndUserId(orgId, newOwnerUserId)
                .orElseThrow(() -> ApiException.notFound("New owner is not a member"));
        for (OrgMember existing : memberRepository.findByOrgId(orgId)) {
            if (existing.getRole() == OrgMember.Role.OWNER && !existing.getUserId().equals(newOwnerUserId)) {
                existing.setRole(OrgMember.Role.ADMIN);
                memberRepository.save(existing);
            }
        }
        newOwner.setRole(OrgMember.Role.OWNER);
        memberRepository.save(newOwner);
        AuditContext.set("org.owner.transferred");
        AuditContext.setTarget("organization", orgId.toString());
        AuditContext.putPayload("new_owner_user_id", newOwnerUserId.toString());
    }
}
