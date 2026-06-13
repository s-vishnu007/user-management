package com.example.cp.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure, hermetic unit tests for {@link OutboxDeliveryScheduler}: no Spring context and no database.
 * The single collaborator ({@link JdbcTemplate}) is a Mockito mock, so the claim query is stubbed to
 * return synthetic rows and the per-row {@code UPDATE} statements are asserted via captured SQL.
 *
 * <p>Covered:
 * <ul>
 *   <li>the claim query uses {@code status = 'PENDING'}, the due-time guard, {@code ORDER BY
 *       occurred_at}, and {@code FOR UPDATE SKIP LOCKED} (multi-instance safety);</li>
 *   <li>a successful notify flips the row to {@code PUBLISHED} and stamps {@code published_at};</li>
 *   <li>a failed notify increments {@code attempts}, records {@code last_error}, and schedules a
 *       retry ({@code next_attempt_at}) while the row stays {@code PENDING};</li>
 *   <li>a row already on its last attempt is quarantined as {@code FAILED} (poison message);</li>
 *   <li>the retention sweep only deletes terminal rows older than the window;</li>
 *   <li>capped exponential backoff math.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxDeliverySchedulerTest {

    @Mock
    private JdbcTemplate jdbc;

    private OutboxDeliveryScheduler scheduler() {
        return new OutboxDeliveryScheduler(jdbc, Duration.ofDays(30));
    }

    // ------------------------------------------------------------------
    //  Claim query shape (multi-instance safety)
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void claimQuery_isPendingDueOrderedAndSkipLocked() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        scheduler().publishBatch();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class));
        String q = sql.getValue();
        assertThat(q).contains("status = 'PENDING'");
        assertThat(q).contains("next_attempt_at IS NULL OR next_attempt_at <= now()");
        assertThat(q).contains("ORDER BY occurred_at ASC");
        assertThat(q).contains("FOR UPDATE SKIP LOCKED");
        // Nothing claimed => no UPDATE / pg_notify issued.
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // ------------------------------------------------------------------
    //  Success path
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void successfulNotify_marksPublished() {
        UUID id = UUID.randomUUID();
        OutboxDeliverySchedulerTestRows.stub(jdbc, new OutboxDeliverySchedulerTestRows.Row(id, "subscription.created", 0));
        // pg_notify + the PUBLISHED update both succeed (mock returns 0 by default).

        scheduler().publishBatch();

        // The PUBLISHED update is issued for this row's id.
        verify(jdbc).update(contains("status = 'PUBLISHED'"),
                any(), eq(id));
        // No FAILED / retry update happened.
        verify(jdbc, never()).update(contains("status = 'FAILED'"), any(), any(), eq(id));
    }

    // ------------------------------------------------------------------
    //  Transient failure path -> retry with backoff
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void notifyFailure_belowMax_schedulesRetryAndStaysPending() {
        UUID id = UUID.randomUUID();
        OutboxDeliverySchedulerTestRows.stub(jdbc, new OutboxDeliverySchedulerTestRows.Row(id, "subscription.created", 0));
        // Make pg_notify throw; the retry UPDATE (4 args) must then succeed.
        when(jdbc.query(eq("SELECT pg_notify(?, ?)"), any(ResultSetExtractor.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("broker down"));

        scheduler().publishBatch();

        // attempts incremented to 1, last_error + next_attempt_at set, status NOT changed to FAILED.
        verify(jdbc).update(
                argThat(s -> s.contains("attempts = ?") && s.contains("next_attempt_at = ?")
                        && !s.contains("status = 'FAILED'")),
                eq(1), contains("broker down"), any(), eq(id));
        verify(jdbc, never()).update(contains("status = 'PUBLISHED'"), any(), eq(id));
    }

    // ------------------------------------------------------------------
    //  Poison message path -> FAILED
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void notifyFailure_atMaxAttempts_quarantinesAsFailed() {
        UUID id = UUID.randomUUID();
        // attempts already MAX-1 so this failure becomes the MAX'th and trips the poison cap.
        OutboxDeliverySchedulerTestRows.stub(jdbc,
                new OutboxDeliverySchedulerTestRows.Row(id, "subscription.created", OutboxDeliveryScheduler.MAX_ATTEMPTS - 1));
        when(jdbc.query(eq("SELECT pg_notify(?, ?)"), any(ResultSetExtractor.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("still down"));

        scheduler().publishBatch();

        verify(jdbc).update(contains("status = 'FAILED'"),
                eq(OutboxDeliveryScheduler.MAX_ATTEMPTS), contains("still down"), eq(id));
        verify(jdbc, never()).update(contains("next_attempt_at = ?"),
                any(), any(), any(), eq(id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneBadRow_doesNotAbortTheRest() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        OutboxDeliverySchedulerTestRows.stub(jdbc,
                new OutboxDeliverySchedulerTestRows.Row(bad, "a.evt", 0),
                new OutboxDeliverySchedulerTestRows.Row(good, "b.evt", 0));
        // Fail notify only for the first row's payload (contains its id), succeed for the rest.
        when(jdbc.query(eq("SELECT pg_notify(?, ?)"), any(ResultSetExtractor.class), any(), contains(bad.toString())))
                .thenThrow(new RuntimeException("boom"));

        scheduler().publishBatch();

        // good row still published despite bad row failing.
        verify(jdbc).update(contains("status = 'PUBLISHED'"), any(), eq(good));
    }

    // ------------------------------------------------------------------
    //  Retention sweep
    // ------------------------------------------------------------------

    @Test
    void purge_deletesOnlyTerminalRowsOlderThanWindow() {
        when(jdbc.update(anyString(), any(java.sql.Timestamp.class))).thenReturn(3);

        scheduler().purgeOldEvents();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(java.sql.Timestamp.class));
        String q = sql.getValue();
        assertThat(q).contains("DELETE FROM outbox_events");
        // Only delivered/quarantined rows are purged; PENDING rows are never removed.
        assertThat(q).contains("status IN ('PUBLISHED', 'FAILED')");
        // Only rows the webhook fan-out has already considered are eligible (never lose an event).
        assertThat(q).contains("fanned_out_at IS NOT NULL");
        assertThat(q).contains("occurred_at < ?");
    }

    // ------------------------------------------------------------------
    //  Backoff math
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "1, 5000",     // base
            "2, 10000",    // x2
            "3, 20000",
            "4, 40000",
    })
    void backoff_isExponentialFromBase(int attempts, long expectedMillis) {
        assertThat(OutboxDeliveryScheduler.backoff(attempts)).isEqualTo(Duration.ofMillis(expectedMillis));
    }

    @Test
    void backoff_isCappedAtOneHour() {
        // Large attempt counts must not overflow and must saturate at the cap.
        assertThat(OutboxDeliveryScheduler.backoff(30)).isEqualTo(OutboxDeliveryScheduler.BACKOFF_CAP);
        assertThat(OutboxDeliveryScheduler.backoff(1000)).isEqualTo(OutboxDeliveryScheduler.BACKOFF_CAP);
        assertThat(OutboxDeliveryScheduler.BACKOFF_CAP).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void entityDefaults_arePendingWithZeroAttempts() {
        OutboxEvent e = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("subscription")
                .aggregateId("sub-1")
                .eventType("subscription.created")
                .payloadJson("{}")
                .occurredAt(java.time.OffsetDateTime.now())
                .build();
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(e.getAttempts()).isZero();
        assertThat(e.getNextAttemptAt()).isNull();
        assertThat(e.getLastError()).isNull();
    }
}
