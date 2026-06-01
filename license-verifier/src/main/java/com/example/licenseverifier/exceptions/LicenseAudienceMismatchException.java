package com.example.licenseverifier.exceptions;

import java.util.List;

public class LicenseAudienceMismatchException extends LicenseException {

    private final String expected;
    private final List<String> actual;

    public LicenseAudienceMismatchException(String expected, List<String> actual) {
        super("License audience mismatch: expected '" + expected + "' but token audience was " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public String getExpected() {
        return expected;
    }

    public List<String> getActual() {
        return actual;
    }
}
