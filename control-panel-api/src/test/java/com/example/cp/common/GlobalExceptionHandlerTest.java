package com.example.cp.common;

import com.example.cp.audit.AuditWriter;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for the two new {@link GlobalExceptionHandler} mappings introduced to stop standard
 * conflict/race exceptions surfacing as raw 500s. The framework-supplied Spring MVC exception
 * mappings (malformed JSON 400, wrong method 405, etc.) come from extending
 * {@link ResponseEntityExceptionHandler} and are exercised end-to-end in
 * {@link ApiContractErrorMappingIT}.
 */
class GlobalExceptionHandlerTest {

    private final AuditWriter auditWriter = mock(AuditWriter.class);
    private final TrustedProxyResolver proxyResolver = mock(TrustedProxyResolver.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(auditWriter, proxyResolver);

    @Test
    void optimisticLockingFailure_mapsTo409WithRetryHint() {
        ResponseEntity<ProblemDetail> resp = handler.handleOptimisticLock(
                new ObjectOptimisticLockingFailureException("User", "id-1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail pd = resp.getBody();
        assertThat(pd).isNotNull();
        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties()).containsEntry("retryable", true);
        // A bare optimistic-lock failure is not an authz refusal: nothing should be audited.
        verifyNoInteractions(auditWriter);
    }

    @Test
    void plainOptimisticLockingFailure_mapsTo409() {
        ResponseEntity<ProblemDetail> resp = handler.handleOptimisticLock(
                new OptimisticLockingFailureException("stale"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getStatus()).isEqualTo(409);
    }

    @Test
    void dataIntegrityViolation_mapsTo409_andDoesNotLeakConstraintDetail() {
        ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrity(
                new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"ux_org_member\""));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail pd = resp.getBody();
        assertThat(pd).isNotNull();
        assertThat(pd.getStatus()).isEqualTo(409);
        // The raw constraint name must NOT be echoed to the client.
        assertThat(pd.getDetail()).doesNotContain("ux_org_member");
        assertThat(pd.getDetail()).doesNotContain("unique constraint");
    }
}
