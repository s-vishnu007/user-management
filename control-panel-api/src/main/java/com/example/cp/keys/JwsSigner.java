package com.example.cp.keys;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.security.interfaces.EdECPrivateKey;

/**
 * Reusable Ed25519 JWS signer extracted from {@code LicenseIssuer.signJwt} so both license
 * issuance and CRL signing sign with the active key without duplicating the OctetKeyPair /
 * raw-byte extraction logic.
 */
@Component
public class JwsSigner {

    private final KeyService keyService;

    public JwsSigner(KeyService keyService) {
        this.keyService = keyService;
    }

    /**
     * Sign the given claims with the supplied active key, producing a compact EdDSA JWS whose
     * header carries the active {@code kid} and the requested {@code typ}.
     */
    public String sign(JWTClaimsSet claims, String typ, KeyService.ActiveKey active) {
        try {
            byte[] rawPub = KeyService.extractRawEd25519PublicBytes(active.publicKey());
            byte[] rawPriv = extractRawEd25519PrivateBytes(active.privateKey());

            OctetKeyPair okp = new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(rawPub))
                    .d(Base64URL.encode(rawPriv))
                    .keyID(active.kid())
                    .algorithm(JWSAlgorithm.EdDSA)
                    .build();

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                    .keyID(active.kid())
                    .type(new JOSEObjectType(typ))
                    .build();

            SignedJWT signedJwt = new SignedJWT(header, claims);
            signedJwt.sign(new Ed25519Signer(okp));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign " + typ, e);
        }
    }

    /**
     * Convenience overload that resolves the active signing key internally — used by callers
     * (e.g. CrlController) that do not already hold an {@link KeyService.ActiveKey}.
     */
    public String sign(JWTClaimsSet claims, String typ) {
        return sign(claims, typ, keyService.getActiveSigningKeyPair());
    }

    /**
     * Java's PKCS8-encoded Ed25519 private key wraps a 32-byte CurvePrivateKey OCTET STRING.
     * The standard SunEC PKCS8 layout is 48 bytes total: header (16) + OCTET STRING (2 header + 32 key).
     * We look for a 0x04 0x20 marker and take the next 32 bytes.
     */
    static byte[] extractRawEd25519PrivateBytes(java.security.PrivateKey priv) {
        byte[] enc = priv.getEncoded();
        // search for the inner CurvePrivateKey marker: 0x04 0x20 followed by 32 bytes
        for (int i = 0; i + 33 < enc.length; i++) {
            if (enc[i] == 0x04 && enc[i + 1] == 0x20 && i + 2 + 32 <= enc.length) {
                byte[] raw = new byte[32];
                System.arraycopy(enc, i + 2, raw, 0, 32);
                return raw;
            }
        }
        if (priv instanceof EdECPrivateKey ed) {
            return ed.getBytes().orElseThrow(() ->
                    new IllegalStateException("EdECPrivateKey has no exportable raw bytes")
            );
        }
        throw new IllegalStateException("Could not extract raw Ed25519 private key bytes");
    }
}
