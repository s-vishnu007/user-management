package com.example.cp.auth;

import com.example.cp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the email-verification flow that follows self-service signup:
 * {@code POST /api/v1/auth/register} -> {@code POST /api/v1/auth/verify-email} ->
 * {@code POST /api/v1/auth/verify-email/resend}, all on the real HTTP filter chain via
 * {@link AbstractIntegrationTest}.
 *
 * <p>The raw verification token is opaque/single-use/hashed-at-rest and is returned in the register
 * (and resend) response ONLY when {@code app.auth.expose-verification-token=true}; this is enabled
 * class-locally via {@link TestPropertySource} (the shared {@code application-test.yml} is left
 * untouched) so the tests can read and replay it. The auth rate-limit capacity is also raised for this
 * class since every MockMvc call shares the single {@code 127.0.0.1} per-IP bucket.
 *
 * <p>Verification is non-blocking (the user is ACTIVE and may log in before verifying); it only flips
 * the {@code emailVerified} flag and establishes no session of its own. Tokens are single-use: a
 * replayed or bogus token is a 400.
 */
@TestPropertySource(properties = {
        "app.auth.expose-verification-token=true",
        "app.ratelimit.auth.capacity=1000",
        "app.ratelimit.auth.refill-per-minute=1000"
})
class EmailVerificationIT extends AbstractIntegrationTest {

    private String registerJson(String fullName, String email, String password, String orgName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        body.put("email", email);
        body.put("password", password);
        body.put("orgName", orgName);
        return objectMapper.writeValueAsString(body);
    }

    private String verifyEmailJson(String token) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        return objectMapper.writeValueAsString(body);
    }

    /** Registers a user (token exposed) and returns the parsed register response JSON. */
    private JsonNode register(String email, String orgName) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(registerJson("Verify User", email, DEFAULT_PASSWORD, orgName)))
                .andExpect(status().isOk())
                // With expose-verification-token=true the raw token is present in the body.
                .andExpect(jsonPath("$.verificationToken", not(org.hamcrest.Matchers.emptyOrNullString())))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void register_thenVerifyEmail_flipsEmailVerified_andTokenIsSingleUse() throws Exception {
        String email = "verify-" + rnd() + "@example.com";
        JsonNode registerNode = register(email, "Verify Org " + rnd());
        String token = registerNode.get("verificationToken").asText();

        // Before verification, /me reports emailVerified=false.
        String session = loginAndGetToken(email, DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerified", is(false)));

        // Verifying the token flips the flag (public endpoint, no session needed).
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType("application/json")
                        .content(verifyEmailJson(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified", is(true)))
                .andExpect(jsonPath("$.email", is(email)));

        // /me now reflects the verified email (a fresh login proves it is persisted).
        String freshSession = loginAndGetToken(email, DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", bearer(freshSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerified", is(true)));

        // Replaying the SAME (now used) token is rejected as a 400.
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType("application/json")
                        .content(verifyEmailJson(token)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyEmail_withBogusToken_isBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType("application/json")
                        .content(verifyEmailJson("not-a-real-token-" + rnd())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendVerification_asAuthenticatedUser_returnsFreshToken() throws Exception {
        String email = "resend-" + rnd() + "@example.com";
        register(email, "Resend Org " + rnd());

        String session = loginAndGetToken(email, DEFAULT_PASSWORD);

        // Resend issues a brand-new verification token for the still-unverified caller.
        String resendResponse = mockMvc.perform(post("/api/v1/auth/verify-email/resend")
                        .header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")))
                .andExpect(jsonPath("$.alreadyVerified", is(false)))
                // Exposed in dev/test: a fresh raw token is returned for replay.
                .andExpect(jsonPath("$.verification_token", not(org.hamcrest.Matchers.emptyOrNullString())))
                .andReturn().getResponse().getContentAsString();

        // The freshly-resent token verifies the email end-to-end.
        String freshToken = objectMapper.readTree(resendResponse).get("verification_token").asText();
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType("application/json")
                        .content(verifyEmailJson(freshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified", is(true)))
                .andExpect(jsonPath("$.email", is(email)));
    }
}
