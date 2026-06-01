package com.example.licenseverifier.exceptions;

import java.util.Set;

public class LicenseKidUnknownException extends LicenseException {

    private final String kid;
    private final Set<String> knownKids;

    public LicenseKidUnknownException(String kid, Set<String> knownKids) {
        super("License signing key id '" + kid + "' is not in the known set " + knownKids);
        this.kid = kid;
        this.knownKids = knownKids;
    }

    public String getKid() {
        return kid;
    }

    public Set<String> getKnownKids() {
        return knownKids;
    }
}
