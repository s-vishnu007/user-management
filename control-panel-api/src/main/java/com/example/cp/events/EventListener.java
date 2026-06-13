package com.example.cp.events;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Postgres {@code LISTEN} consumer for the {@code cp_events} channel (disabled by default;
 * gated on {@code app.events.listener.enabled=true}). It is a diagnostic tail of the outbox NOTIFY
 * stream — the durable delivery path is the scheduler-driven {@link OutboxPublisher}; this listener
 * only logs received notifications.
 *
 * <p>Resilience: the listener uses its OWN dedicated JDBC connection (a long-lived blocking LISTEN
 * connection must not borrow/pin a pool connection for the lifetime of the app). If the connection
 * drops or the initial connect fails, the loop does NOT die permanently: it logs, closes the bad
 * connection, sleeps for a capped exponential backoff, and reconnects — re-issuing {@code LISTEN}.
 * It only stops when {@link #stop()} flips {@code running} to false on shutdown.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.events.listener", name = "enabled", havingValue = "true")
public class EventListener {

    private static final Logger log = LoggerFactory.getLogger(EventListener.class);

    private static final long BACKOFF_BASE_MS = 1_000L;
    private static final long BACKOFF_CAP_MS = 60_000L;

    private final DataSource dataSource;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private volatile Connection conn;

    @Value("${app.events.listener.poll-ms:1000}")
    private long pollMs;

    public EventListener(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void start() {
        running.set(true);
        thread = new Thread(this::run, "outbox-listener");
        thread.setDaemon(true);
        thread.start();
        log.info("Outbox EventListener started, channel=cp_events");
    }

    /**
     * Outer supervision loop: (re)establishes the dedicated LISTEN connection and consumes
     * notifications. Any failure breaks out of {@link #consume()}, after which we back off and
     * reconnect rather than terminating the thread.
     */
    private void run() {
        int failures = 0;
        while (running.get()) {
            try {
                openAndListen();
                failures = 0; // healthy connection established; reset backoff.
                consume();
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                failures++;
                log.warn("EventListener connection failed (attempt {}): {}; reconnecting after backoff",
                        failures, e.getMessage());
            } finally {
                closeQuietly();
            }
            if (running.get()) {
                sleepBackoff(failures);
            }
        }
        log.info("Outbox EventListener stopped");
    }

    private void openAndListen() throws Exception {
        conn = dataSource.getConnection();
        try (Statement st = conn.createStatement()) {
            st.execute("LISTEN cp_events");
        }
    }

    /** Blocks polling for notifications until the connection breaks (throws) or shutdown is requested. */
    private void consume() throws Exception {
        Class<?> pgConnClass = Class.forName("org.postgresql.PGConnection");
        Object pg = conn.unwrap(pgConnClass);
        Method getNotifications = pgConnClass.getMethod("getNotifications", int.class);
        Method getParameter = Class.forName("org.postgresql.PGNotification").getMethod("getParameter");
        while (running.get()) {
            // A poll failure here usually means the connection died — propagate so the outer loop
            // reconnects, instead of silently spinning on a dead connection forever.
            Object[] notifications = (Object[]) getNotifications.invoke(pg, (int) pollMs);
            if (notifications != null) {
                for (Object n : notifications) {
                    log.info("cp_events: {}", getParameter.invoke(n));
                }
            }
            if (conn.isClosed()) {
                throw new IllegalStateException("LISTEN connection closed");
            }
        }
    }

    /** Capped exponential backoff between reconnect attempts: BASE * 2^(failures-1), <= CAP. */
    private void sleepBackoff(int failures) {
        int shift = Math.max(0, failures - 1);
        long delay = shift >= 16 ? BACKOFF_CAP_MS : Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << shift);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (thread != null) thread.interrupt();
        closeQuietly();
    }

    private void closeQuietly() {
        Connection c = conn;
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
            conn = null;
        }
    }
}
