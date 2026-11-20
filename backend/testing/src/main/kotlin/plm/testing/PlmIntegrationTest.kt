// Outside `com.plm` on purpose: application component scans must never pick up test support.
package plm.testing

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Boots a Spring context with every `com.plm` module on the test classpath, against a real
 * Postgres (Testcontainers) with all Flyway migrations applied.
 *
 * Use it from a module's tests to exercise that module together with its declared dependencies,
 * without the full application.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(classes = [PlmTestApplication::class])
@Import(PostgresTestConfiguration::class)
annotation class PlmIntegrationTest

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("com.plm")
class PlmTestApplication

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {
    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))

    /** Replaces the system clock, so tests can pin and advance time. */
    @Bean
    @Primary
    fun testClock() = MutableClock()

    companion object {
        /** Keep in step with deploy/docker-compose.yml. */
        const val POSTGRES_IMAGE = "postgres:18-alpine"
    }
}
