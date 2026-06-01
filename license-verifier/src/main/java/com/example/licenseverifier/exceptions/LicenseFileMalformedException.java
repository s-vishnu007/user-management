package com.example.licenseverifier.exceptions;

public class LicenseFileMalformedException extends LicenseException {

    public LicenseFileMalformedException(String message) {
        super(message);
    }

    public LicenseFileMalformedException(String message, Throwable cause) {
        super(message, cause);
    }
}
