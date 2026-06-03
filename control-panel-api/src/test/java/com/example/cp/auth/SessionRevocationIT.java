package com.example.cp.auth;

import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import com.example.cp.users.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session-revocation integration coverage built on {@link AbstractIntegrationTest}.
 *
 * <p>Exercises the two distinct revocation mechanisms wired through the real HTTP filter chain
 * ({@code JwtAuthFilter}), using {@link #loginAndGetToken} to obtain genuine session JWTs:
 *
 * <ol>
 *   <li><b>Single-session logout</b> — {@code POST /api/v1/auth/logout} denylists the presented
 *       token's {@code jti} for its remaining lifetime, so the SAME bearer is rejected with 401 on
 *       a subsequent protected call. The user stays ACTIVE, so a fresh login still mints a working
 *       token (only the one logged-out token is dead).</li>
 *   <li><b>Bulk revocation via token-version bump</b> — suspending ({@code UserService.deactivate})
 *       or deleting ({@code UserService.delete}) a user increments the durable per-user
 *       {@code token_version}. Every token minted at the old version is then rejected with 401 on
 *       the next protected call (the filter reloads the user and compares versions), independent of
 *       the per-jti denylist.</li>
 * </ol>
 *
 * <p>The protected probe endpoint is {@code GET /api/v1/users/{id}} called for the caller's OWN id:
 * its guard {@code @PreAuthorize("hasAuthority('user.read') or #id == currentUserId()")} is satisfied
 * by self-reference, so the only thing that can flip the result from 200 to 401 is the filter-level
 * session check — which is exactly what this test isolates. A revoked token surfaces as a 401 written
 * directly by {@code JwtAuthFilter}, never reaching the controller.
 */
class SessionRevocationIT extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Test
    void logout_denylistsTheToken_butAFreshLoginStillWorks() throws Exception {
        User user = seedUser("logout-" + rnd() + "@example.com", "Logout User", false);

        // --- a freshly minted token reads the owner's own record (200) ---
        String token = loginAndGetToken(user.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(user.getId().toString())));

        // --- logout denylists this exact token's jti (idempotent 204) ---
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        // --- the SAME bearer is now rejected on the protected endpoint (401) ---
        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());

        // --- but the user is still ACTIVE: a brand-new login yields a working token ---
        String freshToken = loginAndGetToken(user.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", bearer(freshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(user.getId().toString())));

        // --- and the original logged-out token remains dead ---
        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suspendingUser_bumpsTokenVersion_revokesExistingToken() throws Exception {
        User user = seedUser("suspend-" + rnd() + "@example.com", "Suspend User", false);

        // --- token works before suspension ---
        String token = loginAndGetToken(user.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(user.getId().toString())));

        // --- suspend via UserService: status -> SUSPENDED AND token_version bump ---
        userService.deactivate(user.getId());

        // --- the previously-valid token is now rejected (401) ---
        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());

        // --- a suspended account cannot obtain a fresh token either: login is rejected (401) ---
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(loginJson(user.getEmail(), DEFAULT_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletingUser_bumpsTokenVersion_revokesExistingToken() throws Exception {
        User user = seedUser("delete-" + rnd() + "@example.com", "Delete User", false);

        // --- token works before deletion ---
        String token = loginAndGetToken(user.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(user.getId().toString())));

        // --- soft-delete via UserService: status -> DELETED AND token_version bump ---
        userService.delete(user.getId());

        // --- the previously-valid token is now rejected (401) ---
        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());

        // --- a deleted account cannot log in to obtain a fresh token (401) ---
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(loginJson(user.getEmail(), DEFAULT_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    private String loginJson(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginBody(email, password));
    }

    private record LoginBody(String email, String password) {}
}
