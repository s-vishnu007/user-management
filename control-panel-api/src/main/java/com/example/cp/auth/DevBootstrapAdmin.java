package com.example.cp.auth;

import com.example.cp.common.Ids;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.orgs.Organization;
import com.example.cp.orgs.OrganizationRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * DEV-ONLY bootstrap of an initial super-admin so the control panel is runnable straight after a
 * fresh DB migration without any manual SQL.
 *
 * <p><strong>Safety model.</strong> This bean is gated three ways and is therefore impossible to
 * run in prod or in the test suite:
 * <ul>
 *   <li>{@code @Profile("dev")} — only instantiated when {@code spring.profiles.active=dev}. The
 *       test suite runs under {@code @ActiveProfiles("test")} and prod runs without {@code dev},
 *       so the bean never even loads there.</li>
 *   <li>{@code @ConditionalOnProperty(app.dev.bootstrap-admin.enabled)} — defaults to enabled
 *       ({@code matchIfMissing = true}) but can be turned off in {@code application-dev.yml} or via
 *       env without removing the dev profile.</li>
 *   <li>Idempotency — it only seeds when {@link UserRepository#count()} is zero, so re-running a
 *       populated dev DB is a no-op.</li>
 * </ul>
 *
 * <p>On {@link ApplicationReadyEvent}, when there are zero users, it creates: an {@code ACTIVE}
 * organization (slug {@code acme}), a super-admin {@link User} ({@code superAdmin=true},
 * {@code ACTIVE}) with a bcrypt-hashed password from {@link PasswordConfig}'s {@link PasswordEncoder}
 * bean, and an {@code OWNER} {@link OrgMember} row linking them. The credentials used are logged at
 * {@code WARN} so a developer can immediately sign in.
 *
 * <p>Entities are built directly (mirroring {@code OrgService}/{@code UserService}) rather than going
 * through {@code UserService.createUser}, because that path forces {@code superAdmin=false} and runs
 * the production {@code PasswordPolicy}; here we deliberately seed a super-admin with a known dev
 * credential.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.dev.bootstrap-admin.enabled", havingValue = "true", matchIfMissing = true)
public class DevBootstrapAdmin {

    private static final Logger log = LoggerFactory.getLogger(DevBootstrapAdmin.class);

    private static final String ORG_SLUG = "acme";

    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final OrgMemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    private final String adminEmail;
    private final String adminPassword;
    private final String orgName;

    public DevBootstrapAdmin(UserRepository userRepository,
                             OrganizationRepository orgRepository,
                             OrgMemberRepository memberRepository,
                             PasswordEncoder passwordEncoder,
                             @Value("${app.dev.bootstrap-admin.email:${APP_BOOTSTRAP_ADMIN_EMAIL:admin@example.com}}")
                             String adminEmail,
                             @Value("${app.dev.bootstrap-admin.password:${APP_BOOTSTRAP_ADMIN_PASSWORD:Admin123!ChangeMe}}")
                             String adminPassword,
                             @Value("${app.dev.bootstrap-admin.org-name:${APP_BOOTSTRAP_ORG_NAME:Acme (dev)}}")
                             String orgName) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.orgName = orgName;
    }

    /**
     * Seeds the super-admin + org + OWNER membership when the DB has no users yet. Idempotent: a
     * non-empty {@code users} table short-circuits. {@code @Order} is high so this runs after the
     * signing-key bootstrap; ordering is not strictly required but keeps startup logs predictable.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1000)
    @Transactional
    public void seedSuperAdminIfEmpty() {
        if (userRepository.count() > 0) {
            log.debug("Dev bootstrap-admin: users already present, skipping seed");
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();

        Organization org = orgRepository.findBySlug(ORG_SLUG).orElseGet(() ->
                orgRepository.save(Organization.builder()
                        .id(Ids.newId())
                        .slug(ORG_SLUG)
                        .name(orgName)
                        .status(Organization.Status.ACTIVE)
                        .createdAt(now)
                        .updatedAt(now)
                        .build()));

        User admin = userRepository.save(User.builder()
                .id(Ids.newId())
                .email(adminEmail)
                .fullName("Dev Super Admin")
                .passwordHash(passwordEncoder.encode(adminPassword))
                .status(User.Status.ACTIVE)
                .superAdmin(true)
                .createdAt(now)
                .build());

        memberRepository.save(OrgMember.builder()
                .orgId(org.getId())
                .userId(admin.getId())
                .role(OrgMember.Role.OWNER)
                .addedAt(now)
                .build());

        log.warn("""
                ================================================================
                DEV BOOTSTRAP SUPER-ADMIN CREATED (profile=dev only)
                  org:      slug='{}' name='{}'
                  email:    {}
                  password: {}
                Change these immediately and NEVER enable the dev profile in prod.
                Disable this seeding with app.dev.bootstrap-admin.enabled=false.
                ================================================================""",
                ORG_SLUG, orgName, adminEmail, adminPassword);
    }
}
