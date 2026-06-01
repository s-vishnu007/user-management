package com.example.cp.keys;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.subscriptions.OutboxPublisher;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class KeyService {

    private static final Logger log = LoggerFactory.getLogger(KeyService.class);

    private static final String ALGORITHM = "Ed25519";
    private static final int RETENTION_MONTHS = 18;

    private final SigningKeyRepository repo;
    private final KeyEncryptor encryptor;
    private final OutboxPublisher outbox;

    public KeyService(SigningKeyRepository repo, KeyEncryptor encryptor, OutboxPublisher outbox) {
        this.repo = repo;
        this.encryptor = encryptor;
        this.outbox = outbox;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            if (repo.findFirstByStatusOrderByCreatedAtDesc(SigningKey.Status.ACTIVE).isEmpty()) {
                log.info("No ACTIVE signing key found — generating initial Ed25519 key");
                generateNewActiveKey();
            }
        } catch (Exception e) {
            log.warn("Signing key bootstrap deferred: {}", e.getMessage());
        }
    }

    @Transactional
    public SigningKey generateNewActiveKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(ALGORITHM);
            KeyPair pair = gen.generateKeyPair();
            PublicKey pub = pair.getPublic();
            PrivateKey priv = pair.getPrivate();

            String kid = generateKid();
            String pem = toPublicKeyPem(pub);
            byte[] encryptedPriv = encryptor.encrypt(priv.getEncoded());

            // Retire any existing ACTIVE keys
            OffsetDateTime now = OffsetDateTime.now();
            for (SigningKey existing : repo.findByStatus(SigningKey.Status.ACTIVE)) {
                existing.setStatus(SigningKey.Status.RETIRED);
                existing.setRetiredAt(now);
                repo.save(existing);
            }

            SigningKey k = SigningKey.builder()
                    .id(Ids.newId())
                    .kid(kid)
                    .algorithm(ALGORITHM)
                    .publicKeyPem(pem)
                    .privateKeyEncrypted(encryptedPriv)
                    .status(SigningKey.Status.ACTIVE)
                    .createdAt(now)
                    .build();
            SigningKey saved = repo.save(k);

            AuditContext.set("key.rotated");
            AuditContext.setTarget("signing_key", saved.getKid());

            try {
                outbox.publish("signing_key", saved.getKid(), "KeyRotated",
                        Map.of("kid", saved.getKid(), "algorithm", ALGORITHM, "created_at", now.toString()));
            } catch (Exception e) {
                log.warn("Failed to publish KeyRotated outbox event: {}", e.getMessage());
            }

            log.info("Generated new active Ed25519 signing key kid={}", kid);
            return saved;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Ed25519 key", e);
        }
    }

    @Transactional
    public SigningKey rotate() {
        return generateNewActiveKey();
    }

    @Transactional(readOnly = true)
    public ActiveKey getActiveSigningKeyPair() {
        SigningKey row = repo.findFirstByStatusOrderByCreatedAtDesc(SigningKey.Status.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("No active signing key available"));
        try {
            byte[] privBytes = encryptor.decrypt(row.getPrivateKeyEncrypted());
            KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            PublicKey pub = parsePublicKeyPem(row.getPublicKeyPem());
            return new ActiveKey(row.getKid(), priv, pub);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load active signing key", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<PublicKey> getPublicKey(String kid) {
        return repo.findByKid(kid).map(row -> {
            try {
                return parsePublicKeyPem(row.getPublicKeyPem());
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse public key for kid " + kid, e);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<SigningKey> listAll() {
        return repo.findAll();
    }

    /**
     * Returns ACTIVE plus RETIRED keys retired within the retention window — these are
     * what we publish at /.well-known/jwks.json.
     */
    @Transactional(readOnly = true)
    public List<SigningKey> listPublishedKeys() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMonths(RETENTION_MONTHS);
        return repo.findByStatusOrRetiredAtAfter(SigningKey.Status.ACTIVE, cutoff);
    }

    /**
     * Build a Nimbus OKP/Ed25519 JWK for one key row.
     */
    public JWK toJwk(SigningKey row) {
        try {
            PublicKey pub = parsePublicKeyPem(row.getPublicKeyPem());
            byte[] rawPub = extractRawEd25519PublicBytes(pub);
            OctetKeyPair okp = new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(rawPub))
                    .keyID(row.getKid())
                    .algorithm(com.nimbusds.jose.JWSAlgorithm.EdDSA)
                    .keyUse(KeyUse.SIGNATURE)
                    .build();
            return okp;
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert signing key to JWK kid=" + row.getKid(), e);
        }
    }

    static PublicKey parsePublicKeyPem(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(body);
        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        return kf.generatePublic(new X509EncodedKeySpec(der));
    }

    static String toPublicKeyPem(PublicKey pub) {
        byte[] der = pub.getEncoded();
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END PUBLIC KEY-----\n");
        return sb.toString();
    }

    /**
     * X.509 SubjectPublicKeyInfo for Ed25519 prepends a 12-byte algorithm header before the
     * 32-byte raw public key. The JCA EdECPublicKey exposes the point Y-coordinate; the
     * easiest correct path is to strip the SPKI prefix from the encoded form.
     */
    public static byte[] extractRawEd25519PublicBytes(PublicKey pub) {
        byte[] spki = pub.getEncoded();
        // SPKI for Ed25519 is exactly 44 bytes: 12-byte header + 32-byte raw key.
        if (spki.length == 44) {
            byte[] raw = new byte[32];
            System.arraycopy(spki, 12, raw, 0, 32);
            return raw;
        }
        // Defensive fallback for any oddly-encoded providers: try the EdEC interface.
        if (pub instanceof EdECPublicKey ed) {
            byte[] coord = ed.getPoint().getY().toByteArray();
            // BigInteger may add a leading sign byte; trim/pad to 32 bytes.
            byte[] raw = new byte[32];
            int srcOffset = Math.max(0, coord.length - 32);
            int copyLen = Math.min(coord.length, 32);
            System.arraycopy(coord, srcOffset, raw, 32 - copyLen, copyLen);
            // Apply x-coordinate sign bit per RFC 8032 §5.1.2 if present
            if (ed.getPoint().isXOdd()) {
                raw[31] |= (byte) 0x80;
            }
            return raw;
        }
        throw new IllegalStateException("Unexpected Ed25519 public key encoding, length=" + spki.length);
    }

    private static String generateKid() {
        // human-readable timestamp + short id
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
        String shortId = Ids.newId().toString().substring(0, 8);
        return "key-" + stamp + "-" + shortId;
    }

    public record ActiveKey(String kid, PrivateKey privateKey, PublicKey publicKey) {}

    @Transactional(readOnly = true)
    public List<SigningKeyView> listForAdmin() {
        List<SigningKey> all = listAll();
        List<SigningKeyView> out = new ArrayList<>(all.size());
        for (SigningKey k : all) {
            out.add(new SigningKeyView(
                    k.getId(), k.getKid(), k.getAlgorithm(),
                    k.getStatus().name(), k.getCreatedAt(), k.getRetiredAt(),
                    k.getPublicKeyPem()
            ));
        }
        return out;
    }

    public record SigningKeyView(
            java.util.UUID id,
            String kid,
            String algorithm,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime retiredAt,
            String publicKeyPem
    ) {}
}
