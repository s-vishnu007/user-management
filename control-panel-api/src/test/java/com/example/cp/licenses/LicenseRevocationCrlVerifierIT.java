package com.example.cp.licenses;

import com.example.cp.keys.JwsSigner;
import com.example.cp.keys.KeyService;
import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import com.example.licenseverifier.CrlVerifier;
import com.example.licenseverifier.License;
import com.example.licenseverifier.LicenseVerifier;
import com.example.licenseverifier.PublicKeyProvider;
import com.example.licenseverifier.RevocationChecker;
import com.example.licenseverifier.RevocationList;
import com.example.licenseverifier.exceptions.LicenseExpiredException;
import com.example.licenseverifier.exceptions.LicenseRevokedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-module integration/contract test spanning the control-panel license endpoints, the signed
 * CRL artifact, the published JWKS, and the standalone {@code license-verifier} library.
 *
 * <p>The flow it exercises end-to-end:
 * <ol>
 *   <li>Issue a real EdDSA license for a seeded ACTIVE subscription via
 *       {@code POST /api/v1/subscriptions/{subId}/licenses}.</li>
 *   <li>Verify the issued license is accepted by a {@link LicenseVerifier} configured with the
 *       panel's published JWKS ({@code /.well-known/jwks.json}) and a CRL-backed
 *       {@link RevocationChecker} built from the panel's signed CRL.</li>
 *   <li>Revoke the license via {@code POST /api/v1/licenses/{jti}/revoke}.</li>
 *   <li>Fetch {@code GET /api/v1/licenses/crl}; assert it is a compact EdDSA {@code crl+jwt} JWS
 *       that verifies against the same JWKS (via {@link CrlVerifier}) and now lists the revoked
 *       jti, with the contracted issuer / nextUpdate claims.</li>
 *   <li>Re-build the {@link RevocationChecker} from the fresh CRL and assert the verifier now
 *       rejects the (otherwise still-valid, unexpired) revoked license with
 *       {@link LicenseRevokedException}.</li>
 *   <li>Assert that a license carrying no {@code exp} claim — hand-signed with the panel's active
 *       key so the signature/issuer/audience are all valid — is rejected by the verifier
 *       ({@link LicenseExpiredException} for the mandatory-exp hardening).</li>
 * </ol>
 *
 * <p>The CRL endpoint is {@code permitAll}; issuance/revocation are driven as a super-admin (the
 * single global bypass in {@code TenantAccessChecker}) since this test targets the license/CRL/
 * verifier cross-module contract rather than tenant isolation (covered elsewhere).
 */
class LicenseRevocationCrlVerifierIT extends AbstractIntegrationTest {

    @Autowired private JwsSigner jwsSigner;
    @Autowired private KeyService keyService;

    @Value("${app.signing.issuer}") private String issuer;
    @Value("${app.signing.default-audience}") private String defaultAudience;

