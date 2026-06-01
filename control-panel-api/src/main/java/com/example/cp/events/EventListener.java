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

@Component
@ConditionalOnProperty(prefix = "app.events.listener", name = "enabled", havingValue = "true")
public class EventListener {

    private static final Logger log = LoggerFactory.getLogger(EventListener.class);

    private final DataSource dataSource;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private Connection conn;

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

    private void run() {
        try {
            conn = dataSource.getConnection();
            try (Statement st = conn.createStatement()) {
                st.execute("LISTEN cp_events");
            }
            Class<?> pgConnClass = Class.forName("org.postgresql.PGConnection");
            Object pg = conn.unwrap(pgConnClass);
            Method getNotifications = pgConnClass.getMethod("getNotifications", int.class);
            Method getParameter = Class.forName("org.postgresql.PGNotification").getMethod("getParameter");
            while (running.get()) {
                try {
                    Object[] notifications = (Object[]) getNotifications.invoke(pg, (int) pollMs);
                    if (notifications != null) {
                        for (Object n : notifications) {
                            log.info("cp_events: {}", getParameter.invoke(n));
                        }
                    }
                } catch (Exception ex) {
                    if (running.get()) {
                        log.warn("EventListener notification poll failed: {}", ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("EventListener crashed: {}", e.getMessage());
        } finally {
            closeQuietly();
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (thread != null) thread.interrupt();
        closeQuietly();
    }

    private void closeQuietly() {
        if (conn != null) {
            try { conn.close(); } catch (Exception ignored) {}
            conn = null;
        }
    }
}
