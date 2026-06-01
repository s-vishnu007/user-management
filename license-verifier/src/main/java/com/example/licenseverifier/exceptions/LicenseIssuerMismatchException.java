package com.example.licenseverifier.exceptions;

public class LicenseIssuerMismatchException extends LicenseException {

    private final String expected;
    private final String actual;

    public LicenseIssuerMismatchException(String expected, String actual) {
        super("License issuer mismatch: expected '" + expected + "' but token issuer was '" + actual + "'");
        this.expected = expected;
        this.actual = actual;
    }

    public String getExpected() {
        return expected;
    }

    public String getActual() {
        return actual;
    }
}
