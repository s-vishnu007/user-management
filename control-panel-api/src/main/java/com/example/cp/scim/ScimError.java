package com.example.cp.scim;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SCIM 2.0 {@code Error} response (RFC 7644 §3.12). SCIM clients (IdPs) expect this shape rather than
 * the platform's RFC-7807 {@code ProblemDetail}, so SCIM endpoints serialize errors with this record
 * directly (and set the matching HTTP status) instead of letting {@code GlobalExceptionHandler} render
 * a non-SCIM body.
 *
 * <p>{@code status} is the HTTP status code as a string (per the SCIM schema); {@code scimType} is the
 * optional SCIM error keyword (e.g. {@code uniqueness}, {@code invalidValue}); {@code detail} is a
 * human-readable, caller-safe message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimError(
        java.util.List<String> schemas,
        String status,
        String scimType,
        String detail
) {

    public static final String SCHEMA_ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";

    public static ScimError of(int httpStatus, String scimType, String detail) {
        return new ScimError(java.util.List.of(SCHEMA_ERROR), String.valueOf(httpStatus), scimType, detail);
    }
}
