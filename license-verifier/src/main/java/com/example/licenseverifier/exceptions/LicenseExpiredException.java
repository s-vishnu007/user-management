package com.example.licenseverifier.exceptions;

import java.time.Instant;

public class LicenseExpiredException extends LicenseException {

    private final Instant expiresAt;

    public LicenseExpiredException(String message, Instant expiresAt) {
        super(message);
        this.expiresAt = expiresAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
