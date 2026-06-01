package com.example.licenseverifier;

import com.example.licenseverifier.exceptions.LicenseAudienceMismatchException;
import com.example.licenseverifier.exceptions.LicenseExpiredException;
import com.example.licenseverifier.exceptions.LicenseFileMalformedException;
import com.example.licenseverifier.exceptions.LicenseIssuerMismatchException;
import com.example.licenseverifier.exceptions.LicenseKidUnknownException;
import com.example.licenseverifier.exceptions.LicenseNotYetValidException;
import com.example.licenseverifier.exceptions.LicenseSignatureInvalidException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class LicenseVerifier {

    private static final String EXPECTED_TYP = "license+jwt";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PublicKeyProvider keyProvider;
    private final String audience;
    private final String issuer;
    private final Duration clockSkew;
    private final Clock clock;

    private LicenseVerifier(Builder b) {
        this.keyProvider = Objects.requireNonNull(b.keyProvider, "publicKeys must be configured");
        this.audience = Objects.requireNonNull(b.audience, "audience must be configured");
        this.issuer = b.issuer;
        this.clockSkew = b.clockSkew != null ? b.clockSkew : Duration.ZERO;
        this.clock = b.clock != null ? b.clock : Clock.systemUTC();
    }

    public static Builder builder() {
        return new Builder();
    }

    public License verify(String licenseFileContent) {
        if (licenseFileContent == null || licenseFileContent.isBlank()) {
            throw new LicenseFileMalformedException("License content is empty");
        }
        String jwt = extractJwt(licenseFileContent.trim());
        return verifyJwt(jwt);
    }

    public License verify(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return verify(content);
        } catch (IOException e) {
            throw new LicenseFileMalformedException("Failed to read license file: " + path, e);
        }
    }

    private String extractJwt(String content) {
        if (content.startsWith("{")) {
            try {
                LicenseEnvelope envelope = MAPPER.readValue(content, LicenseEnvelope.class);
                if (envelope.license() == null || envelope.license().isBlank()) {
                    throw new LicenseFileMalformedException("License envelope is missing 'license' field");
                }
                return envelope.license().trim();
            } catch (LicenseFileMalformedException e) {
                throw e;
            } catch (IOException e) {
                throw new LicenseFileMalformedException("Invalid license envelope JSON", e);
            }
        }
        return content;
    }

    private License verifyJwt(String jwt) {
        SignedJWT signedJwt;
        try {
            signedJwt = SignedJWT.parse(jwt);
        } catch (ParseException e) {
            throw new LicenseFileMalformedException("License JWT is not a valid JWS token", e);
        }

        JWSHeader header = signedJwt.getHeader();
        if (header.getAlgorithm() == null || !JWSAlgorithm.EdDSA.equals(header.getAlgorithm())) {
            throw new LicenseSignatureInvalidException(
                    "Unsupported JWS algorithm: " + header.getAlgorithm() + " (expected EdDSA)");
        }
        if (header.getType() != null && !EXPECTED_TYP.equalsIgnoreCase(header.getType().getType())) {
            throw new LicenseFileMalformedException(
                    "Unexpected JWT typ: " + header.getType().getType() + " (expected " + EXPECTED_TYP + ")");
        }
        String kid = header.getKeyID();
        if (kid == null || kid.isBlank()) {
            throw new LicenseFileMalformedException("License JWT header is missing 'kid'");
        }

        JWSVerifier verifier = resolveVerifier(kid);

        try {
            if (!signedJwt.verify(verifier)) {
                throw new LicenseSignatureInvalidException("License JWT signature is invalid");
            }
        } catch (LicenseSignatureInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new LicenseSignatureInvalidException("Failed to verify license JWT signature", e);
        }

        JWTClaimsSet claims;
        try {
            claims = signedJwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new LicenseFileMalformedException("Failed to parse JWT claims", e);
        }

        validateTemporalClaims(claims);
        validateAudience(claims);
        validateIssuer(claims);

        return toLicense(claims, kid);
    }

    private JWSVerifier resolveVerifier(String kid) {
        // Preferred path: built-in providers expose the original JWK so we get the exact raw
        // Ed25519 encoding from the JWKS without going through PublicKey.
        if (keyProvider instanceof PublicKeyProvider.JwkProvider jwkProvider) {
            Optional<JWK> jwk = jwkProvider.findJwkByKid(kid);
            if (jwk.isPresent()) {
                if (jwk.get() instanceof OctetKeyPair okp && Curve.Ed25519.equals(okp.getCurve())) {
                    try {
                        return new Ed25519Verifier(okp);
                    } catch (Exception e) {
                        throw new LicenseSignatureInvalidException(
                                "Failed to build Ed25519 verifier for kid '" + kid + "'", e);
                    }
                }
                throw new LicenseSignatureInvalidException(
                        "JWKS entry for kid '" + kid + "' is not an Ed25519 OctetKeyPair");
            }
            throw new LicenseKidUnknownException(kid, keyProvider.knownKids());
        }

        // Custom provider path: only PublicKey is available, reconstruct OctetKeyPair.
        PublicKey publicKey = keyProvider.findByKid(kid)
                .orElseThrow(() -> new LicenseKidUnknownException(kid, keyProvider.knownKids()));
        if (!(publicKey instanceof EdECPublicKey)) {
            throw new LicenseSignatureInvalidException(
                    "Public key for kid '" + kid + "' is not an Ed25519 key: " + publicKey.getClass().getName());
        }
        try {
            OctetKeyPair okp = new OctetKeyPair.Builder(Curve.Ed25519, encodeEd25519PublicKey(publicKey))
                    .build();
            return new Ed25519Verifier(okp);
        } catch (Exception e) {
            throw new LicenseSignatureInvalidException(
                    "Failed to build Ed25519 verifier for kid '" + kid + "'", e);
        }
    }

    private static Base64URL encodeEd25519PublicKey(PublicKey publicKey) {
        // JDK X.509 SubjectPublicKeyInfo for Ed25519 is always 44 bytes:
        //   12-byte ASN.1 header { 30 2A 30 05 06 03 2B 65 70 03 21 00 } || 32 raw key bytes
        // The final 32 bytes are the raw Ed25519 public key per RFC 8032.
        byte[] encoded = publicKey.getEncoded();
        if (encoded == null || encoded.length < 32) {
            throw new LicenseSignatureInvalidException(
                    "Ed25519 public key encoding is too short: " + (encoded == null ? 0 : encoded.length));
        }
        byte[] raw = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        return Base64URL.encode(raw);
    }

    private void validateTemporalClaims(JWTClaimsSet claims) {
        Instant now = Instant.now(clock);
        Date exp = claims.getExpirationTime();
        if (exp != null) {
            Instant expInstant = exp.toInstant();
            if (now.isAfter(expInstant.plus(clockSkew))) {
                throw new LicenseExpiredException(
                        "License expired at " + expInstant, expInstant);
            }
        }
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null) {
            Instant nbfInstant = nbf.toInstant();
            if (now.isBefore(nbfInstant.minus(clockSkew))) {
                throw new LicenseNotYetValidException(
                        "License not valid before " + nbfInstant, nbfInstant);
            }
        }
    }

    private void validateAudience(JWTClaimsSet claims) {
        List<String> tokenAudience = claims.getAudience();
        if (tokenAudience == null || !tokenAudience.contains(audience)) {
            throw new LicenseAudienceMismatchException(audience,
                    tokenAudience == null ? Collections.emptyList() : tokenAudience);
        }
    }

    private void validateIssuer(JWTClaimsSet claims) {
        if (issuer == null) {
            return;
        }
        String actual = claims.getIssuer();
        if (!issuer.equals(actual)) {
            throw new LicenseIssuerMismatchException(issuer, actual);
        }
    }

    @SuppressWarnings("unchecked")
    private License toLicense(JWTClaimsSet claims, String kid) {
        try {
            List<String> permissions = (List<String>) claims.getClaim("permissions");
            Set<String> permSet = permissions == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new HashSet<>(permissions));

            Map<String, Object> features = (Map<String, Object>) claims.getClaim("features");
            Map<String, Object> featuresCopy = features == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(features));

            License.Customer customer = null;
            Object rawCustomer = claims.getClaim("customer");
            if (rawCustomer instanceof Map<?, ?> map) {
                Object orgName = map.get("org_name");
                Object contactEmail = map.get("contact_email");
                customer = new License.Customer(
                        orgName == null ? null : orgName.toString(),
                        contactEmail == null ? null : contactEmail.toString());
            }

            Integer seats = optionalInt(claims, "seats");
            Integer version = optionalInt(claims, "version");

            return License.builder()
                    .jti(claims.getJWTID())
                    .issuer(claims.getIssuer())
                    .subject(claims.getSubject())
                    .subscriptionId(stringClaim(claims, "subscription_id"))
                    .plan(stringClaim(claims, "plan"))
                    .audience(claims.getAudience() == null
                            ? Collections.emptyList()
                            : List.copyOf(claims.getAudience()))
                    .permissions(permSet)
                    .features(featuresCopy)
                    .seats(seats == null ? 0 : seats)
                    .issuedAt(claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant())
                    .expiresAt(claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant())
                    .notBefore(claims.getNotBeforeTime() == null ? null : claims.getNotBeforeTime().toInstant())
                    .customer(customer)
                    .version(version == null ? 0 : version)
                    .kid(kid)
                    .build();
        } catch (ClassCastException e) {
            throw new LicenseFileMalformedException("License claims have unexpected types", e);
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object v = claims.getClaim(name);
        return v == null ? null : v.toString();
    }

    private static Integer optionalInt(JWTClaimsSet claims, String name) {
        Object v = claims.getClaim(name);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static final class Builder {

        private PublicKeyProvider keyProvider;
        private String audience;
        private String issuer;
        private Duration clockSkew;
        private Clock clock;

        private Builder() {
        }

        public Builder publicKeys(PublicKeyProvider provider) {
            this.keyProvider = Objects.requireNonNull(provider, "provider");
            return this;
        }

        public Builder publicKeysFromClasspath(String resource) {
            Objects.requireNonNull(resource, "resource");
            InputStream stream = LicenseVerifier.class.getResourceAsStream(resource);
            if (stream == null) {
                stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(
                        resource.startsWith("/") ? resource.substring(1) : resource);
            }
            if (stream == null) {
                throw new LicenseFileMalformedException("Classpath resource not found: " + resource);
            }
            try (InputStream in = stream) {
                this.keyProvider = PublicKeyProvider.fromJwks(in);
            } catch (IOException e) {
                throw new LicenseFileMalformedException("Failed to load JWKS from classpath: " + resource, e);
            }
            return this;
        }

        public Builder publicKeysFromUrl(URL url, Duration refreshInterval) {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(refreshInterval, "refreshInterval");
            this.keyProvider = PublicKeyProvider.fromJwksUrl(url, refreshInterval);
            return this;
        }

        public Builder publicKeysFromUrl(URL url) {
            return publicKeysFromUrl(url, Duration.ofHours(24));
        }

        public Builder audience(String audience) {
            this.audience = Objects.requireNonNull(audience, "audience");
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder clockSkew(Duration clockSkew) {
            this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public LicenseVerifier build() {
            return new LicenseVerifier(this);
        }
    }
}
