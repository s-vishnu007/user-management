package com.example.licenseverifier.spring;

public class LicensePermissionDeniedException extends RuntimeException {

    private final String missingPermission;

    public LicensePermissionDeniedException(String missingPermission) {
        super("License does not grant required permission: " + missingPermission);
        this.missingPermission = missingPermission;
    }

    public LicensePermissionDeniedException(String missingPermission, String message) {
        super(message);
        this.missingPermission = missingPermission;
    }

    public String getMissingPermission() {
        return missingPermission;
    }
}
