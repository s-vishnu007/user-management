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
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class KeyService {

    private static final Logger log = LoggerFactory.getLogger(KeyService.class);

    private static final String ALGORITHM = "Ed25519";
    private static final int RETENTION_MONTHS = 18;

    /**
     * Every {@code (table, column)} that stores a {@link KeyEncryptor} AES-GCM blob. KEK rotation
     * ({@link #rotateKek()}) re-encrypts EVERY one of these under the active KEK, and the startup
     * drop-guard ({@link #assertNoOrphanedKekReferences()}) scans all of them to refuse a config that
     * would leave any blob undecryptable. A single KEK protects all four categories, so dropping the
     * old KEK after rotating only {@code signing_keys} would permanently orphan MFA/webhook/SSO
     * secrets — this registry is the single source of truth that keeps the four in lock-step.
     *
     * <p>The other packages' columns are touched GENERICALLY via native SQL (no cross-package Java
     * dependency); the column names are validated against the live schema by the audit/verifier.
     */
    static final List<EncryptedColumn> ENCRYPTED_COLUMNS = List.of(
            new EncryptedColumn("signing_keys", "id", "private_key_encrypted", false),
            new EncryptedColumn("user_mfa", "user_id", "secret_enc", false),
            new EncryptedColumn("webhook_subscriptions", "id", "secret_enc", false),
            new EncryptedColumn("sso_providers", "id", "client_secret_enc", true));

    /** A KeyEncryptor-encrypted column: its table, primary-key column, blob column, and nullability. */
    record EncryptedColumn(String table, String pkColumn, String blobColumn, boolean nullable) {}

    private final SigningKeyRepository repo;
    private final KeyEncryptor encryptor;
    private final OutboxPublisher outbox;
    private final JdbcTemplate jdbc;
    private final KeyService self;

    public KeyService(SigningKeyRepository repo, KeyEncryptor encryptor, OutboxPublisher outbox,
                      JdbcTemplate jdbc, @Lazy KeyService self) {
        this.repo = repo;
        this.encryptor = encryptor;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.self = self;
    }

    /**
     * Bootstrap the initial ACTIVE signing key if none exists. Routed through the self-proxy so the
     * inner {@link #generateNewActiveKey()} runs inside its own transaction (a direct call from this
     * {@code @EventListener} would self-invoke and bypass the {@code @Transactional} boundary, so the
     * retire-then-insert would not be atomic and the new partial unique index could surface a bare
     * constraint violation across racing instances rather than a clean retry).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void bootstrap() {
        try {
            if (repo.findFirstByStatusOrderByCreatedAtDesc(SigningKey.Status.ACTIVE).isEmpty()) {
                log.info("No ACTIVE signing key found — generating initial Ed25519 key");
                self.generateNewActiveKey();
            }
        } catch (DataIntegrityViolationException e) {
            // A concurrent bootstrap on another instance won the race and inserted the single ACTIVE
            // key first; the partial unique index ux_signing_keys_single_active rejected ours. That is
            // the desired outcome (exactly one ACTIVE key), so treat it as success.
            log.info("Signing key bootstrap: another instance generated the initial ACTIVE key first");
        } catch (Exception e) {
            log.warn("Signing key bootstrap deferred: {}", e.getMessage());
        }
    }

    /**
     * Startup guard (P1-6): refuse to run with a KEK configuration that has dropped a KEK still
     * referenced by any stored blob. Scans every {@link #ENCRYPTED_COLUMNS} entry and verifies the
     * KEK id embedded in each blob is still configured in {@link KeyEncryptor}. If any blob references
     * a now-missing KEK the application fails fast (rather than silently throwing the first time that
     * secret is needed — at MFA login, webhook delivery, or SSO). Operators must keep the old KEK
     * configured until {@link #rotateKek()} has re-wrapped every column under the new active KEK.
     *
     * <p>Runs after {@link #bootstrap()} (lower {@code @Order} value runs first) so the freshly
     * generated bootstrap key — written under the active KEK — is included in the scan.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void assertNoOrphanedKekReferences() {
        Set<String> configured = encryptor.configuredKekIds();
        List<String> orphans = new ArrayList<>();
        for (EncryptedColumn col : ENCRYPTED_COLUMNS) {
            Set<String> referenced = referencedKekIds(col);
            for (String kekId : referenced) {
                if (!configured.contains(kekId)) {
                    orphans.add(col.table() + "." + col.blobColumn() + " -> KEK '" + kekId + "'");
                }
            }
        }
        if (!orphans.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: stored encrypted blobs reference KEK id(s) that are no longer "
                            + "configured in app.signing.master-keys, so they can never be decrypted. Re-add the "
                            + "missing KEK(s) and run KEK rotation (POST /api/v1/admin/keys/rotate-kek) before "
                            + "removing them. Orphaned references: " + orphans);
        }
        log.info("KEK drop-guard OK: every encrypted blob across {} column(s) references a configured KEK "
                + "(configured KEKs: {})", ENCRYPTED_COLUMNS.size(), configured);
    }

    /** The distinct KEK ids referenced by the (non-null) blobs in one encrypted column. */
    private Set<String> referencedKekIds(EncryptedColumn col) {
        String sql = "SELECT " + col.blobColumn() + " FROM " + col.table()
                + " WHERE " + col.blobColumn() + " IS NOT NULL";
        List<byte[]> blobs = jdbc.query(sql, (rs, rowNum) -> rs.getBytes(1));
        Set<String> ids = new LinkedHashSet<>();
        for (byte[] blob : blobs) {
            if (blob == null || blob.length == 0) {
                continue;
            }
            try {
                ids.add(encryptor.referencedKekId(blob));
            } catch (RuntimeException e) {
                // A too-short/garbage blob is a separate data-corruption issue, not a dropped-KEK
                // problem; the drop-guard only cares about which KEKs are referenced, so skip it here
                // (it will fail loudly at actual decrypt time if/when that secret is needed).
                log.warn("Skipping unparseable blob in {}.{} during KEK drop-guard scan: {}",
                        col.table(), col.blobColumn(), e.getMessage());
            }
        }
        return ids;
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

            // Retire any existing ACTIVE keys, then FLUSH the retire UPDATEs before inserting the new
            // ACTIVE key. Hibernate's default action order executes INSERTs before UPDATEs within a
            // flush, so without this explicit flush the new ACTIVE row would hit the database while the
            // previous ACTIVE row is still ACTIVE — violating the partial unique index
            // ux_signing_keys_single_active. Flushing the retires first guarantees at most one ACTIVE
            // row is ever present mid-transaction.
            OffsetDateTime now = OffsetDateTime.now();
            boolean retiredAny = false;
            for (SigningKey existing : repo.findByStatus(SigningKey.Status.ACTIVE)) {
                existing.setStatus(SigningKey.Status.RETIRED);
                existing.setRetiredAt(now);
                repo.save(existing);
                retiredAny = true;
            }
            if (retiredAny) {
                repo.flush();
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

    /**
     * Flags the key identified by {@code kid} as COMPROMISED. A COMPROMISED key is excluded from
     * {@link #listPublishedKeys()}/{@link #toJwk}, so {@code /.well-known/jwks.json} drops it
     * immediately and offline verifiers stop trusting tokens signed by it at their next JWKS refresh.
     *
     * <p>If the compromised key was the current ACTIVE key, a fresh ACTIVE key is generated so the
     * panel can keep issuing/signing without a gap.
     *
     * @return the newly generated ACTIVE key when the compromised key had been ACTIVE; otherwise empty.
     */
    @Transactional
    public Optional<SigningKey> markCompromised(String kid) {
        SigningKey key = repo.findByKid(kid)
                .orElseThrow(() -> ApiException.notFound("No signing key with kid " + kid));

        if (key.getStatus() == SigningKey.Status.COMPROMISED) {
            // Idempotent: already flagged, nothing further to do (and never re-generate on a repeat call).
            AuditContext.set("key.compromised");
            AuditContext.setTarget("signing_key", kid);
            return Optional.empty();
        }

        boolean wasActive = key.getStatus() == SigningKey.Status.ACTIVE;
        key.setStatus(SigningKey.Status.COMPROMISED);
        if (key.getRetiredAt() == null) {
            key.setRetiredAt(OffsetDateTime.now());
        }
        repo.save(key);

        AuditContext.set("key.compromised");
        AuditContext.setTarget("signing_key", kid);

        try {
            outbox.publish("signing_key", kid, "KeyCompromised",
                    Map.of("kid", kid, "was_active", wasActive));
        } catch (Exception e) {
            log.warn("Failed to publish KeyCompromised outbox event: {}", e.getMessage());
        }

        log.warn("Marked signing key kid={} COMPROMISED (wasActive={}) — excluded from JWKS immediately",
                kid, wasActive);

        if (wasActive) {
            // generateNewActiveKey only retires keys still in ACTIVE status, so the COMPROMISED key
            // we just saved is left untouched; it simply mints and persists a new ACTIVE key.
            return Optional.of(generateNewActiveKey());
        }
        return Optional.empty();
    }

    /**
     * Re-encrypts EVERY {@link KeyEncryptor}-protected secret under the currently-active KEK, across
     * all four categories that share the one KEK: signing keys, TOTP secrets, webhook HMAC secrets,
     * and OIDC client secrets (see {@link #ENCRYPTED_COLUMNS}). For each registered {@code (table,
     * column)} it decrypts each blob (transparently handling legacy unversioned blobs and blobs under
     * older KEKs) and re-encrypts it under the active KEK, so after a single call every blob is tagged
     * with the active KEK id and the old KEK can be safely removed from configuration.
     *
     * <p>The non-{@code keys} columns are re-wrapped GENERICALLY via native SQL (a per-row {@code
     * UPDATE ... SET <col>=? WHERE <pk>=?}), so this method has no compile-time dependency on the
     * mfa/webhooks/sso packages. Rows already encrypted under the active KEK are still re-wrapped
     * (idempotent and cheap). The whole operation is one transaction: a failure rolls every column
     * back, so we never leave a half-rotated state.
     *
     * @return the total number of blob rows re-encrypted across all categories.
     */
    @Transactional
    public int rotateKek() {
        String activeKekId = encryptor.activeKekId();
        int total = 0;
        Map<String, Integer> perColumn = new java.util.LinkedHashMap<>();
        for (EncryptedColumn col : ENCRYPTED_COLUMNS) {
            int n = reEncryptColumn(col);
            perColumn.put(col.table() + "." + col.blobColumn(), n);
            total += n;
        }

        AuditContext.set("key.kek.rotated");
        AuditContext.setTarget("kek", activeKekId);

        try {
            outbox.publish("kek", activeKekId, "KekRotated",
                    Map.of("active_kek_id", activeKekId, "reencrypted_count", total,
                            "reencrypted_by_column", perColumn));
        } catch (Exception e) {
            log.warn("Failed to publish KekRotated outbox event: {}", e.getMessage());
        }

        log.info("Rotated KEK: re-encrypted {} blob(s) under active KEK id='{}' ({})",
                total, activeKekId, perColumn);
        return total;
    }

    /**
     * Re-encrypts every non-null blob in one registered column under the active KEK via native SQL.
     * Reads {@code (pk, blob)} pairs, decrypts + re-encrypts each blob in application code, and writes
     * it back with a targeted single-row UPDATE keyed on the primary key.
     */
    private int reEncryptColumn(EncryptedColumn col) {
        String selectSql = "SELECT " + col.pkColumn() + ", " + col.blobColumn()
                + " FROM " + col.table()
                + " WHERE " + col.blobColumn() + " IS NOT NULL";
        List<Object[]> rows = jdbc.query(selectSql,
                (rs, rowNum) -> new Object[]{rs.getObject(1), rs.getBytes(2)});

        String updateSql = "UPDATE " + col.table()
                + " SET " + col.blobColumn() + " = ?"
                + " WHERE " + col.pkColumn() + " = ?";
        int count = 0;
        for (Object[] row : rows) {
            Object pk = row[0];
            byte[] blob = (byte[]) row[1];
            if (blob == null || blob.length == 0) {
                continue;
            }
            byte[] reEncrypted = encryptor.encrypt(encryptor.decrypt(blob));
            jdbc.update(updateSql, reEncrypted, pk);
            count++;
        }
        return count;
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
     * what we publish at /.well-known/jwks.json. COMPROMISED keys are excluded so a flagged key
     * drops out of the JWKS immediately (offline verifiers stop trusting it at their next refresh).
     */
    @Transactional(readOnly = true)
    public List<SigningKey> listPublishedKeys() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMonths(RETENTION_MONTHS);
        return repo.findPublishable(SigningKey.Status.ACTIVE, SigningKey.Status.RETIRED, cutoff);
    }

    /**
     * Build a Nimbus OKP/Ed25519 JWK for one key row. COMPROMISED keys are never publishable, so a
     * compromised row is rejected here as a defensive backstop in case a caller passes one directly
     * (the JWKS path already filters them out via {@link #listPublishedKeys()}).
     */
    public JWK toJwk(SigningKey row) {
        if (row.getStatus() == SigningKey.Status.COMPROMISED) {
            throw new IllegalStateException("Refusing to publish a COMPROMISED signing key as a JWK: kid=" + row.getKid());
        }
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
        // human-readable timestamp + a RANDOM suffix. Use the last 12 hex chars of the UUID (the random
        // node segment) — NOT the first 8, which in a time-ordered UUIDv7 are the timestamp prefix and
        // collide for keys generated within the same instant (e.g. a compromise replacement minted in the
        // same second as the bootstrap key), violating the unique kid constraint.
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
        String hex = Ids.newId().toString().replace("-", "");
        String shortId = hex.substring(hex.length() - 12);
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
