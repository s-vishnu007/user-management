package com.example.cp.mfa;

import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end two-step login for an MFA-enabled user:
 * <ol>
 *   <li>{@code POST /login} with the correct password returns {@code mfaRequired=true} + a challenge
 *       and NO session token;</li>
 *   <li>{@code POST /api/v1/auth/mfa/login} with the challenge + a valid TOTP code returns the full
 *       session token.</li>
 * </ol>
 * Also verifies that a non-enrolled user still gets a session straight from {@code /login}, and that
 * a wrong MFA code is rejected with 401.
 */
class MfaLoginIT extends AbstractIntegrationTest {

    @Autowired
    private MfaService mfaService;

    @Autowired
    private UserMfaRepository userMfaRepository;

    private final DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    private final SystemTimeProvider timeProvider = new SystemTimeProvider();

    private String currentCode(String secret) throws Exception {
        long bucket = Math.floorDiv(timeProvider.getTime(), 30);
        return codeGenerator.generate(secret, bucket);
    }

    /** Enrolls and enables MFA for a user, returning the plaintext secret. */
    private String enableMfa(User user) {
        MfaService.EnrollmentResult enrollment = mfaService.enroll(user.getId(), user.getEmail());
        boolean ok = mfaService.confirmEnrollment(user.getId(),
                generateQuietly(enrollment.secret()));
        assertThat(ok).isTrue();
        // Enrollment confirmation consumes the current TOTP step (advancing last_accepted_step to
        // defeat replay — see MfaServiceTest.confirmEnrollment_recordsTheAcceptedStep). In production
        // the first MFA login lands in a later 30s window; this test performs it in the same window,
        // so clear the just-consumed step to model that elapsed time and let the login present a valid
        // current code. The replay guard still rejects any step <= the last consumed one.
        userMfaRepository.findByUserId(user.getId()).ifPresent(row -> {
            row.setLastAcceptedStep(null);
            userMfaRepository.save(row);
        });
        return enrollment.secret();
    }

    private String generateQuietly(String secret) {
        try {
            return currentCode(secret);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mfaEnabledUser_login_returnsChallenge_then_mfaLogin_returnsSession() throws Exception {
        User user = seedUser("mfa-" + rnd() + "@example.com", "MFA User", false);
        String secret = enableMfa(user);

        // Step 1: password login -> challenge, no session token.
        String step1 = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", user.getEmail(), "password", DEFAULT_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())   // NON_NULL: omitted on a challenge
                .andExpect(jsonPath("$.mfaChallenge").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String challenge = objectMapper.readTree(step1).get("mfaChallenge").asText();

        // Step 2: challenge + valid code -> full session token.
        String step2 = mockMvc.perform(post("/api/v1/auth/mfa/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("challenge", challenge, "code", currentCode(secret)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(step2);
        assertThat(node.get("accessToken").asText()).isNotBlank();
        assertThat(node.get("user").get("email").asText()).isEqualTo(user.getEmail());
    }

    @Test
    void mfaLogin_withWrongCode_isUnauthorized() throws Exception {
        User user = seedUser("mfa-" + rnd() + "@example.com", "MFA User", false);
        enableMfa(user);

        String step1 = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", user.getEmail(), "password", DEFAULT_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String challenge = objectMapper.readTree(step1).get("mfaChallenge").asText();

        mockMvc.perform(post("/api/v1/auth/mfa/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("challenge", challenge, "code", "000000"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonEnrolledUser_login_returnsSessionDirectly() throws Exception {
        User user = seedUser("plain-" + rnd() + "@example.com", "Plain User", false);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", user.getEmail(), "password", DEFAULT_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    /**
     * P0-1 / P1-3: the email-only fallback (no signed challenge) is gone. Supplying {email, code}
     * with no challenge can no longer collapse 2FA to a single TOTP guess — it is a 400, never a
     * session, even with the otherwise-correct current code.
     */
    @Test
    void mfaLogin_withoutChallenge_isRejected_evenWithACorrectCode() throws Exception {
        User user = seedUser("mfa-" + rnd() + "@example.com", "MFA User", false);
        String secret = enableMfa(user);

        mockMvc.perform(post("/api/v1/auth/mfa/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", user.getEmail(), "code", currentCode(secret)))))
                .andExpect(status().isBadRequest());
    }

    /**
     * P0-1: the MFA-login endpoint is rate-limited/lockout-protected. After the configured ceiling
     * of bad codes (default 5) the account is locked and even a correct code is refused — the 6-digit
     * TOTP can no longer be brute-forced.
     */
    @Test
    void mfaLogin_repeatedBadCodes_lockOutTheAccount() throws Exception {
        User user = seedUser("mfa-lock-" + rnd() + "@example.com", "MFA Lock User", false);
        String secret = enableMfa(user);

        // Pin a distinct source IP so this test's failure counters do not pollute (or get polluted by)
        // the shared 127.0.0.1 per-IP counter that other integration tests accrue against.
        String clientIp = "203.0.113." + (1 + (Math.abs(user.getEmail().hashCode()) % 200));
        RequestPostProcessor fromIp = req -> { req.setRemoteAddr(clientIp); return req; };

        String step1 = mockMvc.perform(post("/api/v1/auth/login").with(fromIp)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", user.getEmail(), "password", DEFAULT_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String challenge = objectMapper.readTree(step1).get("mfaChallenge").asText();

        // Burn through the lockout ceiling with wrong codes (default app.auth.lockout.max-attempts=5).
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/mfa/login").with(fromIp)
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(
                                    Map.of("challenge", challenge, "code", "000000"))))
                    .andExpect(status().isUnauthorized());
        }

        // Now even the CORRECT code is refused because the account is locked.
        mockMvc.perform(post("/api/v1/auth/mfa/login").with(fromIp)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("challenge", challenge, "code", currentCode(secret)))))
                .andExpect(status().isUnauthorized());
    }
}
