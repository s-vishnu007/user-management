package com.example.licenseverifier.exceptions;

import java.time.Instant;

public class LicenseNotYetValidException extends LicenseException {

    private final Instant notBefore;

    public LicenseNotYetValidException(String message, Instant notBefore) {
        super(message);
        this.notBefore = notBefore;
    }

    public Instant getNotBefore() {
        return notBefore;
    }
}
