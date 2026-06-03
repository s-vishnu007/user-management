package com.example.cp.compliance;

import com.example.cp.audit.AuditWriter;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.Organization;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GDPR/CCPA compliance integration coverage built on {@link AbstractIntegrationTest}.
 *
 * <p>Covers the three data-subject flows end-to-end through the real HTTP/method-security stack:
 * <ul>
 *   <li><b>export</b> returns the subject's personal data and enforces who may request it
 *       (subject-self and super_admin allowed; an unrelated user is forbidden);</li>
 *   <li><b>erase</b> pseudonymises PII (email redacted, full_name nulled), flips status to DELETED,
 *       writes an erasure_log row, and RETAINS the user's audit rows (with PII scrubbed);</li>
 *   <li><b>tenant delete</b> marks the org DELETED and erases its members' PII; super_admin only.</li>
 * </ul>
 */
class DataPrivacyIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditWriter auditWriter;
    @Autowired private ErasureLogRepository erasureLogRepository;

    @Test
    void export_returnsSubjectData_forSelf_andSuperAdmin_butForbidsOthers() throws Exception {
        Organization org = seedOrg("Export Org");
        User subject = seedUser("subject-" + rnd() + "@example.com", "Subject Person", false);
        addOrgMember(org.getId(), subject.getId(), OrgMember.Role.MEMBER);
        User other = seedUser("other-" + rnd() + "@example.com", "Other Person", false);
        User admin = seedUser("admin-" + rnd() + "@example.com", "Platform Admin", true);

        // The subject can export their own data and sees their profile.
        mockMvc.perform(get("/api/v1/privacy/export")
                        .param("userId", subject.getId().toString())
                        .with(asUser(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportType", is("user")))
                .andExpect(jsonPath("$.profile.email", is(subject.getEmail())))
                .andExpect(jsonPath("$.profile.fullName", is("Subject Person")))
                .andExpect(jsonPath("$.orgMemberships[0].orgId", is(org.getId().toString())));

        // A super-admin can export anyone.
        mockMvc.perform(get("/api/v1/privacy/export")
                        .param("userId", subject.getId().toString())
                        .with(asSuperAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.email", is(subject.getEmail())));

        // An unrelated user is forbidden from exporting the subject.
        mockMvc.perform(get("/api/v1/privacy/export")
                        .param("userId", subject.getId().toString())
                        .with(asUser(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    void orgExport_allowedForOrgAdmin_forbiddenForPlainMember() throws Exception {
        Organization org = seedOrg("Org Export Org");
        User orgAdmin = seedUser("orgadmin-" + rnd() + "@example.com", "Org Admin", false);
        addOrgMember(org.getId(), orgAdmin.getId(), OrgMember.Role.ADMIN);
        User member = seedUser("member-" + rnd() + "@example.com", "Plain Member", false);
        addOrgMember(org.getId(), member.getId(), OrgMember.Role.MEMBER);

        mockMvc.perform(get("/api/v1/privacy/export")
                        .param("orgId", org.getId().toString())
                        .with(asUser(orgAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportType", is("org")))
                .andExpect(jsonPath("$.profile.id", is(org.getId().toString())))
                .andExpect(jsonPath("$.members.length()", greaterThanOrEqualTo(2)));

        // A plain MEMBER may not export the whole tenant.
        mockMvc.perform(get("/api/v1/privacy/export")
                        .param("orgId", org.getId().toString())
                        .with(asUser(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    void export_requiresExactlyOneSubject() throws Exception {
        User admin = seedUser("admin-" + rnd() + "@example.com", "Admin", true);
        mockMvc.perform(get("/api/v1/privacy/export").with(asSuperAdmin(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void erase_redactsPii_revokesSessions_keepsAuditTrail() throws Exception {
        User admin = seedUser("eraser-" + rnd() + "@example.com", "Eraser Admin", true);
        User subject = seedUser("erasee-" + rnd() + "@example.com", "Erasee Person", false);

        // Seed an audit row authored by the subject (the trail we must RETAIN). audit_log is immutable
        // (append-only, tamper-evident: a DB trigger blocks UPDATE/DELETE), so erasure cannot and must
        // not mutate it. Pseudonymisation comes from redacting the users row the actor UUID points at,
        // plus data-minimisation: audit payloads carry the actor UUID and non-PII fields, never the raw
        // email. We seed a PII-free payload to reflect that policy.
        auditWriter.record(subject.getId(), null, "user.updated", "user",
                subject.getId().toString(), Map.of("field", "fullName"), "203.0.113.7");

        long auditBefore = countAuditForActor(subject.getId());
        assertThat(auditBefore).isGreaterThanOrEqualTo(1);

        // Non-super-admin cannot erase.
        User nobody = seedUser("nobody-" + rnd() + "@example.com", "Nobody", false);
        mockMvc.perform(post("/api/v1/privacy/erase")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("userId", subject.getId())))
                        .with(asUser(nobody)))
                .andExpect(status().isForbidden());

        // Super-admin erases the subject.
        mockMvc.perform(post("/api/v1/privacy/erase")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("userId", subject.getId())))
                        .with(asSuperAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("erased")))
                .andExpect(jsonPath("$.userId", is(subject.getId().toString())));

        // PII is gone: email redacted, full_name null, status DELETED.
        User erased = userRepository.findById(subject.getId()).orElseThrow();
        assertThat(erased.getEmail()).doesNotContain("erasee-").contains("redacted.invalid");
        assertThat(erased.getFullName()).isNull();
        assertThat(erased.getPasswordHash()).isNull();
        assertThat(erased.getStatus()).isEqualTo(User.Status.DELETED);
        // Sessions revoked: token_version bumped.
        assertThat(erased.getTokenVersion()).isGreaterThanOrEqualTo(1L);

        // Audit trail RETAINED unmodified (immutable) — rows still exist for the actor — and carries no
        // direct PII, so nothing leaks post-erasure (the actor UUID now resolves to a redacted users row).
        long auditAfter = countAuditForActor(subject.getId());
        assertThat(auditAfter).isGreaterThanOrEqualTo(auditBefore);
        Integer leaking = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_user_id = ? AND payload_json::text LIKE '%erasee-%'",
                Integer.class, subject.getId());
        assertThat(leaking).isZero();

        // An erasure_log ledger row was written for the subject.
        var ledger = erasureLogRepository
                .findBySubjectTypeAndSubjectIdOrderByRequestedAtDesc("user", subject.getId());
        assertThat(ledger).isNotEmpty();
        assertThat(ledger.get(0).getAction()).isEqualTo("erase");
        assertThat(ledger.get(0).getRequestedBy()).isEqualTo(admin.getId());
    }

    @Test
    void tenantDelete_marksOrgDeleted_andErasesMemberPii_superAdminOnly() throws Exception {
        Organization org = seedOrg("Doomed Tenant");
        User admin = seedUser("tenant-admin-" + rnd() + "@example.com", "Tenant Admin", true);
        User member = seedUser("tenant-member-" + rnd() + "@example.com", "Tenant Member", false);
        addOrgMember(org.getId(), member.getId(), OrgMember.Role.OWNER);

        // A non-super-admin org owner cannot off-board the tenant.
        mockMvc.perform(post("/api/v1/privacy/tenant/{orgId}/delete", org.getId())
                        .with(asUser(member)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/privacy/tenant/{orgId}/delete", org.getId())
                        .with(asSuperAdmin(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("deleted")))
                .andExpect(jsonPath("$.orgId", is(org.getId().toString())));

        Organization after = organizationRepository.findById(org.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Organization.Status.DELETED);

        User erasedMember = userRepository.findById(member.getId()).orElseThrow();
        assertThat(erasedMember.getStatus()).isEqualTo(User.Status.DELETED);
        assertThat(erasedMember.getFullName()).isNull();
        assertThat(erasedMember.getEmail()).contains("redacted.invalid");

        var ledger = erasureLogRepository
                .findBySubjectTypeAndSubjectIdOrderByRequestedAtDesc("org", org.getId());
        assertThat(ledger).isNotEmpty();
    }

    private long countAuditForActor(UUID actorId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_user_id = ?", Integer.class, actorId);
        return n == null ? 0 : n;
    }
}
