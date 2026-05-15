package io.github.xbta4224j.scanner

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Issue #1 acceptance: Spring context loads against a real Postgres (Testcontainers + pgvector).
 * Validates that all auto-configurations wire successfully and Flyway applies V1..V3 migrations
 * against a pgvector-enabled Postgres without errors.
 */
@SpringBootTest
@Testcontainers
class ScannerApplicationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
        ).withDatabaseName("scanner")
            .withUsername("scanner")
            .withPassword("scanner")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Test
    fun contextLoads() {
        // Pass = the Spring context wired up, Flyway applied, all beans resolved.
    }
}
