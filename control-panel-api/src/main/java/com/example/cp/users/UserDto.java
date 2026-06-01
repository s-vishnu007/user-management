package com.example.cp.users;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserDto(
        UUID id,
        String email,
        String fullName,
        String status,
        boolean superAdmin,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginAt
) {
    public static UserDto from(User u) {
        return new UserDto(
                u.getId(),
                u.getEmail(),
                u.getFullName(),
                u.getStatus() == null ? null : u.getStatus().name(),
                u.isSuperAdmin(),
                u.getCreatedAt(),
                u.getLastLoginAt()
        );
    }
}
