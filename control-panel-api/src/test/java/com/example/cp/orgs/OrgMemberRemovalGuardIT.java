package com.example.cp.orgs;

import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end guards for {@code DELETE /api/v1/orgs/{orgId}/members/{userId}} via
 * {@code OrgService.removeMember} (P2 actor-vs-target rank guard + P3 last-OWNER guard).
 *
 * <p>Mirrors {@code RbacPrivilegeEscalationIT} style: real users + memberships are seeded and the
 * request runs through the full HTTP/method-security stack with an injected principal whose org role
 * is resolved from the DB membership row (as {@code OrgController.removeMember} does).
 */
class OrgMemberRemovalGuardIT extends AbstractIntegrationTest {

    private String url(java.util.UUID orgId, java.util.UUID userId) {
        return "/api/v1/orgs/" + orgId + "/members/" + userId;
    }

    // ------------------------------------------------------------------
    // P2: an ADMIN cannot remove an OWNER (target rank >= actor rank).
    // ------------------------------------------------------------------

    @Test
    void admin_cannotRemoveOwner_isForbidden() throws Exception {
        Organization org = seedOrg("Rank Org");

        User admin = seedUser("admin-" + rnd() + "@example.com", "Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);

        User owner = seedUser("owner-" + rnd() + "@example.com", "Owner", false);
        addOrgMember(org.getId(), owner.getId(), OrgMember.Role.OWNER);
        // A second owner so the last-OWNER guard is NOT what blocks this — the rank guard is.
        User owner2 = seedUser("owner2-" + rnd() + "@example.com", "Owner2", false);
        addOrgMember(org.getId(), owner2.getId(), OrgMember.Role.OWNER);

        mockMvc.perform(delete(url(org.getId(), owner.getId()))
                        .with(asUser(admin, "org.read")))
                .andExpect(status().isForbidden());

        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), owner.getId()))
                .as("the OWNER must NOT be removed by an ADMIN")
                .isPresent();
    }

    @Test
    void admin_cannotRemovePeerAdmin_isForbidden() throws Exception {
        Organization org = seedOrg("Peer Org");
        addOrgMember(org.getId(), seedUser("owner-" + rnd() + "@example.com", "O", false).getId(), OrgMember.Role.OWNER);

        User admin = seedUser("admin-" + rnd() + "@example.com", "Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);
        User peer = seedUser("peer-" + rnd() + "@example.com", "Peer", false);
        addOrgMember(org.getId(), peer.getId(), OrgMember.Role.ADMIN);

        mockMvc.perform(delete(url(org.getId(), peer.getId()))
                        .with(asUser(admin, "org.read")))
                .andExpect(status().isForbidden());

        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), peer.getId())).isPresent();
    }

    // ------------------------------------------------------------------
    // Control: an ADMIN may remove a strictly-lower-rank member (MEMBER).
    // ------------------------------------------------------------------

    @Test
    void admin_canRemoveMember_succeeds() throws Exception {
        Organization org = seedOrg("Ok Remove Org");
        addOrgMember(org.getId(), seedUser("owner-" + rnd() + "@example.com", "O", false).getId(), OrgMember.Role.OWNER);

        User admin = seedUser("admin-" + rnd() + "@example.com", "Admin", false);
        addOrgMember(org.getId(), admin.getId(), OrgMember.Role.ADMIN);
        User member = seedUser("member-" + rnd() + "@example.com", "Member", false);
        addOrgMember(org.getId(), member.getId(), OrgMember.Role.MEMBER);

        mockMvc.perform(delete(url(org.getId(), member.getId()))
                        .with(asUser(admin, "org.read")))
                .andExpect(status().isNoContent());

        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), member.getId())).isEmpty();
    }

    // ------------------------------------------------------------------
    // P3: the last OWNER cannot be removed (conditional-delete guard). For a NON-super actor, a sole
    // OWNER removing itself is blocked by the rank guard (rank(target) == rank(actor)) -> 403, which
    // already prevents the last-OWNER zero-out; the 400 last-OWNER path is exercised via the
    // super-admin (rank-exempt) test below.
    // ------------------------------------------------------------------

    @Test
    void owner_cannotRemoveSelfWhenSoleOwner_isForbiddenByRankGuard() throws Exception {
        Organization org = seedOrg("Last Owner Org");
        User owner = seedUser("owner-" + rnd() + "@example.com", "Owner", false);
        addOrgMember(org.getId(), owner.getId(), OrgMember.Role.OWNER);

        mockMvc.perform(delete(url(org.getId(), owner.getId()))
                        .with(asUser(owner, "org.read")))
                .andExpect(status().isForbidden());

        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), owner.getId()))
                .as("the last OWNER must remain")
                .isPresent();
    }

    @Test
    void owner_cannotRemovePeerOwner_isForbiddenByRankGuard() throws Exception {
        Organization org = seedOrg("Two Owner Org");
        User owner1 = seedUser("owner1-" + rnd() + "@example.com", "Owner1", false);
        addOrgMember(org.getId(), owner1.getId(), OrgMember.Role.OWNER);
        User owner2 = seedUser("owner2-" + rnd() + "@example.com", "Owner2", false);
        addOrgMember(org.getId(), owner2.getId(), OrgMember.Role.OWNER);

        // owner1 (rank OWNER) removing owner2 (rank OWNER): rank(target) >= rank(actor) so a non-super
        // actor is blocked. Peers cannot remove peers; the super-admin path below proves the
        // not-last-owner removal succeeds.
        mockMvc.perform(delete(url(org.getId(), owner2.getId()))
                        .with(asUser(owner1, "org.read")))
                .andExpect(status().isForbidden());

        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), owner2.getId())).isPresent();
    }

    // ------------------------------------------------------------------
    // Super-admin bypasses the rank guard but NOT the last-OWNER guard.
    // ------------------------------------------------------------------

    @Test
    void superAdmin_canRemoveOwner_whenNotLast() throws Exception {
        Organization org = seedOrg("Super Org");
        User owner1 = seedUser("owner1-" + rnd() + "@example.com", "Owner1", false);
        addOrgMember(org.getId(), owner1.getId(), OrgMember.Role.OWNER);
        User owner2 = seedUser("owner2-" + rnd() + "@example.com", "Owner2", false);
        addOrgMember(org.getId(), owner2.getId(), OrgMember.Role.OWNER);

        User root = seedUser("root-" + rnd() + "@example.com", "Root", true);

        mockMvc.perform(delete(url(org.getId(), owner2.getId()))
                        .with(asSuperAdmin(root)))
                .andExpect(status().isNoContent());

        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), owner2.getId())).isEmpty();
        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), owner1.getId())).isPresent();
    }

    @Test
    void superAdmin_cannotRemoveLastOwner_isBadRequest() throws Exception {
        Organization org = seedOrg("Super Last Org");
        User owner = seedUser("owner-" + rnd() + "@example.com", "Owner", false);
        addOrgMember(org.getId(), owner.getId(), OrgMember.Role.OWNER);

        User root = seedUser("root-" + rnd() + "@example.com", "Root", true);

        mockMvc.perform(delete(url(org.getId(), owner.getId()))
                        .with(asSuperAdmin(root)))
                .andExpect(status().isBadRequest());

        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), owner.getId())).isPresent();
    }
}
