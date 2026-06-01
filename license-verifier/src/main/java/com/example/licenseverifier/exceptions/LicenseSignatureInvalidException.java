package com.example.licenseverifier.exceptions;

public class LicenseSignatureInvalidException extends LicenseException {

    public LicenseSignatureInvalidException(String message) {
        super(message);
    }

    public LicenseSignatureInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
