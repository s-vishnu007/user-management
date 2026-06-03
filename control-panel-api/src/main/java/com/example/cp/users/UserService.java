package com.example.cp.users;

import com.example.cp.auth.SessionRevocationStore;
import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevocationStore revocationStore;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       SessionRevocationStore revocationStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.revocationStore = revocationStore;
    }

    @Transactional
    public User createUser(String email, String fullName, String password) {
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("Email is required");
        }
        if (password == null || password.length() < 8) {
            throw ApiException.badRequest("Password must be at least 8 characters");
        }
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("A user with that email already exists");
        }
        AuditContext.set("user.created");
        User u = User.builder()
                .id(Ids.newId())
                .email(email)
                .fullName(fullName)
                .passwordHash(passwordEncoder.encode(password))
                .status(User.Status.ACTIVE)
                .superAdmin(false)
                .createdAt(OffsetDateTime.now())
                .build();
        User saved = userRepository.save(u);
        AuditContext.setTarget("user", saved.getId().toString());
        return saved;
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    @Transactional
    public User updateProfile(UUID id, String fullName) {
        User u = get(id);
        if (fullName != null) {
            u.setFullName(fullName);
        }
        AuditContext.set("user.updated");
        AuditContext.setTarget("user", id.toString());
        return userRepository.save(u);
    }

    @Transactional
    public void changePassword(UUID id, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw ApiException.badRequest("Password must be at least 8 characters");
        }
        User u = get(id);
        if (u.getPasswordHash() == null || !passwordEncoder.matches(oldPassword, u.getPasswordHash())) {
            throw ApiException.badRequest("Old password is incorrect");
        }
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        revokeAllSessions(u);
        AuditContext.set("user.password.changed");
        AuditContext.setTarget("user", id.toString());
        userRepository.save(u);
    }

    @Transactional
    public void setPassword(UUID id, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw ApiException.badRequest("Password must be at least 8 characters");
        }
        User u = get(id);
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        revokeAllSessions(u);
        AuditContext.set("user.password.reset");
        AuditContext.setTarget("user", id.toString());
        userRepository.save(u);
    }

    @Transactional
    public void deactivate(UUID id) {
        User u = get(id);
        u.setStatus(User.Status.SUSPENDED);
        revokeAllSessions(u);
        AuditContext.set("user.deactivated");
        AuditContext.setTarget("user", id.toString());
        userRepository.save(u);
    }

    @Transactional
    public void delete(UUID id) {
        User u = get(id);
        // Soft delete: matches the DELETED enum + CHECK constraint (no hard delete).
        u.setStatus(User.Status.DELETED);
        revokeAllSessions(u);
        AuditContext.set("user.deleted");
        AuditContext.setTarget("user", id.toString());
        userRepository.save(u);
    }

    @Transactional
    public void touchLastLogin(UUID id) {
        userRepository.findById(id).ifPresent(u -> {
            u.setLastLoginAt(OffsetDateTime.now());
            userRepository.save(u);
        });
    }

    /**
     * Bulk-revokes all of a user's active sessions by bumping the durable per-user token-version
     * (DB is the source of truth) and write-through to the Redis fast-path cache (best-effort).
     * The caller is responsible for persisting the user (e.g. {@code userRepository.save(u)}).
     */
    private void revokeAllSessions(User u) {
        long v = u.getTokenVersion() + 1;
        u.setTokenVersion(v);
        try {
            revocationStore.setTokenVersion(u.getId(), v);
        } catch (Exception e) {
            // DB column is authoritative; the Redis write is a best-effort accelerator.
            log.warn("Failed to write-through token-version to revocation store for user {}: {}",
                    u.getId(), e.getMessage());
        }
    }
}
