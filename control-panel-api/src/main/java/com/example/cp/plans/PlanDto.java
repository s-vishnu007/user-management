package com.example.cp.plans;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PlanDto(
        UUID id,
        String code,
        String name,
        String description,
        String tier,
        boolean active,
        int defaultTtlDays,
        OffsetDateTime createdAt,
        List<String> permissions,
        Map<String, Object> features
) {
    public static PlanDto basic(Plan p) {
        return new PlanDto(
                p.getId(), p.getCode(), p.getName(), p.getDescription(), p.getTier(),
                p.isActive(), p.getDefaultTtlDays(), p.getCreatedAt(),
                List.of(), Map.of()
        );
    }
}
