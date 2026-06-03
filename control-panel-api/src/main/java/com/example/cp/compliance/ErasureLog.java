package com.example.cp.compliance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable record of a GDPR/CCPA data-subject request (right of access / portability, right to
 * erasure, or tenant off-boarding). One row is written per request; {@code completedAt} is set when
 * the operation finishes, so a started-but-failed request stays distinguishable from a completed one.
 *
 * <p>This is a deliberately small, PII-free ledger: it records WHAT subject was acted on, by WHOM,
 * and WHEN — never the personal data itself. It complements (does not replace) the {@code audit_log}
 * rows the controller emits, and is what a DPO/auditor consults to evidence that DSARs were honoured.
 *
 * <p>Maps to migration {@code 15-compliance.sql} (table {@code erasure_log}).
 */
@Entity
@Table(name = "erasure_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErasureLog {

    /** Kind of data subject the request targets. Persisted lowercase to match the DB CHECK. */
    public enum SubjectType {
        USER("user"),
        ORG("org");

        private final String db;

        SubjectType(String db) {
            this.db = db;
        }

        public String db() {
            return db;
        }
    }

    /** Which DSAR operation this row records. Persisted lowercase to match the DB CHECK. */
    public enum Action {
        EXPORT("export"),
        ERASE("erase");

        private final String db;

        Action(String db) {
            this.db = db;
        }

        public String db() {
            return db;
        }
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "subject_type", nullable = false, length = 16)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "action", nullable = false, length = 16)
    private String action;
}
