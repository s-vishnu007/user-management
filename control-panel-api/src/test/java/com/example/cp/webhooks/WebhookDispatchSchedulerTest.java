package com.example.cp.webhooks;

import com.example.cp.events.OutboxEventRepository;
import com.example.cp.keys.KeyEncryptor;
import com.example.cp.licenses.LicenseTokenRepository;
import com.example.cp.subscriptions.SubscriptionRepository;
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
import org.springframework.jdbc.core.RowMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure, hermetic unit tests for {@link WebhookDispatchScheduler} — no Spring context, no DB, no real
 * network. {@link JdbcTemplate} and {@link HttpClient} are Mockito mocks; the claim query is stubbed
 * to return a synthetic joined row and the persisted outcome ({@code UPDATE}) is asserted via captured
 * SQL/args.
 *
 * <p>Covered:
 * <ul>
 *   <li>event-type CSV filter matching (incl. the null/blank = all-events rule);</li>
 *   <li>the claim query is PENDING + due-guarded + ordered + {@code FOR UPDATE ... SKIP LOCKED};</li>
 *   <li>a 2xx response marks the delivery DELIVERED and stamps {@code response_status}/{@code delivered_at};</li>
 *   <li>a non-2xx response increments attempts, records {@code last_error}, and schedules a retry while
 *       the row stays PENDING;</li>
 *   <li>a row on its last attempt is quarantined as FAILED;</li>
 *   <li>the signed POST carries the contracted headers (X-CP-Event/Delivery/Timestamp/Signature);</li>
 *   <li>capped exponential backoff math.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookDispatchSchedulerTest {

    @Mock private OutboxEventRepository outboxRepo;
    @Mock private WebhookSubscriptionRepository subRepo;
    @Mock private WebhookDeliveryRepository deliveryRepo;
    @Mock private SubscriptionRepository subscriptionRepo;
    @Mock private LicenseTokenRepository tokenRepo;
    @Mock private KeyEncryptor keyEncryptor;
    @Mock private JdbcTemplate jdbc;
    @Mock private HttpClient httpClient;

    private final WebhookSigner signer = new WebhookSigner();

    private WebhookDispatchScheduler scheduler() {
        WebhookDispatchScheduler s = new WebhookDispatchScheduler(
                outboxRepo, subRepo, deliveryRepo, subscriptionRepo, tokenRepo, signer, keyEncryptor,
                jdbc, Duration.ofMinutes(10));
        s.setHttpClientForTest(httpClient);
        return s;
    }

    // ------------------------------------------------------------------
    //  Event-type filter
    // ------------------------------------------------------------------

    @Test
    void matches_nullOrBlankFilter_acceptsEverything() {
        assertThat(WebhookDispatchScheduler.matches(null, "LicenseRevoked")).isTrue();
        assertThat(WebhookDispatchScheduler.matches("", "LicenseRevoked")).isTrue();
        assertThat(WebhookDispatchScheduler.matches("   ", "LicenseRevoked")).isTrue();
    }

    @Test
    void matches_csvFilter_isExactTrimmedTokenMatch() {
        assertThat(WebhookDispatchScheduler.matches("SubscriptionActivated, LicenseRevoked", "LicenseRevoked")).isTrue();
        assertThat(WebhookDispatchScheduler.matches("SubscriptionActivated,LicenseRevoked", "SubscriptionActivated")).isTrue();
        assertThat(WebhookDispatchScheduler.matches("SubscriptionActivated", "LicenseRevoked")).isFalse();
        assertThat(WebhookDispatchScheduler.matches("LicenseRevoked", null)).isFalse();
        // Not a prefix/substring match.
        assertThat(WebhookDispatchScheduler.matches("License", "LicenseRevoked")).isFalse();
    }

    // ------------------------------------------------------------------
    //  Claim query shape (multi-instance safety)
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void claimQuery_isPendingDueOrderedAndSkipLocked() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        scheduler().deliverDueBatch();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class));
        String q = sql.getValue();
        assertThat(q).contains("d.status = 'PENDING'");
        assertThat(q).contains("next_attempt_at IS NULL OR d.next_attempt_at <= now()");
        assertThat(q).contains("ORDER BY d.created_at ASC");
        assertThat(q).contains("FOR UPDATE OF d SKIP LOCKED");
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    // ------------------------------------------------------------------
    //  Delivery success
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void delivery_2xx_marksDeliveredWithHeaders() throws Exception {
        UUID id = UUID.randomUUID();
        stubOneClaimed(id, "LicenseRevoked", 0);
        when(keyEncryptor.decrypt(any())).thenReturn("sekret".getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(204);
        doReturn(resp).when(httpClient).send(any(HttpRequest.class), any());

        scheduler().deliverDueBatch();

        // DELIVERED update with the observed status (204) for this row.
        verify(jdbc).update(contains("status = 'DELIVERED'"), eq(1), eq(204), any(), eq(id));
        verify(jdbc, never()).update(contains("status = 'FAILED'"), any(), any(), any(), any());

        // The POST carries the contracted signed headers.
        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(req.capture(), any());
        var headers = req.getValue().headers();
        assertThat(headers.firstValue("X-CP-Event")).hasValue("LicenseRevoked");
        assertThat(headers.firstValue("X-CP-Delivery")).hasValue(id.toString());
        assertThat(headers.firstValue("X-CP-Timestamp")).isPresent();
        assertThat(headers.firstValue("X-CP-Signature")).isPresent();
        assertThat(headers.firstValue("X-CP-Signature").get()).startsWith("sha256=");
        assertThat(req.getValue().method()).isEqualTo("POST");
    }

    // ------------------------------------------------------------------
    //  Delivery failure -> retry with backoff (stays PENDING)
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void delivery_non2xx_belowMax_schedulesRetryAndStaysPending() throws Exception {
        UUID id = UUID.randomUUID();
        stubOneClaimed(id, "LicenseRevoked", 0);
        when(keyEncryptor.decrypt(any())).thenReturn("sekret".getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(500);
        doReturn(resp).when(httpClient).send(any(HttpRequest.class), any());

        scheduler().deliverDueBatch();

        verify(jdbc).update(
                argThat(s -> s.contains("attempts = ?") && s.contains("next_attempt_at = ?")
                        && !s.contains("status = 'FAILED'") && !s.contains("status = 'DELIVERED'")),
                eq(1), eq(500), any(), contains("non-2xx"), eq(id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void delivery_ioError_schedulesRetry() throws Exception {
        UUID id = UUID.randomUUID();
        stubOneClaimed(id, "LicenseRevoked", 0);
        when(keyEncryptor.decrypt(any())).thenReturn("sekret".getBytes(StandardCharsets.UTF_8));
        doThrow(new java.io.IOException("connection refused"))
                .when(httpClient).send(any(HttpRequest.class), any());

        scheduler().deliverDueBatch();

        verify(jdbc).update(
                argThat(s -> s.contains("next_attempt_at = ?") && !s.contains("status = 'FAILED'")),
                eq(1), eq((Integer) null), any(), contains("io error"), eq(id));
    }

    // ------------------------------------------------------------------
    //  Poison message -> FAILED
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void delivery_atMaxAttempts_quarantinesAsFailed() throws Exception {
        UUID id = UUID.randomUUID();
        // attempts already MAX-1 so this failure becomes the MAX'th and trips the poison cap.
        stubOneClaimed(id, "LicenseRevoked", WebhookDispatchScheduler.MAX_ATTEMPTS - 1);
        when(keyEncryptor.decrypt(any())).thenReturn("sekret".getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(503);
        doReturn(resp).when(httpClient).send(any(HttpRequest.class), any());

        scheduler().deliverDueBatch();

        verify(jdbc).update(contains("status = 'FAILED'"),
                eq(WebhookDispatchScheduler.MAX_ATTEMPTS), eq(503), contains("non-2xx"), eq(id));
        verify(jdbc, never()).update(contains("next_attempt_at = ?"), anyInt(), any(), any(), any(), eq(id));
    }

    // ------------------------------------------------------------------
    //  Backoff math
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "1, 10000",
            "2, 20000",
            "3, 40000",
            "4, 80000",
    })
    void backoff_isExponentialFromBase(int attempts, long expectedMillis) {
        assertThat(WebhookDispatchScheduler.backoff(attempts)).isEqualTo(Duration.ofMillis(expectedMillis));
    }

    @Test
    void backoff_isCappedAtOneHour() {
        assertThat(WebhookDispatchScheduler.backoff(30)).isEqualTo(WebhookDispatchScheduler.BACKOFF_CAP);
        assertThat(WebhookDispatchScheduler.backoff(1000)).isEqualTo(WebhookDispatchScheduler.BACKOFF_CAP);
        assertThat(WebhookDispatchScheduler.BACKOFF_CAP).isEqualTo(Duration.ofHours(1));
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubOneClaimed(UUID id, String eventType, int attempts) {
        WebhookDispatchScheduler.ClaimedDelivery row = new WebhookDispatchScheduler.ClaimedDelivery(
                id, UUID.randomUUID(), UUID.randomUUID(), attempts,
                "https://hooks.example.com/endpoint", new byte[]{1, 2, 3},
                eventType, "license_token", "jti-123", "{\"jti\":\"jti-123\"}");
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<WebhookDispatchScheduler.ClaimedDelivery> rm = inv.getArgument(1);
                    // Bypass the RowMapper (no real ResultSet); return the synthetic row directly.
                    return List.of(row);
                });
    }
}
