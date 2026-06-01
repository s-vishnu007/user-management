package com.example.licenseverifier;

import lombok.Builder;
import lombok.Value;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Value
@Builder
public class License {

    String jti;
    String issuer;
    String subject;
    String subscriptionId;
    String plan;
    @Builder.Default
    List<String> audience = Collections.emptyList();
    @Builder.Default
    Set<String> permissions = Collections.emptySet();
    @Builder.Default
    Map<String, Object> features = Collections.emptyMap();
    int seats;
    Instant issuedAt;
    Instant expiresAt;
    Instant notBefore;
    Customer customer;
    int version;
    String kid;

    public boolean hasPermission(String code) {
        return permissions != null && permissions.contains(code);
    }

    public String plan() { return plan; }
    public String jti() { return jti; }
    public String keyId() { return kid; }
    public Instant expiresAt() { return expiresAt; }
    public Set<String> permissions() { return permissions; }
    public Map<String, Object> features() { return features; }

    public <T> T feature(String key, Class<T> type) {
        if (features == null) {
            return null;
        }
        Object value = features.get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (type == Integer.class && value instanceof Number n) {
            return type.cast(n.intValue());
        }
        if (type == Long.class && value instanceof Number n) {
            return type.cast(n.longValue());
        }
        if (type == Double.class && value instanceof Number n) {
            return type.cast(n.doubleValue());
        }
        if (type == String.class) {
            return type.cast(value.toString());
        }
        return null;
    }

    public boolean isExpired(Clock clock) {
        if (expiresAt == null) {
            return false;
        }
        return !Instant.now(clock).isBefore(expiresAt);
    }

    public Status status(Clock clock) {
        Instant now = Instant.now(clock);
        if (notBefore != null && now.isBefore(notBefore)) {
            return Status.NOT_YET_VALID;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return Status.EXPIRED;
        }
        return Status.ACTIVE;
    }

    public record Customer(String orgName, String contactEmail) {
    }

    public enum Status {
        ACTIVE,
        EXPIRED,
        NOT_YET_VALID
    }
}
