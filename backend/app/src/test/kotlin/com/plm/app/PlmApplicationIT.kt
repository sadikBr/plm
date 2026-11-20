package com.plm.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.RestClient
import plm.testing.PostgresTestConfiguration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class)
class PlmApplicationIT {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `application boots against a real Postgres and reports healthy`() {
        val health =
            RestClient
                .create("http://localhost:$port")
                .get()
                .uri("/actuator/health")
                .retrieve()
                .body(String::class.java)

        assertThat(health).contains("\"status\":\"UP\"")
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int", Int::class.java))
            .isGreaterThanOrEqualTo(180000)
    }

    @Test
    fun `every module's migrations are applied`() {
        val tables =
            jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String::class.java,
            )

        assertThat(tables).contains("tenant", "context", "type_def", "object_master", "object_iteration")
    }
}
