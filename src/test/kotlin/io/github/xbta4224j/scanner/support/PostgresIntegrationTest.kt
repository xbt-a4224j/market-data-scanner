package io.github.xbta4224j.scanner.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared Testcontainers Postgres + pgvector base. Subclass and add @SpringBootTest
 * (or a slice annotation) to get a real database with all Flyway migrations applied.
 *
 * One container is started ONCE per test JVM via the static initializer below
 * (intentionally not via @Container / @Testcontainers - the JUnit extension would
 * tear down the container between test classes, invalidating Spring's context
 * cache and the cached HikariCP datasource). The singleton stays alive for the
 * lifetime of the JVM and is reaped by the testcontainers Ryuk sidecar.
 *
 * Tests should isolate their own writes (typically via @Transactional on the
 * test class) since the schema is shared across the run.
 */
abstract class PostgresIntegrationTest {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("scanner")
                .withUsername("scanner")
                .withPassword("scanner")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
