package com.example.cp.subscriptions;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SubscriptionDto(
        UUID id,
        UUID orgId,
        UUID planId,
        String planCode,
        String status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer seats,
        String notes,
        UUID createdBy,
        OffsetDateTime createdAt,
        List<OverrideDto> overrides
) {

    public record OverrideDto(UUID id, String type, String key, Object value) {}
}
