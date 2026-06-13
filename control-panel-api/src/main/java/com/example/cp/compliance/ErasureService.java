package com.example.cp.compliance;

import com.example.cp.audit.AuditLogRepository;
import com.example.cp.auth.SessionRevocationStore;
import com.example.cp.common.ApiException;
import com.example.cp.common.Ids;
import com.example.cp.mfa.UserMfaRepository;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Right-to-erasure (GDPR Art. 17) and tenant off-boarding.
 *
 * <p><b>Erasure model — pseudonymise, do not destroy the trail.</b> A subject's directly-identifying
 * PII is removed (email rewritten to a non-reversible per-id placeholder, {@code full_name} nulled,
 * {@code password_hash} cleared), the account is marked {@code DELETED}, all live sessions are
 * revoked (token-version bump), and authentication side-data (SSO identities, MFA enrollment) is
 * deleted. The {@code audit_log} rows are RETAINED for compliance: the actor reference (a UUID FK)
 * is kept so the security trail stays intact, while any PII embedded in those rows' free-form
 * {@code payload_json} is scrubbed. The (now redacted) {@code users} row the FK points at is what
 * "pseudonymises the actor".
 *
 * <p>Every operation records an {@link ErasureLog} row (PII-free ledger) in the same transaction.
 */
@Service
public class ErasureService {

    private static final Logger log = LoggerFactory.getLogger(ErasureService.class);

    private final UserRepository userRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final UserMfaRepository userMfaRepository;
    private final ErasureLogRepository erasureLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final SessionRevocationStore revocationStore;
    private final JdbcTemplate jdbc;

    public ErasureService(UserRepository userRepository,
                          OrgMemberRepository orgMemberRepository,
                          UserMfaRepository userMfaRepository,
                          ErasureLogRepository erasureLogRepository,
                          AuditLogRepository auditLogRepository,
                          SessionRevocationStore revocationStore,
                          JdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.orgMemberRepository = orgMemberRepository;
        this.userMfaRepository = userMfaRepository;
        this.erasureLogRepository = erasureLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.revocationStore = revocationStore;
        this.jdbc = jdbc;
    }

    /**
     * Erase a single human data subject. Idempotent in effect: re-running on an already-erased user
     * simply re-applies the redaction.
     *
     * @return the {@link ErasureLog} ledger row written for the request.
     */
    @Transactional
    public ErasureLog eraseUser(UUID userId, UUID requestedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        // 1. Pseudonymise the identity. The placeholder email is unique per id (CITEXT unique
        //    constraint) and non-reversible; full_name + password_hash are dropped entirely.
        user.setEmail(redactedEmail(userId));
        user.setFullName(null);
        user.setPasswordHash(null);
        user.setStatus(User.Status.DELETED);

        // 2. Revoke all live sessions by bumping the durable per-user token-version (JwtAuthFilter
        //    rejects tokens whose "tv" claim no longer matches), with a best-effort cache write.
        long newVersion = user.getTokenVersion() + 1;
        user.setTokenVersion(newVersion);
        userRepository.save(user);
        try {
            revocationStore.setTokenVersion(userId, newVersion);
        } catch (Exception e) {
            // DB column is authoritative; the cache write is an accelerator only.
            log.warn("Failed to write-through token-version during erasure of user {}: {}", userId, e.getMessage());
        }

        // 3. Delete authentication side-data: SSO identities + MFA enrollment.
        int ssoDeleted = jdbc.update("DELETE FROM sso_identities WHERE user_id = ?", userId);
        userMfaRepository.deleteByUserId(userId);

        // 4. Audit rows are RETAINED (the security trail is append-only and tamper-evident — a DB
        //    trigger rejects DELETE and any non-redaction UPDATE), but the PII embedded in those rows
        //    for THIS subject is scrubbed: the raw client IP is always stored, and some writers (e.g.
        //    the SCIM provisioned-event payload) put the raw email into payload_json — so the prior
        //    "the audit writer already masks emails" justification did not hold. We redact payload_json
        //    + ip_address for every row the erased subject authored, within this transaction.
        int auditRedacted = redactSubjectAudit(userId);

        // 5. Ledger row (PII-free).
        ErasureLog ledger = ErasureLog.builder()
                .id(Ids.newId())
                .subjectType(ErasureLog.SubjectType.USER.db())
                .subjectId(userId)
                .requestedBy(requestedBy)
                .requestedAt(OffsetDateTime.now())
                .completedAt(OffsetDateTime.now())
                .action(ErasureLog.Action.ERASE.db())
                .build();
        erasureLogRepository.save(ledger);

        log.info("Erased user {} (ssoIdentitiesDeleted={}, auditRowsRedacted={}); audit_log identity "
                + "columns retained, PII scrubbed", userId, ssoDeleted, auditRedacted);
        return ledger;
    }

