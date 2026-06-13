package com.example.cp.events;

import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test fixture that stubs {@link JdbcTemplate#query(String, RowMapper)} so the production
 * {@link OutboxDeliveryScheduler}'s real (private) {@code ClaimedRow} {@link RowMapper} is exercised
 * against a synthetic {@link ResultSet}. This keeps the scheduler's mapping logic under test without
 * exposing its private record, and without a database.
 */
final class OutboxDeliverySchedulerTestRows {

    private OutboxDeliverySchedulerTestRows() {
    }

    /** A claimed outbox row as the scheduler's SELECT would surface it. */
    record Row(UUID id, String eventType, int attempts) {
    }

    /**
     * Arranges the mock {@code jdbc} so that the claim query returns the given rows by running the
     * scheduler's own {@link RowMapper} over a stubbed {@link ResultSet} per row.
     */
    @SuppressWarnings("unchecked")
    static void stub(JdbcTemplate jdbc, Row... rows) {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer((InvocationOnMock inv) -> {
            RowMapper<Object> mapper = inv.getArgument(1, RowMapper.class);
            List<Object> out = new ArrayList<>();
            int n = 0;
            for (Row row : rows) {
                out.add(mapper.mapRow(resultSetFor(row), n++));
            }
            return out;
        });
    }

    private static ResultSet resultSetFor(Row row) throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(row.id());
        when(rs.getString("aggregate_type")).thenReturn("aggregate");
        when(rs.getString("aggregate_id")).thenReturn("agg-1");
        when(rs.getString("event_type")).thenReturn(row.eventType());
        when(rs.getInt("attempts")).thenReturn(row.attempts());
        return rs;
    }
}
