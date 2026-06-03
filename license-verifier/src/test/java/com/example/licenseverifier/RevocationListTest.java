package com.example.licenseverifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RevocationListTest {

    private static final String ISSUER = "https://control-panel.example.com";
    private static final Instant ISSUED_AT = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant NEXT_UPDATE = Instant.parse("2026-06-01T11:00:00Z");

    private RevocationList list(Set<String> revoked) {
        return new RevocationList(ISSUER, ISSUED_AT, NEXT_UPDATE, revoked);
    }

    @Test
    void isRevoked_true_for_listed_jti() {
        RevocationList list = list(Set.of("lic_revoked_001", "lic_revoked_002"));

        assertThat(list.isRevoked("lic_revoked_001")).isTrue();
        assertThat(list.isRevoked("lic_revoked_002")).isTrue();
    }

    @Test
    void isRevoked_false_for_unlisted_jti() {
        RevocationList list = list(Set.of("lic_revoked_001"));

        assertThat(list.isRevoked("lic_active_999")).isFalse();
    }

    @Test
    void isRevoked_false_for_null_jti() {
        RevocationList list = list(Set.of("lic_revoked_001"));

        assertThat(list.isRevoked(null)).isFalse();
    }

    @Test
    void isRevoked_false_when_revoked_set_empty() {
        RevocationList list = list(Set.of());

        assertThat(list.isRevoked("anything")).isFalse();
    }

    @Test
    void null_revoked_set_is_treated_as_empty_and_never_revokes() {
        RevocationList list = new RevocationList(ISSUER, ISSUED_AT, NEXT_UPDATE, null);

        assertThat(list.revokedJtis()).isEmpty();
        assertThat(list.isRevoked("anything")).isFalse();
    }

    @Test
    void accessors_return_constructor_values() {
        RevocationList list = list(Set.of("lic_revoked_001"));

        assertThat(list.issuer()).isEqualTo(ISSUER);
        assertThat(list.issuedAt()).isEqualTo(ISSUED_AT);
        assertThat(list.nextUpdate()).isEqualTo(NEXT_UPDATE);
        assertThat(list.revokedJtis()).containsExactly("lic_revoked_001");
    }

    @Test
    void revokedJtis_is_an_unmodifiable_defensive_copy() {
        RevocationList list = list(Set.of("lic_revoked_001"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> list.revokedJtis().add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void isStale_false_before_nextUpdate() {
        RevocationList list = list(Set.of("lic_revoked_001"));
        Instant now = NEXT_UPDATE.minus(Duration.ofMinutes(30));

        assertThat(list.isStale(now, Duration.ofHours(1))).isFalse();
    }

    @Test
    void isStale_false_within_maxStale_grace_after_nextUpdate() {
        RevocationList list = list(Set.of("lic_revoked_001"));
        // 30 minutes past nextUpdate, but maxStale grace is 1 hour -> still fresh enough.
        Instant now = NEXT_UPDATE.plus(Duration.ofMinutes(30));

        assertThat(list.isStale(now, Duration.ofHours(1))).isFalse();
    }

    @Test
    void isStale_false_exactly_at_nextUpdate_plus_maxStale_boundary() {
        RevocationList list = list(Set.of("lic_revoked_001"));
        // Exactly at the boundary: isAfter is strict, so the list is NOT yet stale.
        Instant now = NEXT_UPDATE.plus(Duration.ofHours(1));

        assertThat(list.isStale(now, Duration.ofHours(1))).isFalse();
    }

    @Test
    void isStale_true_past_nextUpdate_plus_maxStale() {
        RevocationList list = list(Set.of("lic_revoked_001"));
        // 1 hour 1 second past nextUpdate, beyond the 1-hour grace.
        Instant now = NEXT_UPDATE.plus(Duration.ofHours(1)).plus(Duration.ofSeconds(1));

        assertThat(list.isStale(now, Duration.ofHours(1))).isTrue();
    }

    @Test
    void isStale_true_past_nextUpdate_when_maxStale_zero() {
        RevocationList list = list(Set.of("lic_revoked_001"));
        Instant now = NEXT_UPDATE.plus(Duration.ofSeconds(1));

        assertThat(list.isStale(now, Duration.ZERO)).isTrue();
    }

    @Test
    void isStale_true_when_nextUpdate_is_null_regardless_of_now() {
        RevocationList list = new RevocationList(ISSUER, ISSUED_AT, null, Set.of("lic_revoked_001"));

        assertThat(list.isStale(ISSUED_AT, Duration.ofHours(24))).isTrue();
        assertThat(list.isStale(Instant.EPOCH, Duration.ofHours(24))).isTrue();
    }
}
