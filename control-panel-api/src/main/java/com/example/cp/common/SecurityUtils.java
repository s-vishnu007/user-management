package com.example.cp.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static Optional<AuthenticatedUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof AuthenticatedUser u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    public static UUID currentUserId() {
        return currentUser()
                .map(AuthenticatedUser::userId)
                .orElseThrow(() -> ApiException.unauthorized("Not authenticated"));
    }

    public static AuthenticatedUser requireUser() {
        return currentUser().orElseThrow(() -> ApiException.unauthorized("Not authenticated"));
    }

    public static boolean isAuthenticated() {
        return currentUser().isPresent();
    }
}
