package com.example.cp.apikeys;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ApiKeyService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final ApiKeyRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Org-scoped allow-list constraining which scopes an API key may be created with. This prevents
     * minting keys carrying global/cross-org authorities (e.g. {@code subscription.read},
     * {@code license.issue}, {@code subscription.write}, any {@code *.write}). Sourced from
     * {@code app.api-keys.creatable-scopes}; the default mirrors the design contract.
     */
    private final Set<String> creatableScopes;

    public ApiKeyService(ApiKeyRepository repo,
                         @Value("${app.api-keys.creatable-scopes:usage.ingest,usage.read,license.read}")
                         Set<String> creatableScopes) {
        this.repo = repo;
        this.creatableScopes = creatableScopes == null ? Set.of() : Set.copyOf(creatableScopes);
    }

    public record CreateResult(ApiKey apiKey, String plaintextKey) {}

    @Transactional
    public CreateResult create(UUID orgId, String name, Set<String> scopes) {
        Set<String> requested = scopes == null ? Set.of() : new LinkedHashSet<>(scopes);
        // Constrain creatable scopes to the org-scoped allow-list so an API key can never be minted
        // with a global/cross-org authority (subscription.read, subscription.write, license.issue,
        // license.revoke, apikey.write, any *.write, etc).
        for (String s : requested) {
            if (s == null || !creatableScopes.contains(s)) {
                throw ApiException.badRequest("Scope not permitted for API keys: " + s);
            }
        }
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String plaintext = "cp_" + base32(raw);
        String prefix = plaintext.length() >= 8 ? plaintext.substring(0, 8) : plaintext;
        String hash = sha256Hex(plaintext);
        String scopesJson;
        try {
            scopesJson = mapper.writeValueAsString(requested);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Invalid scopes: " + e.getMessage());
        }
        ApiKey k = ApiKey.builder()
                .id(Ids.newId())
                .orgId(orgId)
                .name(name)
                .keyHash(hash)
                .keyPrefix(prefix)
                .scopesJson(scopesJson)
                .createdAt(OffsetDateTime.now())
                .build();
        ApiKey saved = repo.save(k);
        AuditContext.set("apikey.created");
        AuditContext.setTarget("api_key", saved.getId().toString());
        AuditContext.putPayload("org_id", orgId.toString());
        AuditContext.putPayload("prefix", prefix);
        return new CreateResult(saved, plaintext);
    }

    @Transactional
    public Optional<ApiKey> verify(String rawKey) {
        if (rawKey == null || rawKey.length() < 9) return Optional.empty();
        String prefix = rawKey.substring(0, 8);
        String hash = sha256Hex(rawKey);
        List<ApiKey> candidates = repo.findByKeyPrefix(prefix);
        for (ApiKey k : candidates) {
            if (k.getRevokedAt() != null) continue;
            if (constantTimeEquals(hash, k.getKeyHash())) {
                // Touch last_used_at via a targeted, guarded UPDATE (... WHERE id=? AND revoked_at IS
                // NULL) instead of re-persisting the whole entity. A full-column save would re-write a
                // stale revoked_at=NULL and silently undo a revoke() that committed between our read
                // and write (P1-7 lost update). The conditional UPDATE no-ops (0 rows) when the key was
                // concurrently revoked; in that race we must NOT authenticate the request, so treat a
                // 0-row result as a miss.
                int touched = repo.touchLastUsedIfActive(k.getId(), OffsetDateTime.now());
                if (touched == 0) {
                    return Optional.empty();
                }
                k.setLastUsedAt(OffsetDateTime.now());
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    @Transactional
    public void revoke(UUID orgId, UUID id) {
        // Org-scope the lookup: a key that does not belong to the caller's org is reported as
        // not found (404, not 403) to avoid cross-org existence disclosure. Closes the IDOR where
        // any apikey.write holder could revoke keys in other orgs.
        if (orgId == null) {
            throw ApiException.notFound("API key not found");
        }
        ApiKey k = repo.findById(id).orElseThrow(() -> ApiException.notFound("API key not found"));
        if (!orgId.equals(k.getOrgId())) {
            throw ApiException.notFound("API key not found");
        }
        // Guarded conditional UPDATE (... WHERE id=? AND org_id=? AND revoked_at IS NULL): atomically
        // stamps revoked_at without loading/re-persisting the row, so a concurrent verify() touch
        // cannot clobber it. Already-revoked keys no-op (idempotent). The lookup above is still done
        // first so an unknown/cross-org id deterministically 404s rather than silently succeeding.
        repo.revokeIfActive(id, orgId, OffsetDateTime.now());
        AuditContext.set("apikey.revoked");
        AuditContext.setTarget("api_key", id.toString());
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listForOrg(UUID orgId) {
        return repo.findByOrgId(orgId);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ApiKey> listForOrg(UUID orgId, org.springframework.data.domain.Pageable pageable) {
        return repo.findByOrgId(orgId, pageable);
    }

    public Set<String> parseScopes(ApiKey key) {
        if (key.getScopesJson() == null || key.getScopesJson().isBlank()) return Set.of();
        try {
            return mapper.readValue(key.getScopesJson(), Set.class);
        } catch (Exception e) {
            return Set.of();
        }
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    static String base32(byte[] input) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : input) {
            buffer <<= 8;
            buffer |= value & 0xFF;
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int idx = (buffer >> (bitsLeft - 5)) & 0x1F;
                bitsLeft -= 5;
                sb.append(BASE32_ALPHABET.charAt(idx));
            }
        }
        if (bitsLeft > 0) {
            int idx = (buffer << (5 - bitsLeft)) & 0x1F;
            sb.append(BASE32_ALPHABET.charAt(idx));
        }
        return sb.toString().toLowerCase();
    }
}
