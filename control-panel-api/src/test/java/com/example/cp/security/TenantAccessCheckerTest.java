package com.example.cp.security;

import com.example.cp.common.AuthenticatedUser;
import com.example.cp.licenses.LicenseToken;
import com.example.cp.licenses.LicenseTokenRepository;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link TenantAccessChecker}. No Spring context: the three repositories are
 * Mockito mocks injected by {@link InjectMocks}, and the {@code SecurityContextHolder} is driven
 * manually with a real {@link UsernamePasswordAuthenticationToken} whose principal is an
 * {@link AuthenticatedUser} (matching what {@code SecurityUtils.currentUser()} expects).
 *
 * <p>Covered matrix (per the bean's fixed authorization order):
 * <ol>
 *   <li>{@code super_admin} -&gt; always allow, repositories never consulted;</li>
 *   <li>api-key principal -&gt; allowed for reads only when {@code apiKeyOrgId == target org},
 *       denied for all writes (no write scope by default);</li>
 *   <li>human -&gt; {@code OrgMember} membership for reads; {@code OWNER/ADMIN} rank for writes;</li>
 *   <li>default-deny: unauthenticated, anonymous, wrong principal type, null/blank arg,
 *       missing resource, unresolved org, org mismatch.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantAccessCheckerTest {

    @Mock
    private SubscriptionRepository subRepo;
    @Mock
    private OrgMemberRepository memberRepo;
    @Mock
    private LicenseTokenRepository tokenRepo;

    @InjectMocks
    private TenantAccessChecker checker;

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID SUB = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final String JTI = "jti-abc-123";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // --- principal helpers -------------------------------------------------

    private void authenticateAs(AuthenticatedUser principal) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, "n/a", principal.grantedAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private static AuthenticatedUser superAdmin() {
        return new AuthenticatedUser(USER, "root@example.com", true,
                Set.of(), AuthorityUtils.NO_AUTHORITIES, false, null);
    }

    private static AuthenticatedUser human() {
        return new AuthenticatedUser(USER, "alice@example.com", false,
                Set.of(), AuthorityUtils.NO_AUTHORITIES, false, null);
    }

    /** Api-key principal bound to the given org (apiKey=true, superAdmin=false, userId=null). */
    private static AuthenticatedUser apiKeyBoundTo(UUID orgId) {
        return new AuthenticatedUser(null, null, false,
                Set.of("subscription.read", "usage.read"), AuthorityUtils.NO_AUTHORITIES, true, orgId);
    }

    private void stubMembership(UUID orgId, UUID userId, OrgMember.Role role) {
        OrgMember m = OrgMember.builder().orgId(orgId).userId(userId).role(role).build();
        when(memberRepo.findByOrgIdAndUserId(orgId, userId)).thenReturn(Optional.of(m));
    }

    private void stubSubscriptionOrg(UUID subId, UUID orgId) {
        Subscription s = new Subscription();
        s.setOrgId(orgId);
        when(subRepo.findById(subId)).thenReturn(Optional.of(s));
    }

    private void stubTokenSubscription(String jti, UUID subId) {
        LicenseToken t = new LicenseToken();
        t.setSubscriptionId(subId);
        when(tokenRepo.findByJti(jti)).thenReturn(Optional.of(t));
    }

    // ======================================================================
    //  super_admin: always allow, repositories never consulted
    // ======================================================================

    @Nested
    class SuperAdminBypass {

        @Test
        void canAccessOrg_allowsAndSkipsRepositories() {
            authenticateAs(superAdmin());
            assertThat(checker.canAccessOrg(ORG)).isTrue();
            verifyNoInteractions(memberRepo);
        }

        @Test
        void canManageOrg_allows() {
            authenticateAs(superAdmin());
            assertThat(checker.canManageOrg(ORG)).isTrue();
            verifyNoInteractions(memberRepo);
        }

        @Test
        void subscriptionReadAndWrite_allowWithoutMembership() {
            authenticateAs(superAdmin());
            stubSubscriptionOrg(SUB, ORG); // org resolves, but no membership stubbed
            assertThat(checker.canReadSubscription(SUB)).isTrue();
            assertThat(checker.canWriteSubscription(SUB)).isTrue();
            verifyNoInteractions(memberRepo);
        }

        @Test
        void licenseByJti_readRevokeIngest_allow() {
            authenticateAs(superAdmin());
            stubTokenSubscription(JTI, SUB);
            stubSubscriptionOrg(SUB, ORG);
            assertThat(checker.canReadLicenseByJti(JTI)).isTrue();
            assertThat(checker.canRevokeLicenseByJti(JTI)).isTrue();
            assertThat(checker.canIngestUsageForJti(JTI)).isTrue();
            verifyNoInteractions(memberRepo);
        }
    }

    // ======================================================================
    //  api-key principal: read iff apiKeyOrgId == target org; writes denied
    // ======================================================================

    @Nested
    class ApiKeyPrincipal {

        @Test
        void canAccessOrg_allowsWhenKeyBoundToTargetOrg() {
            authenticateAs(apiKeyBoundTo(ORG));
            assertThat(checker.canAccessOrg(ORG)).isTrue();
            // Membership repo must NOT be consulted for an api-key principal.
            verifyNoInteractions(memberRepo);
        }

        @Test
        void canAccessOrg_deniesWhenKeyBoundToDifferentOrg() {
            authenticateAs(apiKeyBoundTo(OTHER_ORG));
            assertThat(checker.canAccessOrg(ORG)).isFalse();
            verifyNoInteractions(memberRepo);
        }

        @Test
        void canAccessOrg_deniesWhenKeyHasNoBoundOrg() {
            authenticateAs(apiKeyBoundTo(null));
            assertThat(checker.canAccessOrg(ORG)).isFalse();
        }

        @Test
        void canManageOrg_alwaysDeniedEvenForOwnOrg() {
            authenticateAs(apiKeyBoundTo(ORG));
            assertThat(checker.canManageOrg(ORG)).isFalse();
            verifyNoInteractions(memberRepo);
        }

        @Test
        void canReadSubscription_allowsForBoundOrg() {
            authenticateAs(apiKeyBoundTo(ORG));
            stubSubscriptionOrg(SUB, ORG);
            assertThat(checker.canReadSubscription(SUB)).isTrue();
        }

        @Test
        void canReadSubscription_deniesForOtherOrg() {
            authenticateAs(apiKeyBoundTo(OTHER_ORG));
            stubSubscriptionOrg(SUB, ORG);
            assertThat(checker.canReadSubscription(SUB)).isFalse();
        }

        @Test
        void canWriteSubscription_andIssueLicense_deniedForApiKey() {
            authenticateAs(apiKeyBoundTo(ORG));
            stubSubscriptionOrg(SUB, ORG);
            assertThat(checker.canWriteSubscription(SUB)).isFalse();
            assertThat(checker.canIssueLicenseForSubscription(SUB)).isFalse();
        }

        @Test
        void ingestUsageForJti_allowsWhenKeyOwnsLicenseOrg_deniesOtherwise() {
            stubTokenSubscription(JTI, SUB);
            stubSubscriptionOrg(SUB, ORG);

            authenticateAs(apiKeyBoundTo(ORG));
            assertThat(checker.canIngestUsageForJti(JTI)).isTrue();

            authenticateAs(apiKeyBoundTo(OTHER_ORG));
            assertThat(checker.canIngestUsageForJti(JTI)).isFalse();
        }

        @Test
        void revokeLicenseByJti_deniedForApiKey() {
            authenticateAs(apiKeyBoundTo(ORG));
            stubTokenSubscription(JTI, SUB);
            stubSubscriptionOrg(SUB, ORG);
            assertThat(checker.canRevokeLicenseByJti(JTI)).isFalse();
        }

        @Test
        void inOrgReadWriteShortcuts_followKeyRules() {
            authenticateAs(apiKeyBoundTo(ORG));
            assertThat(checker.canReadSubscriptionInOrg(ORG)).isTrue();
            assertThat(checker.canWriteSubscriptionInOrg(ORG)).isFalse();
            assertThat(checker.canReadSubscriptionInOrg(OTHER_ORG)).isFalse();
        }
    }

    // ======================================================================
    //  human: membership for reads, OWNER/ADMIN rank for writes
    // ======================================================================

    @Nested
    class HumanMembership {

        @Test
        void canAccessOrg_allowsAnyMember() {
            authenticateAs(human());
            stubMembership(ORG, USER, OrgMember.Role.VIEWER);
            assertThat(checker.canAccessOrg(ORG)).isTrue();
            verify(memberRepo).findByOrgIdAndUserId(ORG, USER);
        }

        @Test
        void canAccessOrg_deniesNonMember() {
            authenticateAs(human());
            when(memberRepo.findByOrgIdAndUserId(ORG, USER)).thenReturn(Optional.empty());
            assertThat(checker.canAccessOrg(ORG)).isFalse();
        }

        @Test
        void canAccessOrg_deniesMembershipInDifferentOrg() {
            authenticateAs(human());
            // member of OTHER_ORG, asks about ORG -> repo returns empty for ORG
            when(memberRepo.findByOrgIdAndUserId(ORG, USER)).thenReturn(Optional.empty());
            assertThat(checker.canAccessOrg(ORG)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = OrgMember.Role.class, names = {"OWNER", "ADMIN"})
        void canManageOrg_allowsOwnerAndAdmin(OrgMember.Role role) {
            authenticateAs(human());
            stubMembership(ORG, USER, role);
            assertThat(checker.canManageOrg(ORG)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = OrgMember.Role.class, names = {"MEMBER", "VIEWER"})
        void canManageOrg_deniesMemberAndViewer(OrgMember.Role role) {
            authenticateAs(human());
            stubMembership(ORG, USER, role);
            assertThat(checker.canManageOrg(ORG)).isFalse();
        }

        @Test
        void canManageOrg_deniesNonMember() {
            authenticateAs(human());
            when(memberRepo.findByOrgIdAndUserId(ORG, USER)).thenReturn(Optional.empty());
            assertThat(checker.canManageOrg(ORG)).isFalse();
        }

        @Test
        void allReads_allowedForViewer_allWrites_deniedForViewer() {
            authenticateAs(human());
            stubMembership(ORG, USER, OrgMember.Role.VIEWER);
            stubSubscriptionOrg(SUB, ORG);
            stubTokenSubscription(JTI, SUB);

            assertThat(checker.canReadSubscription(SUB)).isTrue();
            assertThat(checker.canReadUsageForSubscription(SUB)).isTrue();
            assertThat(checker.canReadLicenseByJti(JTI)).isTrue();
            assertThat(checker.canReadSubscriptionInOrg(ORG)).isTrue();

            assertThat(checker.canWriteSubscription(SUB)).isFalse();
            assertThat(checker.canIssueLicenseForSubscription(SUB)).isFalse();
            assertThat(checker.canRevokeLicenseByJti(JTI)).isFalse();
            assertThat(checker.canWriteSubscriptionInOrg(ORG)).isFalse();
        }

        @Test
        void writesAllowedForAdmin() {
            authenticateAs(human());
            stubMembership(ORG, USER, OrgMember.Role.ADMIN);
            stubSubscriptionOrg(SUB, ORG);
            stubTokenSubscription(JTI, SUB);

            assertThat(checker.canWriteSubscription(SUB)).isTrue();
            assertThat(checker.canIssueLicenseForSubscription(SUB)).isTrue();
            assertThat(checker.canRevokeLicenseByJti(JTI)).isTrue();
            assertThat(checker.canWriteSubscriptionInOrg(ORG)).isTrue();
        }
    }

    // ======================================================================
    //  default-deny: unauthenticated / bad principal / null / missing / mismatch
    // ======================================================================

    @Nested
    class DefaultDeny {

        @Test
        void noAuthentication_deniesEverything() {
            SecurityContextHolder.clearContext();
            assertThat(checker.canAccessOrg(ORG)).isFalse();
            assertThat(checker.canManageOrg(ORG)).isFalse();
            verifyNoInteractions(memberRepo, subRepo, tokenRepo);
        }

        @Test
        void anonymousAuthentication_denies() {
            Authentication anon = new AnonymousAuthenticationToken(
                    "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(anon);
            SecurityContextHolder.setContext(ctx);

            assertThat(checker.canAccessOrg(ORG)).isFalse();
            assertThat(checker.canManageOrg(ORG)).isFalse();
        }

        @Test
        void nonAuthenticatedUserPrincipal_denies() {
            // Principal is a plain String, not an AuthenticatedUser -> SecurityUtils returns empty.
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    "someString", "n/a", AuthorityUtils.NO_AUTHORITIES);
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);

            assertThat(checker.canAccessOrg(ORG)).isFalse();
        }

        @Test
        void nullOrgId_deniesWithoutTouchingRepos() {
            authenticateAs(superAdmin()); // even super_admin is denied on a null target org
            assertThat(checker.canAccessOrg(null)).isFalse();
            assertThat(checker.canManageOrg(null)).isFalse();
            verifyNoInteractions(memberRepo);
        }

        @Test
        void missingSubscription_deniesAndNeverCallsMemberRepo() {
            authenticateAs(human());
            when(subRepo.findById(SUB)).thenReturn(Optional.empty());
            assertThat(checker.canReadSubscription(SUB)).isFalse();
            assertThat(checker.canWriteSubscription(SUB)).isFalse();
            verify(memberRepo, never()).findByOrgIdAndUserId(any(), any());
        }

        @Test
        void nullSubscriptionId_denies() {
            authenticateAs(human());
            assertThat(checker.canReadSubscription(null)).isFalse();
            assertThat(checker.canWriteSubscription(null)).isFalse();
            verifyNoInteractions(subRepo);
        }

        @Test
        void missingToken_deniesLicenseChecks() {
            authenticateAs(human());
            when(tokenRepo.findByJti(JTI)).thenReturn(Optional.empty());
            assertThat(checker.canReadLicenseByJti(JTI)).isFalse();
            assertThat(checker.canRevokeLicenseByJti(JTI)).isFalse();
            assertThat(checker.canIngestUsageForJti(JTI)).isFalse();
            verifyNoInteractions(subRepo);
        }

        @Test
        void nullOrBlankJti_deniesWithoutTouchingTokenRepo() {
            authenticateAs(human());
            assertThat(checker.canReadLicenseByJti(null)).isFalse();
            assertThat(checker.canReadLicenseByJti("   ")).isFalse();
            assertThat(checker.canRevokeLicenseByJti("")).isFalse();
            verifyNoInteractions(tokenRepo);
        }

        @Test
        void tokenPresentButSubscriptionMissing_denies() {
            authenticateAs(human());
            stubTokenSubscription(JTI, SUB);
            when(subRepo.findById(SUB)).thenReturn(Optional.empty());
            assertThat(checker.canReadLicenseByJti(JTI)).isFalse();
        }

        @Test
        void humanWithNullUserId_deniesMembershipChecks() {
            // A malformed human principal (userId == null) must never match a membership.
            AuthenticatedUser noId = new AuthenticatedUser(null, "x@example.com", false,
                    Set.of(), AuthorityUtils.NO_AUTHORITIES, false, null);
            authenticateAs(noId);
            assertThat(checker.canAccessOrg(ORG)).isFalse();
            assertThat(checker.canManageOrg(ORG)).isFalse();
            verifyNoInteractions(memberRepo);
        }

        @Test
        void orgMismatch_humanMemberOfWrongOrg_denies() {
            authenticateAs(human());
            stubSubscriptionOrg(SUB, ORG);
            // user is a member of OTHER_ORG only; lookup for ORG returns empty
            when(memberRepo.findByOrgIdAndUserId(ORG, USER)).thenReturn(Optional.empty());
            lenient().when(memberRepo.findByOrgIdAndUserId(OTHER_ORG, USER))
                    .thenReturn(Optional.of(OrgMember.builder()
                            .orgId(OTHER_ORG).userId(USER).role(OrgMember.Role.OWNER).build()));
            assertThat(checker.canReadSubscription(SUB)).isFalse();
            assertThat(checker.canWriteSubscription(SUB)).isFalse();
        }
    }
}
