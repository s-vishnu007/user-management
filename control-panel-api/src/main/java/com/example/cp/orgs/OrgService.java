package com.example.cp.orgs;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.common.Slugs;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class OrgService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");
    /** Bound on the slug body before a uniqueness suffix is appended (the pattern allows up to 64). */
    private static final int MAX_SLUG_BODY = 40;
    private final SecureRandom slugRandom = new SecureRandom();

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

    /**
     * Creates an organization deriving a unique slug from a free-text name, adding {@code ownerUserId}
     * as OWNER. Used by self-service signup and SSO/Google JIT, where the caller has a display name but
     * no slug. Slug collisions are resolved with numeric then random suffixes; the {@code slug} UNIQUE
     * constraint is the final backstop for the rare concurrent same-name race.
     */
    @Transactional
    public Organization createOrgFromName(String name, UUID ownerUserId) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw ApiException.badRequest("Name is required");
        }
        return createOrg(allocateSlug(trimmed), trimmed, ownerUserId);
    }

    private String allocateSlug(String name) {
        String base = Slugs.slugify(name);
        if (base.length() > MAX_SLUG_BODY) {
            base = base.substring(0, MAX_SLUG_BODY).replaceAll("-+$", "");
        }
        // The slug pattern requires >= 3 chars starting/ending alphanumeric. Pad short/empty bodies.
        if (base.length() < 3) {
            base = base.isEmpty() ? "org" : base + "-org";
        }
        if (!orgRepository.existsBySlug(base)) {
            return base;
        }
        for (int i = 2; i <= 9; i++) {
            String candidate = base + "-" + i;
            if (!orgRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        for (int i = 0; i < 6; i++) {
            String candidate = base + "-" + Integer.toHexString(slugRandom.nextInt(0x10000));
            if (!orgRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        throw ApiException.conflict("Could not allocate an organization slug; try a different name");
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
    public OrgMember addMember(UUID orgId, String email, OrgMember.Role role, OrgMember.Role actorRole) {
        if (role == null) {
            throw ApiException.badRequest("Role is required");
        }
        if (actorRole == null) {
            throw ApiException.forbidden("Not a member of this organization");
        }
        if (rank(role) > rank(actorRole)) {
            throw ApiException.forbidden("Cannot grant a role that outranks your own");
        }
        if (role == OrgMember.Role.OWNER && actorRole != OrgMember.Role.OWNER) {
            throw ApiException.forbidden("Only an OWNER can grant OWNER");
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

    private int rank(OrgMember.Role r) {
        return switch (r) {
            case OWNER -> 4;
            case ADMIN -> 3;
            case MEMBER -> 2;
            case VIEWER -> 1;
        };
    }

    /**
     * Removes {@code userId} from {@code orgId}.
     *
     * <p>{@code actorRole} is the removing principal's effective role in this org (super-admins pass
     * {@code OWNER} and set {@code superAdmin=true}). Mirrors the actor-vs-target rank guard already
     * enforced in {@link #addMember}: a non-super actor may only remove a member of strictly LOWER
     * rank than its own, so an ADMIN can no longer remove an OWNER (or a peer ADMIN). The last-OWNER
     * guard is enforced via a single conditional delete to avoid the count-then-delete race (P3).
     */
    @Transactional
    public void removeMember(UUID orgId, UUID userId, OrgMember.Role actorRole, boolean superAdmin) {
        OrgMember m = memberRepository.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(() -> ApiException.notFound("Member not found"));
        if (!superAdmin) {
            if (actorRole == null) {
                throw ApiException.forbidden("Not a member of this organization");
            }
            // A non-super actor may only remove members ranked strictly below itself. This blocks an
            // ADMIN from removing an OWNER (and an ADMIN from removing a peer ADMIN).
            if (rank(m.getRole()) >= rank(actorRole)) {
                throw ApiException.forbidden("Cannot remove a member whose role equals or outranks your own");
            }
        }
        // Conditional delete: removes the row only if it is not the org's last OWNER, evaluated
        // atomically within the statement. removed == 0 means either the member vanished concurrently
        // or it was the last OWNER.
        int removed = memberRepository.deleteMemberUnlessLastOwner(orgId, userId, OrgMember.Role.OWNER);
        if (removed == 0) {
            if (m.getRole() == OrgMember.Role.OWNER) {
                throw ApiException.badRequest("Cannot remove the last OWNER");
            }
            throw ApiException.notFound("Member not found");
        }
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
