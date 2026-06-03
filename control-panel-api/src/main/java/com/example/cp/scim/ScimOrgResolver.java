package com.example.cp.scim;

import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.SecurityUtils;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the org a SCIM request operates on. SCIM clients call {@code /scim/v2/Users} with no orgId
 * in the path, so the org is derived from the calling principal:
 * <ul>
 *   <li>an API-key principal -&gt; the key's bound org ({@code apiKeyOrgId});</li>
 *   <li>a (human) super-admin -&gt; explicitly unsupported here: there is no path/param org to scope to,
 *       so a super-admin cannot drive these endpoints without an org-bound key. {@code callerOrgId()}
 *       returns {@code null} for any non-api-key principal and the {@code @PreAuthorize} gate then denies.</li>
 * </ul>
 *
 * <p>Exposed as a Spring bean ({@code @scimOrg}) so it can be referenced directly from method-security
 * SpEL, and used inside controller methods to obtain the same value the gate authorized against.
 */
@Component("scimOrg")
public class ScimOrgResolver {

    /** The caller's SCIM org, or {@code null} when the principal is not an org-bound API key. */
    public UUID callerOrgId() {
        AuthenticatedUser u = SecurityUtils.currentUser().orElse(null);
        if (u == null) {
            return null;
        }
        if (u.isApiKey()) {
            return u.apiKeyOrgId();
        }
        return null;
    }
}
