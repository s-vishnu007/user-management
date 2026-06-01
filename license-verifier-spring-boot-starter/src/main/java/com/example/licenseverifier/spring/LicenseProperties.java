package com.example.licenseverifier.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.license")
public class LicenseProperties {

    /** Filesystem path to the .lic file. */
    private String path = "/etc/app/license.lic";

    /** Required audience claim. */
    private String audience;

    /** Optional issuer claim. */
    private String issuer;

    /** Optional JWKS URL. If null, JWKS is loaded from classpath:/jwks.json. */
    private String refreshFromUrl;

    /** How often to re-read the license file and refresh JWKS. */
    private Duration refreshInterval = Duration.ofHours(24);

    /** Permitted clock skew when validating exp/nbf. */
    private Duration clockSkew = Duration.ofMinutes(5);

    /** If true, an expired license keeps the app running in READ_ONLY mode. */
    private boolean readOnlyOnExpiry = true;

    /** If true, refuse to start the context when the license is missing or invalid. */
    private boolean strict = true;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getRefreshFromUrl() {
        return refreshFromUrl;
    }

    public void setRefreshFromUrl(String refreshFromUrl) {
        this.refreshFromUrl = refreshFromUrl;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public boolean isReadOnlyOnExpiry() {
        return readOnlyOnExpiry;
    }

    public void setReadOnlyOnExpiry(boolean readOnlyOnExpiry) {
        this.readOnlyOnExpiry = readOnlyOnExpiry;
    }

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }
}