    @Test
    void issue_revoke_crl_flow_and_verifierRejectsRevokedAndMissingExp() throws Exception {
        // --- seed tenant + ACTIVE subscription on a fresh plan with a generous TTL ---
        Organization org = seedOrg("Acme " + rnd());
        Plan plan = seedNewPlan("crlplan-" + rnd(), 365);
        Subscription sub = seedSubscription(org.getId(), plan.getId());

        // A super-admin drives issuance + revocation (global bypass in TenantAccessChecker).
        User superAdmin = seedUser("crl-superadmin-" + rnd() + "@example.com", "CRL Super", true);

        // --- 1. issue a real license for the subscription ---
        MvcResult issueResult = mockMvc.perform(
                        post("/api/v1/subscriptions/{subId}/licenses", sub.getId())
                                .with(asSuperAdmin(superAdmin))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode issueBody = objectMapper.readTree(issueResult.getResponse().getContentAsString());
        String jti = issueBody.get("jti").asText();
        String licenseJwt = issueBody.get("license").asText();
        assertThat(jti).startsWith("lic_");
        assertThat(licenseJwt).isNotBlank();

        // --- build a verifier against the panel's published JWKS ---
        PublicKeyProvider keyProvider = fetchPublicKeyProvider();

        // Before revocation the CRL-backed checker must accept the freshly-issued license.
        LicenseVerifier verifierBeforeRevoke = LicenseVerifier.builder()
                .publicKeys(keyProvider)
                .issuer(issuer)
                .audience(defaultAudience)
                .revocationChecker(crlBackedChecker(keyProvider))
                .build();

        License verified = verifierBeforeRevoke.verify(licenseJwt);
        assertThat(verified.jti()).isEqualTo(jti);
        assertThat(verified.expiresAt()).isNotNull();
        assertThat(verified.getAudience()).contains(defaultAudience);

        // Sanity: the CRL must NOT list this jti yet.
        assertThat(fetchAndVerifyCrl(keyProvider).isRevoked(jti)).isFalse();

        // --- 2. revoke the license ---
        mockMvc.perform(post("/api/v1/licenses/{jti}/revoke", jti)
                        .with(asSuperAdmin(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"compromised\"}"))
                .andExpect(status().isNoContent());

        // --- 3 + 4. fetch the CRL, assert it is a valid EdDSA crl+jwt JWS listing the revoked jti ---
        MvcResult crlResult = mockMvc.perform(get("/api/v1/licenses/crl"))
                .andExpect(status().isOk())
                .andReturn();

        String crlContentType = crlResult.getResponse().getContentType();
        assertThat(crlContentType).isNotNull();
        assertThat(crlContentType).contains("application/jwt");

        String crlJws = crlResult.getResponse().getContentAsString();

        // Raw header inspection: compact JWS (3 dot-parts), EdDSA, typ=crl+jwt, active kid.
        SignedJWT parsedCrl = SignedJWT.parse(crlJws);
        JWSHeader crlHeader = parsedCrl.getHeader();
        assertThat(crlHeader.getAlgorithm()).isEqualTo(JWSAlgorithm.EdDSA);
        assertThat(crlHeader.getType()).isNotNull();
        assertThat(crlHeader.getType().getType()).isEqualTo("crl+jwt");
        String activeKid = keyService.getActiveSigningKeyPair().kid();
        assertThat(crlHeader.getKeyID()).isEqualTo(activeKid);

        // Cryptographic verification + claims via the library CrlVerifier (verifies against JWKS).
        RevocationList crl = new CrlVerifier(keyProvider, issuer).verify(crlJws);
        assertThat(crl.issuer()).isEqualTo(issuer);
        assertThat(crl.nextUpdate()).isNotNull();
        assertThat(crl.issuedAt()).isNotNull();
        // nextUpdate must be after issuedAt (iat + crl-ttl).
        assertThat(crl.nextUpdate()).isAfter(crl.issuedAt());
        // The revoked jti now appears in the signed list.
        assertThat(crl.revokedJtis()).contains(jti);
        assertThat(crl.isRevoked(jti)).isTrue();

        // --- 5. verifier with a CRL-backed RevocationChecker rejects the revoked license ---
        RevocationChecker revokedChecker = crlBackedChecker(keyProvider);
        assertThat(revokedChecker.isOperational()).isTrue();
        assertThat(revokedChecker.isRevoked(jti)).isTrue();

        LicenseVerifier verifierAfterRevoke = LicenseVerifier.builder()
                .publicKeys(keyProvider)
                .issuer(issuer)
                .audience(defaultAudience)
                .revocationChecker(revokedChecker)
                .build();

        assertThatThrownBy(() -> verifierAfterRevoke.verify(licenseJwt))
                .isInstanceOf(LicenseRevokedException.class)
                .satisfies(ex -> assertThat(((LicenseRevokedException) ex).getJti()).isEqualTo(jti));

        // --- 6. a license with NO exp claim is rejected (mandatory-exp hardening) ---
        String noExpJwt = signLicenseWithoutExp();
        LicenseVerifier strictVerifier = LicenseVerifier.builder()
                .publicKeys(keyProvider)
                .issuer(issuer)
                .audience(defaultAudience)
                .build();

        assertThatThrownBy(() -> strictVerifier.verify(noExpJwt))
                .isInstanceOf(LicenseExpiredException.class);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Fetches /.well-known/jwks.json from the running app and parses it into a verifier provider. */
    private PublicKeyProvider fetchPublicKeyProvider() throws Exception {
        MvcResult jwksResult = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn();
        byte[] jwksBytes = jwksResult.getResponse().getContentAsString()
                .getBytes(StandardCharsets.UTF_8);
        return PublicKeyProvider.fromJwks(new ByteArrayInputStream(jwksBytes));
    }

    /** Fetches the signed CRL and parses/verifies it into a {@link RevocationList}. */
    private RevocationList fetchAndVerifyCrl(PublicKeyProvider keyProvider) throws Exception {
        String crlJws = mockMvc.perform(get("/api/v1/licenses/crl"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new CrlVerifier(keyProvider, issuer).verify(crlJws);
    }

    /**
     * Builds a {@link RevocationChecker} backed by the panel's current signed CRL (fetched +
     * cryptographically verified against the supplied JWKS), mirroring what the Spring Boot starter
     * does at runtime: revoked == listed in the CRL, operational == CRL present and not stale.
     */
    private RevocationChecker crlBackedChecker(PublicKeyProvider keyProvider) throws Exception {
        RevocationList list = fetchAndVerifyCrl(keyProvider);
        return new RevocationChecker() {
            @Override
            public boolean isRevoked(String jti) {
                return list.isRevoked(jti);
            }

            @Override
            public boolean isOperational() {
                return !list.isStale(Instant.now(), java.time.Duration.ofHours(1));
            }
        };
    }

    /**
     * Hand-signs a license JWT with the panel's ACTIVE key carrying a valid issuer / audience / jti
     * but deliberately NO {@code exp} claim, so the only verification failure is the mandatory-exp
     * rule. Reuses {@link JwsSigner} (typ=license+jwt) so the signature + kid are genuinely valid.
     */
    private String signLicenseWithoutExp() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(List.of(defaultAudience))
                .subject("no-exp-subject")
                .jwtID("lic_noexp_" + rnd())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                // no expirationTime on purpose
                .claim("plan", "test")
                .claim("seats", 1)
                .build();
        return jwsSigner.sign(claims, "license+jwt");
    }
}
