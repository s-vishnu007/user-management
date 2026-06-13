package com.example.cp.mfa;

import com.example.cp.common.ApiException;
import com.example.cp.keys.KeyEncryptor;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MfaService}. The repository is a Mockito mock; {@link KeyEncryptor} is a
 * real instance (with a test AES key) so the encrypt→store→decrypt→verify round-trip is exercised
 * end-to-end. Valid TOTP codes are produced with the same {@code dev.samstevens.totp} primitives
 * the service uses, against the secret the service generated and persisted.
 */
class MfaServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");

    private UserMfaRepository repository;
    private MfaService service;
    private final DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    private final SystemTimeProvider timeProvider = new SystemTimeProvider();

    @BeforeEach
    void setUp() {
        repository = mock(UserMfaRepository.class);
        // 32-byte base64 AES key for the real KeyEncryptor.
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        // 3-arg KeyEncryptor: legacy/default master key only (no versioned KEK list / active id) — it
        // registers under the reserved default id and becomes the active KEK for this test.
        KeyEncryptor encryptor = new KeyEncryptor(Base64.getEncoder().encodeToString(key), "", "");
        invokeInit(encryptor);
        service = new MfaService(repository, encryptor, "Test Issuer", "x".repeat(40));
    }

    /** Generates the current valid TOTP code for {@code secret} via the library. */
    private String currentCode(String secret) throws Exception {
        long bucket = Math.floorDiv(timeProvider.getTime(), 30);
        return codeGenerator.generate(secret, bucket);
    }

    // ---- enroll -----------------------------------------------------------

    @Test
    void enroll_generatesSecret_storesEncryptedDisabled_andReturnsOtpAuthUri() {
        when(repository.existsByUserIdAndEnabledTrue(USER)).thenReturn(false);
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());

        MfaService.EnrollmentResult result = service.enroll(USER, "alice@example.com");

        assertThat(result.secret()).isNotBlank();
        assertThat(result.otpAuthUri())
                .startsWith("otpauth://totp/")
                .contains("secret=" + result.secret())
                .contains("issuer=Test+Issuer")
                .contains("algorithm=SHA1")
                .contains("digits=6")
                .contains("period=30");

        org.mockito.ArgumentCaptor<UserMfa> saved = org.mockito.ArgumentCaptor.forClass(UserMfa.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isEnabled()).isFalse();
        assertThat(saved.getValue().getSecretEnc()).isNotEmpty();
        // The stored blob must be ciphertext, not the plaintext secret.
        assertThat(new String(saved.getValue().getSecretEnc(), StandardCharsets.UTF_8))
                .isNotEqualTo(result.secret());
    }

    @Test
    void enroll_whenAlreadyEnabled_isConflict() {
        when(repository.existsByUserIdAndEnabledTrue(USER)).thenReturn(true);
        assertThatThrownBy(() -> service.enroll(USER, "alice@example.com"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).save(any());
    }

    // ---- confirm / verify -------------------------------------------------

    @Test
    void confirmEnrollment_withValidCode_enablesMfa() throws Exception {
        // Arrange: enroll to obtain a real secret, capture the persisted (encrypted) row.
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        MfaService.EnrollmentResult enrollment = service.enroll(USER, "alice@example.com");
        UserMfa stored = captureSaved();
        stored.setEnabled(false);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(stored));

        boolean ok = service.confirmEnrollment(USER, currentCode(enrollment.secret()));

        assertThat(ok).isTrue();
        assertThat(stored.isEnabled()).isTrue();
    }

    @Test
    void confirmEnrollment_withWrongCode_doesNotEnable() {
        UserMfa stored = enrolledRow(false);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(stored));

        boolean ok = service.confirmEnrollment(USER, "000000");

        assertThat(ok).isFalse();
        assertThat(stored.isEnabled()).isFalse();
    }

    @Test
    void confirmEnrollment_withNoEnrollment_isBadRequest() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirmEnrollment(USER, "123456"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void verifyLoginCode_validCode_onEnabledRow_isTrue() throws Exception {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        MfaService.EnrollmentResult enrollment = service.enroll(USER, "alice@example.com");
        UserMfa stored = captureSaved();
        stored.setEnabled(true);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(stored));

        assertThat(service.verifyLoginCode(USER, currentCode(enrollment.secret()))).isTrue();
    }

    @Test
    void verifyLoginCode_whenNotEnabled_isFalse() throws Exception {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        MfaService.EnrollmentResult enrollment = service.enroll(USER, "alice@example.com");
        UserMfa stored = captureSaved();
        stored.setEnabled(false); // pending, not confirmed
        when(repository.findByUserId(USER)).thenReturn(Optional.of(stored));

        assertThat(service.verifyLoginCode(USER, currentCode(enrollment.secret()))).isFalse();
    }

    @Test
    void verifyLoginCode_wrongCode_isFalse() {
        // Build the enrolled row first: enrolledRow() does its own when().thenReturn() internally, so
        // calling it inside an outer when().thenReturn(...) argument would nest two stubbings and
        // trigger Mockito's UnfinishedStubbingException.
        UserMfa enrolled = enrolledRow(true);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(enrolled));
        assertThat(service.verifyLoginCode(USER, "000000")).isFalse();
        assertThat(service.verifyLoginCode(USER, null)).isFalse();
        assertThat(service.verifyLoginCode(USER, "")).isFalse();
    }

    @Test
    void isEnabled_delegatesToRepository() {
        when(repository.existsByUserIdAndEnabledTrue(USER)).thenReturn(true);
        assertThat(service.isEnabled(USER)).isTrue();
        assertThat(service.isEnabled(null)).isFalse();
    }

    // ---- replay protection ------------------------------------------------

    @Test
    void verifyLoginCode_recordsTheAcceptedStep_andRejectsTheSameCodeReplayedWithinTheWindow()
            throws Exception {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        MfaService.EnrollmentResult enrollment = service.enroll(USER, "alice@example.com");
        UserMfa stored = captureSaved();
        stored.setEnabled(true);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(stored));

        String code = currentCode(enrollment.secret());

        // First use of the current code succeeds and advances last_accepted_step.
        assertThat(service.verifyLoginCode(USER, code)).isTrue();
        assertThat(stored.getLastAcceptedStep()).isNotNull();

        // Replaying the SAME code (same time-step) inside its validity window is now rejected — the
        // step is <= last_accepted_step. This is the core anti-replay guarantee.
        assertThat(service.verifyLoginCode(USER, code)).isFalse();
    }

    @Test
    void verifyLoginCode_rejectsACodeForAStepAtOrBeforeTheLastAccepted() throws Exception {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        MfaService.EnrollmentResult enrollment = service.enroll(USER, "alice@example.com");
        UserMfa stored = captureSaved();
        stored.setEnabled(true);

        long currentStep = Math.floorDiv(timeProvider.getTime(), 30);
        // Pretend a code at the current step has already been consumed.
        stored.setLastAcceptedStep(currentStep);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(stored));

        // The valid current-step code is refused because its step is not strictly greater.
        assertThat(service.verifyLoginCode(USER, currentCode(enrollment.secret()))).isFalse();
    }

    @Test
    void confirmEnrollment_recordsTheAcceptedStep() throws Exception {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        MfaService.EnrollmentResult enrollment = service.enroll(USER, "alice@example.com");
        UserMfa stored = captureSaved();
        stored.setEnabled(false);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(stored));

        assertThat(service.confirmEnrollment(USER, currentCode(enrollment.secret()))).isTrue();
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getLastAcceptedStep()).isNotNull();
    }

    // ---- challenge --------------------------------------------------------

    @Test
    void issueChallenge_thenParseChallenge_roundTripsTheUserId() {
        MfaService.MfaChallenge challenge = service.issueChallenge(USER, "alice@example.com");
        assertThat(challenge.challenge()).isNotBlank();
        assertThat(service.parseChallenge(challenge.challenge())).isEqualTo(USER);
    }

    @Test
    void parseChallenge_rejectsGarbage() {
        assertThatThrownBy(() -> service.parseChallenge("not-a-jwt"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> service.parseChallenge(""))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void parseChallenge_rejectsAForeignlySignedToken() {
        // A challenge signed with a DIFFERENT secret must not verify.
        MfaService other = new MfaService(repository, mock(KeyEncryptor.class), "Other", "y".repeat(40));
        MfaService.MfaChallenge foreign = other.issueChallenge(USER, "alice@example.com");
        assertThatThrownBy(() -> service.parseChallenge(foreign.challenge()))
                .isInstanceOf(ApiException.class);
    }

    // ---- helpers ----------------------------------------------------------

    private UserMfa captureSaved() {
        org.mockito.ArgumentCaptor<UserMfa> cap = org.mockito.ArgumentCaptor.forClass(UserMfa.class);
        verify(repository).save(cap.capture());
        return cap.getValue();
    }

    /** Builds an enrolled row with a fresh real (encrypted) secret at the given enabled state. */
    private UserMfa enrolledRow(boolean enabled) {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        service.enroll(USER, "alice@example.com");
        UserMfa row = captureSaved();
        row.setEnabled(enabled);
        return row;
    }

    /** Invokes the {@code @PostConstruct init()} on KeyEncryptor (package-private) reflectively. */
    private static void invokeInit(KeyEncryptor encryptor) {
        try {
            var m = KeyEncryptor.class.getDeclaredMethod("init");
            m.setAccessible(true);
            m.invoke(encryptor);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init KeyEncryptor for test", e);
        }
    }

    @Test
    void createdAt_isSetOnNewEnrollment() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        service.enroll(USER, "alice@example.com");
        UserMfa row = captureSaved();
        assertThat(row.getCreatedAt()).isNotNull().isBeforeOrEqualTo(OffsetDateTime.now());
    }
}
