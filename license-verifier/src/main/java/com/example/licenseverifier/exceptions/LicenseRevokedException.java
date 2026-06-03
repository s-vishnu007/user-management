package com.example.licenseverifier.exceptions;

/**
 * Thrown by the license verifier when a license's jti appears in the revocation set (or when a
 * fail-closed {@link com.example.licenseverifier.RevocationChecker} is not operational). Extends
 * {@link LicenseException} so existing {@code catch (LicenseException)} handlers still catch it.
 */
public class LicenseRevokedException extends LicenseException {

    private final String jti;

    public LicenseRevokedException(String jti) {
        super("License '" + jti + "' has been revoked");
        this.jti = jti;
    }

    public String getJti() {
        return jti;
    }
}
