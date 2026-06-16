package com.example.cp.auth;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.orgs.OrgService;
import com.example.cp.orgs.Organization;
import com.example.cp.subscriptions.OutboxPublisher;
import com.example.cp.users.User;
import com.example.cp.users.UserService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Self-service signup: creates a brand-new organization with the signer as its OWNER.
 *
 * <p>Reuses the exact creation paths the rest of the app uses — {@link UserService#createUser}
 * (password policy + bcrypt + email-uniqueness, and a hard-coded {@code superAdmin=false}) and
 * {@link OrgService#createOrgFromName} (derives a unique slug and adds the creator as OWNER) — so a
 * self-service owner is indistinguishable from one created administratively. The user is created
 * ACTIVE but unverified; an {@link EmailVerificationService} token is issued and domain events are
 * published for downstream email delivery. The whole thing runs in one transaction, so a failure
 * leaves neither a dangling user nor org.
 */
@Service
public class RegistrationService {

    private final UserService userService;
    private final OrgService orgService;
    private final EmailVerificationService emailVerificationService;
    private final OutboxPublisher outboxPublisher;

    public RegistrationService(UserService userService,
                               OrgService orgService,
                               EmailVerificationService emailVerificationService,
                               @Qualifier("subscriptionOutboxPublisher") OutboxPublisher outboxPublisher) {
        this.userService = userService;
        this.orgService = orgService;
        this.emailVerificationService = emailVerificationService;
        this.outboxPublisher = outboxPublisher;
    }

    @Transactional
    public Result register(String fullName, String email, String password, String orgName, String ip) {
        String name = orgName == null ? "" : orgName.trim();
        if (name.isBlank()) {
            throw ApiException.badRequest("Organization name is required");
        }
        String trimmedEmail = email == null ? null : email.trim();

        // createUser validates the password policy, enforces email uniqueness (409) and hard-codes
        // superAdmin=false — a self-service signup can never mint a super-admin. New users default to
        // email_verified=false (see User entity).
        User user = userService.createUser(trimmedEmail, fullName, password);

        Organization org = orgService.createOrgFromName(name, user.getId());

        String verificationToken = emailVerificationService.issueToken(user);

        // Real email delivery is an external outbox consumer in prod (none required in dev). Publish
        // both the lifecycle event and the verification request.
        outboxPublisher.publish("user", user.getId().toString(), "user.registered",
                Map.of("email", user.getEmail(),
                        "org_id", org.getId().toString(),
                        "org_slug", org.getSlug()));
        outboxPublisher.publish("user", user.getId().toString(), "email.verification.requested",
                Map.of("email", user.getEmail()));

        AuditContext.set("auth.register");
        AuditContext.setTarget("user", user.getId().toString());
        AuditContext.putPayload("org_id", org.getId().toString());

        return new Result(user, org, org.getSlug(), verificationToken);
    }

    /** Outcome of a successful registration. */
    public record Result(User user, Organization org, String orgSlug, String verificationToken) {
    }
}
