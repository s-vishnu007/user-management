package com.example.cp.users;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
        AuditContext.set("user.password.reset");
        AuditContext.setTarget("user", id.toString());
        userRepository.save(u);
    }

    @Transactional
    public void deactivate(UUID id) {
        User u = get(id);
        u.setStatus(User.Status.SUSPENDED);
        AuditContext.set("user.deactivated");
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
}
