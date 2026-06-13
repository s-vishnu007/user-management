package com.example.cp.auth;

import com.example.cp.common.ApiException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SessionTokenService {

    public static final String ISSUER = "control-panel";

    /**
     * Marks a token as a full session token. Set on issue and REQUIRED on parse so that another
     * HS256 token signed with the same secret but a different purpose (e.g. the
     * {@code mfa_challenge} minted by {@code MfaService}) can never be replayed as a session.
     */
    public static final String PURPOSE = "session";
    private static final String PURPOSE_CLAIM = "purpose";

    private final String secret;
    private final Duration ttl;

    public SessionTokenService(
            @Value("${app.auth.session-secret:}") String secret,
            @Value("${app.auth.session-ttl:PT30M}") Duration ttl) {
        this.secret = secret == null ? "" : secret;
        this.ttl = ttl == null ? Duration.ofMinutes(30) : ttl;
    }

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.auth.session-secret (env APP_AUTH_SESSION_SECRET) is required and must be at least 32 chars");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.auth.session-secret must be at least 32 bytes for HS256");
        }
    }

    public IssuedToken issue(UUID userId, String email, boolean superAdmin, Collection<String> authorities,
                             long tokenVersion) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        try {
            String authoritiesCompact = authorities == null ? "" : String.join(",", authorities);
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(userId.toString())
                    .claim("email", email)
                    .claim(PURPOSE_CLAIM, PURPOSE)
                    .claim("super_admin", superAdmin)
                    .claim("authorities", authoritiesCompact)
                    .claim("tv", tokenVersion)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .jwtID(UUID.randomUUID().toString())
                    .build();

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return new IssuedToken(jwt.serialize(), exp);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign session JWT", e);
        }
    }

    public ParsedToken parse(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.unauthorized("Missing token");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(secret.getBytes(StandardCharsets.UTF_8));
            if (!jwt.verify(verifier)) {
                throw ApiException.unauthorized("Invalid token signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            // Reject anything that is not a genuine session token signed by us: an mfa_challenge (or
            // any other purpose) token shares the secret but carries purpose!=session and no issuer,
            // so it can never be accepted here as a Bearer/cookie session.
            if (!ISSUER.equals(claims.getIssuer())) {
                throw ApiException.unauthorized("Invalid token issuer");
            }
            if (!PURPOSE.equals(claims.getStringClaim(PURPOSE_CLAIM))) {
                throw ApiException.unauthorized("Invalid token purpose");
            }
            Date exp = claims.getExpirationTime();
            if (exp != null && exp.toInstant().isBefore(Instant.now())) {
                throw ApiException.unauthorized("Token expired");
            }
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.getStringClaim("email");
            Boolean superAdminObj = claims.getBooleanClaim("super_admin");
            boolean superAdmin = superAdminObj != null && superAdminObj;
            String authoritiesCompact = claims.getStringClaim("authorities");
            Set<String> authorities = new LinkedHashSet<>();
            if (authoritiesCompact != null && !authoritiesCompact.isBlank()) {
                authorities.addAll(List.of(authoritiesCompact.split(",")));
            }
            String jti = claims.getJWTID();
            Long tvObj = claims.getLongClaim("tv");
            long tv = tvObj == null ? 0L : tvObj;
            return new ParsedToken(userId, email, superAdmin, authorities,
                    exp == null ? null : exp.toInstant(), jti, tv);
        } catch (ParseException | JOSEException e) {
            throw ApiException.unauthorized("Invalid token");
        }
    }

    public Duration ttl() {
        return ttl;
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    public record ParsedToken(UUID userId, String email, boolean superAdmin, Set<String> authorities,
                              Instant expiresAt, String jti, long tokenVersion) {}
}
