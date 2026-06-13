package com.example.cp.sso;

import com.example.cp.audit.AuditWriter;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link SsoProvisioningService}, the {@code @Transactional} seam extracted from
 * {@code SsoSuccessHandler} so JIT user/identity/membership writes commit atomically (audit P2:
 * "SsoSuccessHandler is a transaction-script with no transaction boundary").
 */
class SsoProvisioningServiceTest {

    private final UUID providerId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private UserRepository userRepo;
    private OrgMemberRepository memberRepo;
    private SsoIdentityRepository identityRepo;
    private AuditWriter auditWriter;
    private SsoProvisioningService service;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        memberRepo = mock(OrgMemberRepository.class);
        identityRepo = mock(SsoIdentityRepository.class);
        auditWriter = mock(AuditWriter.class);
        when(memberRepo.findByOrgIdAndUserId(any(), any())).thenReturn(Optional.empty());
        service = new SsoProvisioningService(userRepo, memberRepo, identityRepo, auditWriter);
    }

    private SsoProvider provider() {
        return SsoProvider.builder()
                .id(providerId)
                .orgId(orgId)
                .type(SsoProvider.Type.OIDC)
                .configJson("{}")
                .allowedEmailDomains("example.com")
                .enabled(true)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    @Test
    @DisplayName("existing (provider, subject) binding resolves the bound user without creating anything")
    void bindingWins() {
        UUID boundUserId = UUID.randomUUID();
        when(identityRepo.findByProviderIdAndSubject(providerId, "sub-1"))
                .thenReturn(Optional.of(SsoIdentity.builder().id(UUID.randomUUID())
                        .providerId(providerId).subject("sub-1").userId(boundUserId)
                        .createdAt(OffsetDateTime.now()).build()));
        User bound = User.builder().id(boundUserId).email("real@example.com")
                .status(User.Status.ACTIVE).superAdmin(false).tokenVersion(0L).build();
        when(userRepo.findById(boundUserId)).thenReturn(Optional.of(bound));

        SsoProvisioningService.ProvisionResult result =
                service.provision(provider(), "sub-1", "asserted@example.com", "N", orgId, "a***@example.com", "ip");

        assertThat(result.user().getId()).isEqualTo(boundUserId);
        verify(userRepo, never()).save(any());
        verify(identityRepo, never()).save(any());
    }

    @Test
    @DisplayName("JIT-creates a non-super-admin user, links the identity, and adds membership")
    void jitCreatesLinksAndAddsMembership() {
        when(identityRepo.findByProviderIdAndSubject(providerId, "sub-2")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.provision(provider(), "sub-2", "new@example.com", "New", orgId, "n***@example.com", "ip");

        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(userCap.capture());
        assertThat(userCap.getValue().isSuperAdmin()).isFalse();
        assertThat(userCap.getValue().getEmail()).isEqualTo("new@example.com");

        ArgumentCaptor<SsoIdentity> idCap = ArgumentCaptor.forClass(SsoIdentity.class);
        verify(identityRepo).save(idCap.capture());
        assertThat(idCap.getValue().getSubject()).isEqualTo("sub-2");

        ArgumentCaptor<OrgMember> memberCap = ArgumentCaptor.forClass(OrgMember.class);
        verify(memberRepo).save(memberCap.capture());
        assertThat(memberCap.getValue().getRole()).isEqualTo(OrgMember.Role.MEMBER);
        assertThat(memberCap.getValue().getOrgId()).isEqualTo(orgId);
    }

    @Test
    @DisplayName("auto-links an existing user by email without re-creating or elevating it")
    void autoLinksExistingUser() {
        UUID existingId = UUID.randomUUID();
        when(identityRepo.findByProviderIdAndSubject(providerId, "sub-3")).thenReturn(Optional.empty());
        User existing = User.builder().id(existingId).email("existing@example.com")
                .status(User.Status.ACTIVE).superAdmin(false).tokenVersion(0L).build();
        when(userRepo.findByEmail("existing@example.com")).thenReturn(Optional.of(existing));

        SsoProvisioningService.ProvisionResult result =
                service.provision(provider(), "sub-3", "existing@example.com", "E", orgId, "e***@example.com", "ip");

        assertThat(result.user().getId()).isEqualTo(existingId);
        verify(userRepo, never()).save(any());
        verify(identityRepo).save(any());
    }

    @Test
    @DisplayName("no membership write when orgId is null")
    void noMembershipWhenOrgNull() {
        when(identityRepo.findByProviderIdAndSubject(providerId, "sub-4")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("x@example.com")).thenReturn(Optional.empty());
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.provision(provider(), "sub-4", "x@example.com", "X", null, "x***@example.com", "ip");

        verify(memberRepo, never()).save(any());
    }
}
