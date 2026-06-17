package com.example.cp.orgs;

import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Invite-by-email org membership: adding a member by an email that has no account yet provisions a
 * password-less ACTIVE user and adds them (instead of the old 404 "User with that email not found"),
 * mirroring per-user license issuance. Adding an existing account still works.
 */
class OrgMemberInviteByEmailIT extends AbstractIntegrationTest {

    @Test
    void addMember_byNewEmail_provisionsUserAndMembership() throws Exception {
        Organization org = seedOrg("Invite Org " + rnd());
        User superAdmin = seedUser("inv-super-" + rnd() + "@example.com", "Inv Super", true);
        String newEmail = "invitee-" + rnd() + "@example.com";

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", org.getId())
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(newEmail)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(newEmail))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        Optional<User> provisioned = userRepository.findByEmail(newEmail);
        assertThat(provisioned).isPresent();
        assertThat(provisioned.get().getPasswordHash()).isNull();
        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), provisioned.get().getId()))
                .isPresent();
    }

    @Test
    void addMember_byExistingEmail_stillWorks() throws Exception {
        Organization org = seedOrg("Invite Org2 " + rnd());
        User superAdmin = seedUser("inv2-super-" + rnd() + "@example.com", "Inv2 Super", true);
        User existing = seedUser("inv2-existing-" + rnd() + "@example.com", "Existing", false);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", org.getId())
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"role\":\"VIEWER\"}".formatted(existing.getEmail())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        assertThat(orgMemberRepository.findByOrgIdAndUserId(org.getId(), existing.getId())).isPresent();
    }
}
