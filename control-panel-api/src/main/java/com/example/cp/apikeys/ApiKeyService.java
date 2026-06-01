package com.example.cp.apikeys;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
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

    public ApiKeyService(ApiKeyRepository repo) {
        this.repo = repo;
    }

    public record CreateResult(ApiKey apiKey, String plaintextKey) {}

    @Transactional
    public CreateResult create(UUID orgId, String name, Set<String> scopes) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String plaintext = "cp_" + base32(raw);
        String prefix = plaintext.length() >= 8 ? plaintext.substring(0, 8) : plaintext;
        String hash = sha256Hex(plaintext);
        String scopesJson;
        try {
            scopesJson = mapper.writeValueAsString(scopes == null ? Set.of() : scopes);
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
                k.setLastUsedAt(OffsetDateTime.now());
                repo.save(k);
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    @Transactional
    public void revoke(UUID id) {
        ApiKey k = repo.findById(id).orElseThrow(() -> ApiException.notFound("API key not found"));
        if (k.getRevokedAt() == null) {
            k.setRevokedAt(OffsetDateTime.now());
            repo.save(k);
        }
        AuditContext.set("apikey.revoked");
        AuditContext.setTarget("api_key", id.toString());
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listForOrg(UUID orgId) {
        return repo.findByOrgId(orgId);
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
