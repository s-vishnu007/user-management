package com.example.cp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full application context against a Testcontainers Postgres (jdbc:tc URL in
 * application-test.yml). This exercises the whole chain that a 10-bucket parallel refactor can break:
 * Liquibase runs every migration (00..13..99), JPA ddl-auto=validate checks every entity against the
 * migrated schema, and all beans (security, tenant access, session revocation, audit, CRL, etc.) wire.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContextLoadsTest {

    @Test
    void contextLoads() {
        // Success = context started: migrations applied, schema validated, all beans wired.
    }
}
