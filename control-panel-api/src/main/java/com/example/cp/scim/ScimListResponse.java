package com.example.cp.scim;

import java.util.List;

/**
 * SCIM 2.0 {@code ListResponse} envelope (RFC 7644 §3.4.2) returned by {@code GET /scim/v2/Users}.
 *
 * <p>{@code totalResults} is the full count matching the filter (not just this page); {@code startIndex}
 * and {@code itemsPerPage} are 1-based per the SCIM pagination model; {@code Resources} holds the page.
 */
public record ScimListResponse(
        List<String> schemas,
        int totalResults,
        int startIndex,
        int itemsPerPage,
        List<ScimUser> Resources
) {

    public static final String SCHEMA_LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse";

    public static ScimListResponse of(List<ScimUser> resources, int totalResults, int startIndex, int itemsPerPage) {
        return new ScimListResponse(List.of(SCHEMA_LIST), totalResults, startIndex, itemsPerPage, resources);
    }
}
