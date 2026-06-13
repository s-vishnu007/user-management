package com.example.cp.rbac;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuthenticatedUser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.assertj.core.api.ThrowingConsumer;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RbacAuthorizationService#assertCanAssign}. No Spring context: the
 * three collaborators ({@link PermissionService}, {@link RolePermissionRepository},
 * {@link PermissionRepository}) are Mockito mocks injected by {@link InjectMocks}, and
 * {@link AuthenticatedUser} principals are constructed directly.
 *
 * <p>Exercises the bean's fixed authorization order:
 * <ol>
 *   <li>null actor -&gt; 401;</li>
 *   <li>system/super roles blocked: null role -&gt; 400, {@code SUPER_ADMIN} code -&gt; 403,
 *       {@code is_system=true} -&gt; 403 (the block precedes the super-admin amplification bypass);</li>
 *   <li>global scope ({@code orgId == null}) requires platform authority ({@code superAdmin} or
 *       {@code role.assign});</li>
 *   <li>privilege amplification: a non-super actor cannot grant any permission code it does not
 *       itself hold (org-scoped perms via {@link PermissionService#permissionsFor}, not the
 *       global-only SecurityContext authorities);</li>
 *   <li>self-elevation guard: a non-super actor cannot assign a role to its own user id.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RbacAuthorizationServiceTest {

    @Mock
    private PermissionService permissionService;
    @Mock
    private RolePermissionRepository rolePermissionRepository;
    @Mock
    private PermissionRepository permissionRepository;

    @InjectMocks
    private RbacAuthorizationService service;

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

    // --- principal helpers -------------------------------------------------

    private static AuthenticatedUser superAdmin() {
        return new AuthenticatedUser(ACTOR, "root@example.com", true,
                Set.of(), AuthorityUtils.NO_AUTHORITIES, false, null);
    }

    /** Non-super human actor holding exactly the supplied global authority codes. */
    private static AuthenticatedUser human(String... authorities) {
        return new AuthenticatedUser(ACTOR, "alice@example.com", false,
                Set.of(authorities), AuthorityUtils.NO_AUTHORITIES, false, null);
    }

    // --- role / permission stubs -------------------------------------------

    private static Role role(String code, boolean system) {
        return Role.builder().id(ROLE_ID).code(code).name(code).isSystem(system).build();
    }

    /** A plain, non-system, assignable role. */
    private static Role assignableRole() {
        return role("ORG_BILLING", false);
    }

    /**
     * Wires {@code rolePermissionRepository.findByRoleId(ROLE_ID)} and the per-permission
     * {@code permissionRepository.findById(...)} lookups so that
     * {@code service.permissionCodesForRole(ROLE_ID)} resolves to the supplied codes.
     */
    private void stubRoleGrants(String... codes) {
        List<RolePermission> rps = new java.util.ArrayList<>();
        for (String code : codes) {
            UUID permId = UUID.nameUUIDFromBytes(("perm:" + code).getBytes());
            rps.add(RolePermission.builder().roleId(ROLE_ID).permissionId(permId).build());
            when(permissionRepository.findById(permId))
                    .thenReturn(Optional.of(Permission.builder().id(permId).code(code).build()));
        }
        when(rolePermissionRepository.findByRoleId(ROLE_ID)).thenReturn(rps);
    }

    private void stubActorPerms(UUID orgId, String... codes) {
        when(permissionService.permissionsFor(ACTOR, orgId)).thenReturn(Set.of(codes));
    }

    /** Asserts the call throws an {@link ApiException} with the given status and detail substring. */
    private static ThrowingConsumer<Throwable> apiException(HttpStatus status, String detailContains) {
        return t -> {
            assertThat(t).isInstanceOf(ApiException.class);
            ApiException ex = (ApiException) t;
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getDetail()).contains(detailContains);
        };
    }

    // ======================================================================
    //  (1) authentication
    // ======================================================================

    @Test
    void nullActor_throwsUnauthorized_andTouchesNoCollaborators() {
        assertThatThrownBy(() -> service.assertCanAssign(null, assignableRole(), TARGET, ORG))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(permissionService, rolePermissionRepository, permissionRepository);
    }

    // ======================================================================
    //  (2) system / super roles blocked
    // ======================================================================

    @Nested
    class SystemAndSuperRolesBlocked {

        @Test
        void nullRole_throwsBadRequest() {
            assertThatThrownBy(() -> service.assertCanAssign(superAdmin(), null, TARGET, ORG))
                    .satisfies(apiException(HttpStatus.BAD_REQUEST, "Role is required"));
        }

        @Test
        void superAdminCode_isForbiddenEvenForSuperAdminActor() {
            // The SUPER_ADMIN / is_system block runs BEFORE the super-admin amplification bypass,
            // so even a super-admin actor cannot grant SUPER_ADMIN via the API.
            Role superRole = role("SUPER_ADMIN", true);
            assertThatThrownBy(() -> service.assertCanAssign(superAdmin(), superRole, TARGET, ORG))
                    .satisfies(apiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN cannot be assigned"));
            verifyNoInteractions(permissionService, rolePermissionRepository, permissionRepository);
        }

        @Test
        void systemRole_isForbiddenEvenForSuperAdminActor() {
            Role systemRole = role("ORG_OWNER", true);
            assertThatThrownBy(() -> service.assertCanAssign(superAdmin(), systemRole, TARGET, ORG))
                    .satisfies(apiException(HttpStatus.FORBIDDEN, "System roles cannot be assigned"));
            verifyNoInteractions(permissionService, rolePermissionRepository, permissionRepository);
        }
    }

    // ======================================================================
    //  (3) global scope requires platform authority
    // ======================================================================

    @Nested
    class GlobalScopeAuthority {

        @Test
        void globalAssignment_byActorWithoutRoleAssign_isForbidden() {
            // orgId == null and the actor is neither super-admin nor holds role.assign.
            AuthenticatedUser actor = human("user.read");
            assertThatThrownBy(() -> service.assertCanAssign(actor, assignableRole(), TARGET, null))
                    .satisfies(apiException(HttpStatus.FORBIDDEN, "Global role assignment requires platform authority"));
            // Fails before the amplification check, so the role-grant lookups never run.
            verifyNoInteractions(permissionService, rolePermissionRepository, permissionRepository);
        }

        @Test
        void globalAssignment_byActorWithRoleAssign_passesScopeGate() {
            // role.assign satisfies the scope gate; amplification then governs (actor holds the
            // single code the role grants), so the assignment succeeds globally.
            AuthenticatedUser actor = human("role.assign", "user.read");
            stubRoleGrants("user.read");
            stubActorPerms(null, "role.assign", "user.read");

            assertThatCode(() -> service.assertCanAssign(actor, assignableRole(), TARGET, null))
                    .doesNotThrowAnyException();
        }

        @Test
        void globalAssignment_bySuperAdmin_passesScopeGateWithoutAuthorities() {
            // super-admin bypasses both the scope gate and amplification.
            assertThatCode(() -> service.assertCanAssign(superAdmin(), assignableRole(), TARGET, null))
                    .doesNotThrowAnyException();
            verifyNoInteractions(permissionService, rolePermissionRepository, permissionRepository);
        }
    }

    // ======================================================================
    //  (4) privilege amplification
    // ======================================================================

    @Nested
    class PrivilegeAmplification {

        @Test
        void actorLackingAGrantedCode_isForbidden_withThatCodeInMessage() {
            // Org-scoped: actor holds {org.read, user.read} but the role grants user.write.
            AuthenticatedUser actor = human(); // global authorities irrelevant for the org path
            stubRoleGrants("user.write");
            stubActorPerms(ORG, "org.read", "user.read");

            assertThatThrownBy(() -> service.assertCanAssign(actor, assignableRole(), TARGET, ORG))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getDetail())
                                .contains("Cannot grant authority you do not hold")
                                .contains("user.write");
                    });
        }

        @Test
        void amplificationUsesOrgScopedPerms_notGlobalAuthorities() {
            // The actor's GLOBAL authorities include user.write, but its ORG-scoped perms do not.
            // The check must consult permissionsFor(actor, ORG) and therefore deny.
            AuthenticatedUser actor = human("user.write"); // global authority present
            stubRoleGrants("user.write");
            stubActorPerms(ORG, "org.read"); // org-scoped perms lack user.write

            assertThatThrownBy(() -> service.assertCanAssign(actor, assignableRole(), TARGET, ORG))
                    .satisfies(apiException(HttpStatus.FORBIDDEN, "user.write"));

            verify(permissionService).permissionsFor(ACTOR, ORG);
        }

        @Test
        void actorHoldingAllGrantedCodes_passesAmplification() {
            AuthenticatedUser actor = human();
            stubRoleGrants("user.read", "user.write");
            stubActorPerms(ORG, "user.read", "user.write", "org.read");

            assertThatCode(() -> service.assertCanAssign(actor, assignableRole(), TARGET, ORG))
                    .doesNotThrowAnyException();
        }

        @Test
        void superAdminActor_skipsAmplificationLookups() {
            // A super-admin never triggers the permissionsFor / role-grant resolution.
            assertThatCode(() -> service.assertCanAssign(superAdmin(), assignableRole(), TARGET, ORG))
                    .doesNotThrowAnyException();
            verify(permissionService, never()).permissionsFor(any(), any());
            verifyNoInteractions(rolePermissionRepository, permissionRepository);
        }
    }

    // ======================================================================
    //  (5) self-elevation guard
    // ======================================================================

    @Nested
    class SelfElevationGuard {

        @Test
        void nonSuperActorAssigningToSelf_isForbidden_evenWhenAmplificationPasses() {
            // Amplification must PASS first (actor holds every code the role grants) so the call
            // reaches the self-elevation guard, isolating step (5).
            AuthenticatedUser actor = human();
            stubRoleGrants("user.read");
            stubActorPerms(ORG, "user.read");

            // targetUserId == actor.userId() -> self assignment.
            assertThatThrownBy(() -> service.assertCanAssign(actor, assignableRole(), ACTOR, ORG))
                    .satisfies(apiException(HttpStatus.FORBIDDEN, "Cannot assign roles to yourself"));
        }

        @Test
        void superAdminAssigningToSelf_isAllowed() {
            // The self-elevation guard explicitly excludes super-admins.
            assertThatCode(() -> service.assertCanAssign(superAdmin(), assignableRole(), ACTOR, ORG))
                    .doesNotThrowAnyException();
        }

        @Test
        void nonSuperActorAssigningToAnotherUser_isAllowed() {
            AuthenticatedUser actor = human();
            stubRoleGrants("user.read");
            stubActorPerms(ORG, "user.read");

            assertThatCode(() -> service.assertCanAssign(actor, assignableRole(), TARGET, ORG))
                    .doesNotThrowAnyException();
        }
    }

    // ======================================================================
    //  success path
    // ======================================================================

    @Test
    void successPath_nonSuperOrgScopedAssignment_completesQuietly() {
        AuthenticatedUser actor = human("role.assign");
        stubRoleGrants("subscription.read", "subscription.write");
        stubActorPerms(ORG, "subscription.read", "subscription.write", "org.read");

        assertThatCode(() -> service.assertCanAssign(actor, assignableRole(), TARGET, ORG))
                .doesNotThrowAnyException();

        verify(permissionService).permissionsFor(ACTOR, ORG);
        verify(rolePermissionRepository).findByRoleId(ROLE_ID);
    }

    // ======================================================================
    //  assertCanRemove — mirrors the assignment guards (P3), minus self-elevation.
    // ======================================================================

    @Nested
    class RemovalGuard {

        @Test
        void nullActor_throwsUnauthorized_andTouchesNoCollaborators() {
            assertThatThrownBy(() -> service.assertCanRemove(null, assignableRole(), ORG))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
            verifyNoInteractions(permissionService, rolePermissionRepository, permissionRepository);
        }

        @Test
        void nullRole_throwsBadRequest() {
            assertThatThrownBy(() -> service.assertCanRemove(superAdmin(), null, ORG))
                    .satisfies(apiException(HttpStatus.BAD_REQUEST, "Role is required"));
        }

        @Test
        void superAdminCode_isForbiddenEvenForSuperAdminActor() {
            Role superRole = role("SUPER_ADMIN", true);
            assertThatThrownBy(() -> service.assertCanRemove(superAdmin(), superRole, ORG))
                    .satisfies(apiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN cannot be removed"));
            verifyNoInteractions(permissionService, rolePermissionRepository, permissionRepository);
        }

        @Test
        void systemRole_isForbiddenEvenForSuperAdminActor() {
            Role systemRole = role("ORG_OWNER", true);
            assertThatThrownBy(() -> service.assertCanRemove(superAdmin(), systemRole, ORG))
                    .satisfies(apiException(HttpStatus.FORBIDDEN, "System roles cannot be removed"));
            verifyNoInteractions(permissionService, rolePermissionRepository, permissionRepository);
        }

        @Test
        void globalRemoval_byActorWithoutRoleAssign_isForbidden() {
            AuthenticatedUser actor = human("user.read");
            assertThatThrownBy(() -> service.assertCanRemove(actor, assignableRole(), null))
                    .satisfies(apiException(HttpStatus.FORBIDDEN, "Global role removal requires platform authority"));
            verifyNoInteractions(permissionService, rolePermissionRepository, permissionRepository);
        }

        @Test
        void actorLackingAGrantedCode_isForbidden_withThatCodeInMessage() {
            AuthenticatedUser actor = human();
            stubRoleGrants("user.write");
            stubActorPerms(ORG, "org.read", "user.read");

            assertThatThrownBy(() -> service.assertCanRemove(actor, assignableRole(), ORG))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getDetail())
                                .contains("Cannot remove a role granting authority you do not hold")
                                .contains("user.write");
                    });
        }

        @Test
        void actorHoldingAllGrantedCodes_passes() {
            AuthenticatedUser actor = human();
            stubRoleGrants("user.read", "user.write");
            stubActorPerms(ORG, "user.read", "user.write", "org.read");

            assertThatCode(() -> service.assertCanRemove(actor, assignableRole(), ORG))
                    .doesNotThrowAnyException();
        }

        @Test
        void superAdminActor_skipsAmplificationLookups() {
            assertThatCode(() -> service.assertCanRemove(superAdmin(), assignableRole(), ORG))
                    .doesNotThrowAnyException();
            verify(permissionService, never()).permissionsFor(any(), any());
            verifyNoInteractions(rolePermissionRepository, permissionRepository);
        }

        @Test
        void removalHasNoSelfElevationGuard_actorMayRemoveOwnNonSystemRole() {
            // Unlike assignment, removal does NOT block a (de-escalating) self-removal: an actor that
            // holds every code the role grants may remove that role from its own user id.
            AuthenticatedUser actor = human();
            stubRoleGrants("user.read");
            stubActorPerms(ORG, "user.read");

            // No targetUserId parameter exists on assertCanRemove (self vs other is irrelevant); the
            // call simply must not throw when amplification passes.
            assertThatCode(() -> service.assertCanRemove(actor, assignableRole(), ORG))
                    .doesNotThrowAnyException();
        }
    }
}
