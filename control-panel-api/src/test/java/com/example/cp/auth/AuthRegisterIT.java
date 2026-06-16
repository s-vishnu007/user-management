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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for self-service signup: {@code POST /api/v1/auth/register} via the real HTTP
 * filter chain on {@link AbstractIntegrationTest} (Testcontainers Postgres, in-memory rate-limit /
 * revocation).
 *
 * <p>Registration creates a brand-new {@code Organization} (unique auto-derived slug) with the new
 * user as its sole {@code OWNER}, mints an ACTIVE-but-unverified user, and auto-logs them in by
 * setting the HttpOnly {@code cp_session} cookie (and returning the same token as the
 * {@code accessToken}). {@code user.superAdmin} is hard-coded {@code false} — a signup can never mint
 * a super-admin — so an attacker-supplied {@code "superAdmin":true} in the body is an unknown, ignored
 * field with no privilege effect.
 *
 * <p>The per-IP auth rate-limit capacity is raised for this class (all MockMvc requests share the
 * {@code 127.0.0.1} bucket) so the several protected POSTs across these cases do not trip a 429;
 * {@code expose-verification-token} is irrelevant here but harmless. The shared
 * {@code application-test.yml} is NOT modified — overrides are class-local via
 * {@link TestPropertySource}.
 */
@TestPropertySource(properties = {
        "app.auth.expose-verification-token=true",
        "app.ratelimit.auth.capacity=1000",
        "app.ratelimit.auth.refill-per-minute=1000"
})
class AuthRegisterIT extends AbstractIntegrationTest {

    /** Builds the register request body as JSON. */
    private String registerJson(String fullName, String email, String password, String orgName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        body.put("email", email);
        body.put("password", password);
        body.put("orgName", orgName);
        return objectMapper.writeValueAsString(body);
    }

    private String loginJson(String email, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void register_createsOwnerOrg_setsSessionCookie_andReturnsUnverifiedNonSuperUser() throws Exception {
        String email = "register-" + rnd() + "@example.com";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(registerJson("Reggie Owner", email, DEFAULT_PASSWORD, "Example " + rnd())))
                .andExpect(status().isOk())
                // The auto-login sets the HttpOnly cp_session cookie.
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(cookie().exists("cp_session"))
                .andExpect(cookie().httpOnly("cp_session", true))
                // A bearer accessToken is also returned for non-browser clients.
                .andExpect(jsonPath("$.accessToken", not(org.hamcrest.Matchers.emptyOrNullString())))
                .andExpect(jsonPath("$.user.email", is(email)))
                .andExpect(jsonPath("$.user.status", is("ACTIVE")))
                .andExpect(jsonPath("$.user.emailVerified", is(false)))
                .andExpect(jsonPath("$.user.superAdmin", is(false)))
                .andExpect(jsonPath("$.orgSlug", not(org.hamcrest.Matchers.emptyOrNullString())))
                .andExpect(jsonPath("$.emailVerificationSent", is(true)));
    }

    @Test
    void register_thenLogin_andMeShowsNewOrgWithOwnerRole() throws Exception {
        String email = "owner-flow-" + rnd() + "@example.com";
        String orgName = "Flow Org " + rnd();

        String registerResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(registerJson("Flow Owner", email, DEFAULT_PASSWORD, orgName)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode registerNode = objectMapper.readTree(registerResponse);
        String orgSlug = registerNode.get("orgSlug").asText();

        // The newly-registered user can log in with the same credentials (full session token).
        String token = loginAndGetToken(email, DEFAULT_PASSWORD);

        // /me reflects exactly one membership: the freshly-created org, with role OWNER.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email", is(email)))
                .andExpect(jsonPath("$.user.superAdmin", is(false)))
                .andExpect(jsonPath("$.orgs.length()", is(1)))
                .andExpect(jsonPath("$.orgs[0].slug", is(orgSlug)))
                .andExpect(jsonPath("$.orgs[0].role", is("OWNER")));
    }

    @Test
    void register_duplicateEmail_isConflict() throws Exception {
        String email = "dup-" + rnd() + "@example.com";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(registerJson("First", email, DEFAULT_PASSWORD, "First Org " + rnd())))
                .andExpect(status().isOk());

        // Same email, different org name -> 409 (email uniqueness is enforced in createUser).
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(registerJson("Second", email, DEFAULT_PASSWORD, "Second Org " + rnd())))
                .andExpect(status().isConflict());
    }

    @Test
    void register_weakPassword_isBadRequest() throws Exception {
        // "short" is < 12 chars and lacks the required upper/digit/symbol mix.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(registerJson("Weak Pwd", "weak-" + rnd() + "@example.com", "short", "Weak Org " + rnd())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_blankOrgName_isBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(registerJson("No Org", "noorg-" + rnd() + "@example.com", DEFAULT_PASSWORD, "   ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_withSuperAdminTrueInBody_stillYieldsNonSuperUser() throws Exception {
        String email = "noprivesc-" + rnd() + "@example.com";

        // "superAdmin":true is an unknown field on RegisterRequest — it is ignored, never bound, and
        // RegistrationService/UserService hard-code superAdmin=false. No privilege escalation.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "Escalation Attempt");
        body.put("email", email);
        body.put("password", DEFAULT_PASSWORD);
        body.put("orgName", "Privesc Org " + rnd());
        body.put("superAdmin", true);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.superAdmin", is(false)));

        // And a subsequent /me confirms the persisted user is not a super-admin either.
        String token = loginAndGetToken(email, DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.superAdmin", is(false)));
    }
}
