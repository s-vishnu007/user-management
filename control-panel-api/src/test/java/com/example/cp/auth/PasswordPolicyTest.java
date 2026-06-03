package com.example.cp.auth;

import com.example.cp.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link PasswordPolicy}: min length 12, required upper/lower/digit/symbol
 * character classes, the bcrypt 72-byte upper bound, and the common-password denylist.
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    private static void assertRejected(String password, String detailContains) {
        assertThatThrownBy(() -> new PasswordPolicy().validate(password))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getDetail()).contains(detailContains);
                });
    }

    @Test
    void acceptsAStrongPassword() {
        assertThatCode(() -> policy.validate("Tr0ub4dour&3xtra"))
                .doesNotThrowAnyException();
    }

    @Test
    void nullPassword_isRejected() {
        assertRejected(null, "at least " + PasswordPolicy.MIN_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Ab1!", "Ab1!xyz", "Ab1!xyzAb"}) // all < 12 chars
    void tooShort_isRejected(String pw) {
        assertRejected(pw, "at least " + PasswordPolicy.MIN_LENGTH);
    }

    @Test
    void tooLong_isRejected() {
        // 73 chars: one over the bcrypt 72-byte cap, while satisfying every character class.
        String base = "Aa1!"; // upper/lower/digit/symbol
        StringBuilder sb = new StringBuilder(base);
        while (sb.length() <= PasswordPolicy.MAX_LENGTH) {
            sb.append('x');
        }
        assertThat(sb.length()).isGreaterThan(PasswordPolicy.MAX_LENGTH);
        assertRejected(sb.toString(), "at most " + PasswordPolicy.MAX_LENGTH);
    }

    @Test
    void missingUppercase_isRejected() {
        assertRejected("lower0case!secret", "uppercase");
    }

    @Test
    void missingLowercase_isRejected() {
        assertRejected("UPPER0CASE!SECRET", "lowercase");
    }

    @Test
    void missingDigit_isRejected() {
        assertRejected("NoDigitsHere!secret", "digit");
    }

    @Test
    void missingSymbol_isRejected() {
        assertRejected("NoSymbols123secret", "symbol");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password123!", "Changeme123!", "Welcome123!!"})
    void commonPassword_isRejected_caseInsensitively_evenWhenItMeetsCharacterClasses(String pw) {
        // Each satisfies length>=12 + upper/lower/digit/symbol, but its lowercased form
        // ("password123!", "changeme123!", "welcome123!!") is on the denylist.
        assertRejected(pw, "too common");
    }

    @Test
    void boundaryLength12_withAllClasses_isAccepted() {
        assertThat("Abcdef1!ghij".length()).isEqualTo(PasswordPolicy.MIN_LENGTH);
        assertThatCode(() -> policy.validate("Abcdef1!ghij")).doesNotThrowAnyException();
    }
}
