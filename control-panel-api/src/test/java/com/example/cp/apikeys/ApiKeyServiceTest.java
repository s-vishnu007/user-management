package com.example.cp.apikeys;

import com.example.cp.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApiKeyService} with a Mockito {@link ApiKeyRepository}. Focuses on the
 * verification / revocation concurrency contract introduced for P1-7:
 *
 * <ul>
 *   <li>{@code verify()} touches {@code last_used_at} through the guarded targeted UPDATE
 *       ({@code touchLastUsedIfActive}) rather than a full-entity {@code save()}.</li>
 *   <li>If that guarded touch reports 0 rows (a concurrent {@code revoke()} committed between read
 *       and write), the request is treated as a MISS — the just-revoked key is never authenticated
 *       and the revoke is never silently undone.</li>
 *   <li>Already-revoked candidates are skipped, and a wrong hash is a miss.</li>
 *   <li>{@code revoke()} resolves the row org-scoped (404 on unknown / cross-org) and stamps the
 *       revocation via the guarded conditional {@code revokeIfActive} UPDATE.</li>
 *   <li>{@code create()} enforces the creatable-scopes allow-list.</li>
 * </ul>
 */
class ApiKeyServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private ApiKeyRepository repo;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        // Mirror the default creatable-scopes allow-list.
        service = new ApiKeyService(repo, Set.of("usage.ingest", "usage.read", "license.read"));
    }

    // ---- create -----------------------------------------------------------

    @Test
    void create_rejectsScopeOutsideAllowList() {
        assertThatThrownBy(() -> service.create(ORG, "k", Set.of("subscription.read")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repo, never()).save(any());
    }

    @Test
    void create_returnsPlaintextWithExpectedPrefixAndHashesIt() {
        when(repo.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.CreateResult result = service.create(ORG, "ci-key", Set.of("usage.ingest"));

        String plaintext = result.plaintextKey();
        assertThat(plaintext).startsWith("cp_");
        // Stored hash is the SHA-256 of the plaintext, never the plaintext itself.
        assertThat(result.apiKey().getKeyHash())
                .isEqualTo(ApiKeyService.sha256Hex(plaintext))
                .isNotEqualTo(plaintext);
        assertThat(result.apiKey().getKeyPrefix()).isEqualTo(plaintext.substring(0, 8));
        assertThat(result.apiKey().getOrgId()).isEqualTo(ORG);
    }

    // ---- verify ------------------------------------------------------------

    @Test
    void verify_validKey_touchesLastUsedViaGuardedUpdate_notFullSave() {
        ApiKey k = liveKey("cp_abc12", "secret-tail");
        String plaintext = "cp_abc12secret-tail";
        k.setKeyHash(ApiKeyService.sha256Hex(plaintext));
        when(repo.findByKeyPrefix(plaintext.substring(0, 8))).thenReturn(List.of(k));
        when(repo.touchLastUsedIfActive(eq(k.getId()), any(OffsetDateTime.class))).thenReturn(1);

        Optional<ApiKey> match = service.verify(plaintext);

        assertThat(match).isPresent();
        assertThat(match.get().getId()).isEqualTo(k.getId());
        verify(repo).touchLastUsedIfActive(eq(k.getId()), any(OffsetDateTime.class));
        // Must NOT re-persist the whole entity (that is the lost-update bug being fixed).
        verify(repo, never()).save(any());
    }

    @Test
    void verify_whenTouchReportsZeroRows_isTreatedAsMiss_concurrentRevokeWins() {
        // The candidate looked live at read time, but the guarded UPDATE matched 0 rows because a
        // concurrent revoke() committed first. We must NOT authenticate, and we must NOT undo it.
        ApiKey k = liveKey("cp_def34", "tail-2");
        String plaintext = "cp_def34tail-2";
        k.setKeyHash(ApiKeyService.sha256Hex(plaintext));
        when(repo.findByKeyPrefix(plaintext.substring(0, 8))).thenReturn(List.of(k));
        when(repo.touchLastUsedIfActive(eq(k.getId()), any(OffsetDateTime.class))).thenReturn(0);

        assertThat(service.verify(plaintext)).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void verify_skipsRevokedCandidate() {
        ApiKey revoked = liveKey("cp_ghi56", "tail-3");
        String plaintext = "cp_ghi56tail-3";
        revoked.setKeyHash(ApiKeyService.sha256Hex(plaintext));
        revoked.setRevokedAt(OffsetDateTime.now());
        when(repo.findByKeyPrefix(plaintext.substring(0, 8))).thenReturn(List.of(revoked));

        assertThat(service.verify(plaintext)).isEmpty();
        verify(repo, never()).touchLastUsedIfActive(any(), any());
    }

    @Test
    void verify_wrongHash_isMiss() {
        ApiKey k = liveKey("cp_jkl78", "tail-4");
        k.setKeyHash(ApiKeyService.sha256Hex("cp_jkl78different"));
        when(repo.findByKeyPrefix("cp_jkl78")).thenReturn(List.of(k));

        assertThat(service.verify("cp_jkl78tail-4")).isEmpty();
        verify(repo, never()).touchLastUsedIfActive(any(), any());
    }

    @Test
    void verify_nullOrTooShort_isMiss() {
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("cp_short")).isEmpty(); // length < 9
    }

    // ---- revoke ------------------------------------------------------------

    @Test
    void revoke_unknownId_is404_andNoUpdate() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(ORG, id))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(repo, never()).revokeIfActive(any(), any(), any());
    }

    @Test
    void revoke_crossOrg_is404_andNoUpdate() {
        ApiKey foreign = liveKey("cp_mno90", "tail-5");
        foreign.setOrgId(OTHER_ORG);
        when(repo.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.revoke(ORG, foreign.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(repo, never()).revokeIfActive(any(), any(), any());
    }

    @Test
    void revoke_ownOrg_callsGuardedConditionalUpdate_notFullSave() {
        ApiKey k = liveKey("cp_pqr12", "tail-6");
        k.setOrgId(ORG);
        when(repo.findById(k.getId())).thenReturn(Optional.of(k));
        when(repo.revokeIfActive(eq(k.getId()), eq(ORG), any(OffsetDateTime.class))).thenReturn(1);

        service.revoke(ORG, k.getId());

        verify(repo).revokeIfActive(eq(k.getId()), eq(ORG), any(OffsetDateTime.class));
        verify(repo, never()).save(any());
    }

    @Test
    void revoke_nullOrg_is404() {
        assertThatThrownBy(() -> service.revoke(null, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- helpers -----------------------------------------------------------

    private static ApiKey liveKey(String prefix, String tail) {
        return ApiKey.builder()
                .id(UUID.randomUUID())
                .orgId(ORG)
                .name("k")
                .keyPrefix(prefix)
                .keyHash("placeholder")
                .scopesJson("[\"usage.ingest\"]")
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
