package com.example.cp.scim;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SCIM 2.0 multi-valued {@code emails} entry (RFC 7643 §4.1.2). The control panel keys users by a
 * single email, so we emit exactly one entry with {@code primary=true} and {@code type="work"}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimEmail(
        String value,
        String type,
        Boolean primary
) {
    public static ScimEmail primaryWork(String value) {
        return new ScimEmail(value, "work", Boolean.TRUE);
    }
}
