package com.example.cp.scim;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SCIM 2.0 core User resource representation
 * ({@code urn:ietf:params:scim:schemas:core:2.0:User}, RFC 7643 §4.1).
 *
 * <p>Used both as the request body the IdP POSTs/PUTs and as the response the control panel returns.
 * On the wire:
 * <ul>
 *   <li>{@code schemas} is always {@code ["urn:ietf:params:scim:schemas:core:2.0:User"]} on responses;</li>
 *   <li>{@code id} is the {@link ScimUserMapping} id (the per-org resource id), NOT the raw user id;</li>
 *   <li>{@code userName} maps to the control-panel email (the unique login);</li>
 *   <li>{@code active} mirrors {@code users.status == ACTIVE} (false once deprovisioned/suspended);</li>
 *   <li>{@code meta} carries the resourceType/location for client bookkeeping.</li>
 * </ul>
 * {@code @JsonInclude(NON_NULL)} keeps optional attributes off the wire when unset, which is valid
 * per the SCIM schema (attributes are returned only when present).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimUser(
        List<String> schemas,
        String id,
        String externalId,
        String userName,
        ScimName name,
        String displayName,
        List<ScimEmail> emails,
        Boolean active,
        ScimMeta meta
) {

    public static final String SCHEMA_USER = "urn:ietf:params:scim:schemas:core:2.0:User";

    /** SCIM 2.0 {@code meta} complex attribute (RFC 7643 §3.1). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScimMeta(String resourceType, String location) {
        public static ScimMeta forUser(String id) {
            return new ScimMeta("User", "/scim/v2/Users/" + id);
        }
    }
}
