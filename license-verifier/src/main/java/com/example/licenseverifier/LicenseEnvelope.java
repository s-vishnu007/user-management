package com.example.licenseverifier;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LicenseEnvelope(
        @JsonProperty("license") String license,
        @JsonProperty("issued_at") @JsonAlias("issuedAt") String issuedAt,
        @JsonProperty("customer") String customer,
        @JsonProperty("plan") String plan,
        @JsonProperty("expires_at") @JsonAlias("expiresAt") String expiresAt,
        @JsonProperty("notes") String notes
) {
}
