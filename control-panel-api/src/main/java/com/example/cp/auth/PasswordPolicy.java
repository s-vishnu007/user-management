package com.example.cp.auth;

import com.example.cp.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Centralized password-strength policy enforced wherever a password is set or changed
 * ({@code UserService.createUser/setPassword/changePassword} and the reset-confirm path).
 *
 * <p>Rules: minimum {@value #MIN_LENGTH} characters, at most {@value #MAX_LENGTH} characters
 * (bcrypt truncates beyond 72 bytes, so we cap before hashing), at least one uppercase letter,
 * one lowercase letter, one digit and one symbol (any non-alphanumeric), and the password must
 * not be one of a small set of well-known common/breached passwords.</p>
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    /** bcrypt only consumes the first 72 bytes; reject longer to avoid silent truncation surprises. */
    public static final int MAX_LENGTH = 72;

    /**
     * A deliberately small denylist of the most-common passwords. This is NOT a substitute for a
     * full breached-password (HIBP) check — it just blocks the obvious offenders that still satisfy
     * the character-class rules (e.g. {@code Password123!}).
     */
    private static final Set<String> COMMON = Set.of(
            "password",
            "password1",
            "password123",
            "password123!",
            "passw0rd",
            "passw0rd!",
            "p@ssw0rd",
            "p@ssw0rd1234",
            "p@ssword1234",
            "qwerty123",
            "qwertyuiop",
            "letmein12345",
            "welcome12345",
            "welcome123!!",
            "admin123!xyz",
            "changeme123!",
            "changeme1234",
            "1q2w3e4r5t6y",
            "1qaz2wsx3edc",
            "zaq12wsx!cde");

    /** Validates the password against the policy; throws {@link ApiException} (400) on the first failure. */
    public void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw ApiException.badRequest("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw ApiException.badRequest("Password must be at most " + MAX_LENGTH + " characters");
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean symbol = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                // Anything else (incl. whitespace/punctuation/unicode) counts as a symbol.
                symbol = true;
            }
        }
        if (!upper) {
            throw ApiException.badRequest("Password must contain an uppercase letter");
        }
        if (!lower) {
            throw ApiException.badRequest("Password must contain a lowercase letter");
        }
        if (!digit) {
            throw ApiException.badRequest("Password must contain a digit");
        }
        if (!symbol) {
            throw ApiException.badRequest("Password must contain a symbol");
        }
        if (COMMON.contains(password.toLowerCase())) {
            throw ApiException.badRequest("Password is too common; choose a stronger password");
        }
    }
}
