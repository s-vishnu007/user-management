package com.example.cp.auth;

import com.example.cp.common.ApiException;
import com.example.cp.mfa.MfaService;
import com.example.cp.mfa.UserMfaRepository;
import com.example.cp.keys.KeyEncryptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link SessionTokenService} parse/issue, with a focus on the purpose+issuer claim
 * enforcement that closes the latent MFA-bypass (a password-only {@code mfa_challenge} token, signed
 * with the SAME session secret, must NOT be accepted as a session token).
 */
class SessionTokenServiceTest {

    // 32+ byte secret so the HS256 length guard passes; SHARED with the MfaService below.
    private static final String SECRET = "0123456789abcdef0123456789abcdef-extra";
    private static final UUID USER = UUID.randomUUID();

    private final SessionTokenService tokenService =
            new SessionTokenService(SECRET, Duration.ofMinutes(30));

    @Test
    void issueThenParse_roundTrips_aSessionToken() {
        SessionTokenService.IssuedToken issued =
                tokenService.issue(USER, "u@example.com", true, Set.of("user.read"), 3L);

        SessionTokenService.ParsedToken parsed = tokenService.parse(issued.token());

        assertThat(parsed.userId()).isEqualTo(USER);
        assertThat(parsed.email()).isEqualTo("u@example.com");
        assertThat(parsed.superAdmin()).isTrue();
        assertThat(parsed.authorities()).contains("user.read");
        assertThat(parsed.tokenVersion()).isEqualTo(3L);
        assertThat(parsed.jti()).isNotBlank();
    }

    @Test
    void parse_rejectsAnMfaChallengeSignedWithTheSameSecret() {
        // The MFA challenge is an HS256 token over the SAME session secret. Before the purpose/issuer
        // enforcement it was structurally a valid session token; now parse() must reject it.
        MfaService mfa = new MfaService(
                mock(UserMfaRepository.class), mock(KeyEncryptor.class), "control-panel", SECRET);
        MfaService.MfaChallenge challenge = mfa.issueChallenge(USER, "u@example.com");

        assertThatThrownBy(() -> tokenService.parse(challenge.challenge()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void parse_rejectsGarbage() {
        assertThatThrownBy(() -> tokenService.parse("not-a-jwt"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokenService.parse(""))
                .isInstanceOf(ApiException.class);
    }
}
