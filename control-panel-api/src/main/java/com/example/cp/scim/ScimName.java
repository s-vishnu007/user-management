package com.example.cp.scim;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SCIM 2.0 {@code name} complex attribute (RFC 7643 §4.1.1). Only the fields the control panel models
 * are populated; {@code formatted} is the human-friendly full name we store in {@code users.full_name},
 * and {@code givenName}/{@code familyName} are best-effort split components an IdP may also send.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimName(
        String formatted,
        String givenName,
        String familyName
) {
}
