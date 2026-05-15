package io.github.xbta4224j.scanner

import io.github.xbta4224j.scanner.support.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * Issue #1 acceptance: Spring context loads against a real Postgres (Testcontainers + pgvector).
 * Validates that all auto-configurations wire successfully and Flyway applies V1..V3 migrations
 * against a pgvector-enabled Postgres without errors.
 */
@SpringBootTest
class ScannerApplicationTest : PostgresIntegrationTest() {

    @Test
    fun contextLoads() {
        // Pass = the Spring context wired up, Flyway applied, all beans resolved.
    }
}