    /**
     * Tenant off-boarding: erase every member's PII (so personal data does not survive in a deleted
     * tenant), then mark the organization {@code DELETED}. Org-owned rows that hang off the org via
     * {@code ON DELETE CASCADE} (sso_providers, etc.) are anonymised through the per-member erasure
     * and the org status flip; we keep the org row (and its FK-referencing audit/subscription rows)
     * rather than hard-deleting so the compliance trail and historical billing record survive.
     *
     * @return the {@link ErasureLog} ledger row written for the request.
     */
    @Transactional
    public ErasureLog deleteTenant(UUID orgId, UUID requestedBy) {
        // Confirm the org exists (and capture nothing PII).
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM organizations WHERE id = ?", Integer.class, orgId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("Organization not found");
        }

        int membersErased = 0;
        for (OrgMember m : orgMemberRepository.findByOrgId(orgId)) {
            // Only erase users whose sole/last membership context is being torn down. We erase the
            // PII regardless of other memberships because tenant deletion is a destructive admin
            // action; super_admin-gated. (A member shared across tenants is rare in this model.)
            eraseUserPiiInline(m.getUserId());
            membersErased++;
        }

        // Drop SSO providers for the org (also removes any provider-scoped secrets at rest).
        int ssoProvidersDeleted = jdbc.update("DELETE FROM sso_providers WHERE org_id = ?", orgId);

        // Mark the org DELETED (matches Organization.Status + the CHECK constraint). Subscriptions
        // and audit rows are retained for the compliance/billing record.
        jdbc.update("UPDATE organizations SET status = 'DELETED', updated_at = now() WHERE id = ?", orgId);

        ErasureLog ledger = ErasureLog.builder()
                .id(Ids.newId())
                .subjectType(ErasureLog.SubjectType.ORG.db())
                .subjectId(orgId)
                .requestedBy(requestedBy)
                .requestedAt(OffsetDateTime.now())
                .completedAt(OffsetDateTime.now())
                .action(ErasureLog.Action.ERASE.db())
                .build();
        erasureLogRepository.save(ledger);

        log.info("Deleted tenant {} (membersErased={}, ssoProvidersDeleted={})",
                orgId, membersErased, ssoProvidersDeleted);
        return ledger;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** Pseudonymise + revoke + drop side-data for one user, without writing its own ledger row. */
    private void eraseUserPiiInline(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setEmail(redactedEmail(userId));
            user.setFullName(null);
            user.setPasswordHash(null);
            user.setStatus(User.Status.DELETED);
            long newVersion = user.getTokenVersion() + 1;
            user.setTokenVersion(newVersion);
            userRepository.save(user);
            try {
                revocationStore.setTokenVersion(userId, newVersion);
            } catch (Exception e) {
                log.warn("Failed to write-through token-version during tenant erasure of user {}: {}",
                        userId, e.getMessage());
            }
            jdbc.update("DELETE FROM sso_identities WHERE user_id = ?", userId);
            userMfaRepository.deleteByUserId(userId);
            // Scrub PII from this member's retained audit rows (see eraseUser step 4).
            redactSubjectAudit(userId);
        });
    }

    /**
     * Scrub PII (payload_json + ip_address) from every audit_log row authored by {@code userId}, within
     * the current transaction. The audit_log immutability trigger blocks UPDATE unless the transaction
     * opts in via the session GUC {@code app.audit_redaction='on'}; we set it LOCAL (rolled back with
     * the tx) just before the redaction UPDATE so this is the only sanctioned mutation path. The GUC and
     * the redaction UPDATE share the transaction-bound JDBC connection.
     *
     * @return number of audit rows redacted.
     */
    private int redactSubjectAudit(UUID userId) {
        // Opt this transaction into the narrow audit-redaction exception to the immutability trigger.
        jdbc.execute("SET LOCAL app.audit_redaction = 'on'");
        return auditLogRepository.redactPiiForActor(userId);
    }

    /** Stable, non-reversible placeholder email for an erased user (keeps the unique constraint). */
    private static String redactedEmail(UUID userId) {
        return "erased+" + userId + "@redacted.invalid";
    }
}
