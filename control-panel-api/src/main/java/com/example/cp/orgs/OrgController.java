package com.example.cp.orgs;

import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.PageRequestParams;
import com.example.cp.common.PagedResponse;
import com.example.cp.common.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('org.write')")
    public ResponseEntity<OrgDto> create(@Valid @RequestBody CreateOrgRequest body) {
        UUID actorId = SecurityUtils.currentUserId();
        Organization saved = orgService.createOrg(body.slug(), body.name(), actorId);
        return ResponseEntity.status(201).body(OrgDto.from(saved));
    }

    @GetMapping
    public PagedResponse<OrgDto> listMine(@RequestParam(value = "page", required = false) Integer page,
                                          @RequestParam(value = "size", required = false) Integer size) {
        UUID actorId = SecurityUtils.currentUserId();
        List<OrgDto> all = orgService.listOrgsForUser(actorId).stream()
                .map(OrgDto::from).toList();
        return paginate(all, PageRequestParams.of(page, size, null));
    }

    @GetMapping("/{orgId}")
    @PreAuthorize("@orgAccess.isMember(#orgId)")
    public OrgDto get(@PathVariable UUID orgId) {
        return OrgDto.from(orgService.get(orgId));
    }

    @GetMapping("/{orgId}/members")
    @PreAuthorize("@orgAccess.isMember(#orgId)")
    public PagedResponse<OrgMemberDto> listMembers(@PathVariable UUID orgId,
                                                   @RequestParam(value = "page", required = false) Integer page,
                                                   @RequestParam(value = "size", required = false) Integer size) {
        List<OrgMemberDto> all = orgService.listMembersDetailed(orgId).stream()
                .map(OrgMemberDto::from).toList();
        return paginate(all, PageRequestParams.of(page, size, null));
    }

    /**
     * Applies a server-side page/size window (already capped at {@link PageRequestParams#MAX_SIZE})
     * to an in-memory, inherently-bounded collection and reports the TRUE total, so the
     * {@link PagedResponse} envelope no longer lies about pagination (P3). Used for the membership-
     * derived lists that cannot be served by a single Spring Data {@code Pageable} query.
     */
    private static <T> PagedResponse<T> paginate(List<T> all, Pageable pageable) {
        int total = all.size();
        int from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), total);
        int to = (int) Math.min((long) from + pageable.getPageSize(), total);
        List<T> window = all.subList(from, to);
        return PagedResponse.of(window, total, pageable.getPageNumber(), pageable.getPageSize());
    }

    @PostMapping("/{orgId}/members")
    @PreAuthorize("@orgAccess.hasRole(#orgId, 'ADMIN')")
    public ResponseEntity<OrgMemberDto> addMember(@PathVariable UUID orgId,
                                                  @Valid @RequestBody AddMemberRequest body) {
        OrgMember.Role role;
        try {
            role = OrgMember.Role.valueOf(body.role().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw com.example.cp.common.ApiException.badRequest("Invalid role; must be OWNER, ADMIN, MEMBER, or VIEWER");
        }
        AuthenticatedUser actor = SecurityUtils.requireUser();
        OrgMember.Role actorRole = actor.superAdmin()
                ? OrgMember.Role.OWNER
                : orgService.roleOf(orgId, actor.userId())
                        .orElseThrow(() -> com.example.cp.common.ApiException.forbidden("Not a member of this organization"));
        OrgMember saved = orgService.addMember(orgId, body.email(), role, actorRole);
        return ResponseEntity.status(201).body(OrgMemberDto.from(saved, body.email(), null));
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    @PreAuthorize("@orgAccess.hasRole(#orgId, 'ADMIN')")
    public ResponseEntity<Void> removeMember(@PathVariable UUID orgId, @PathVariable UUID userId) {
        AuthenticatedUser actor = SecurityUtils.requireUser();
        OrgMember.Role actorRole = actor.superAdmin()
                ? OrgMember.Role.OWNER
                : orgService.roleOf(orgId, actor.userId())
                        .orElseThrow(() -> com.example.cp.common.ApiException.forbidden("Not a member of this organization"));
        orgService.removeMember(orgId, userId, actorRole, actor.superAdmin());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{orgId}/transfer-owner")
    @PreAuthorize("@orgAccess.hasRole(#orgId, 'OWNER')")
    public ResponseEntity<Void> transferOwner(@PathVariable UUID orgId,
                                              @Valid @RequestBody TransferOwnerRequest body) {
        orgService.transferOwner(orgId, body.newOwnerUserId());
        return ResponseEntity.noContent().build();
    }

    public record CreateOrgRequest(
            @NotBlank @Size(min = 3, max = 64)
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$", message = "lowercase letters, digits and dashes only")
            String slug,
            @NotBlank @Size(max = 255) String name) {}

    public record AddMemberRequest(
            @NotBlank @Email String email,
            @NotBlank String role) {}

    public record TransferOwnerRequest(@NotNull UUID newOwnerUserId) {}

    public record OrgDto(UUID id, String slug, String name, String status,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static OrgDto from(Organization o) {
            return new OrgDto(o.getId(), o.getSlug(), o.getName(),
                    o.getStatus() == null ? null : o.getStatus().name(),
                    o.getCreatedAt(), o.getUpdatedAt());
        }
    }

    public record OrgMemberDto(UUID orgId, UUID userId, String email, String fullName,
                               String role, OffsetDateTime addedAt) {
        public static OrgMemberDto from(OrgService.MemberView v) {
            OrgMember m = v.member();
            return new OrgMemberDto(m.getOrgId(), m.getUserId(), v.email(), v.fullName(),
                    m.getRole() == null ? null : m.getRole().name(),
                    m.getAddedAt());
        }

        public static OrgMemberDto from(OrgMember m, String email, String fullName) {
            return new OrgMemberDto(m.getOrgId(), m.getUserId(), email, fullName,
                    m.getRole() == null ? null : m.getRole().name(),
                    m.getAddedAt());
        }
    }
}
