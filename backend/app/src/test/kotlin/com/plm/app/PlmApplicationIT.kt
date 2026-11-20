package com.plm.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.RestClient
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PlmApplicationIT.Containers::class)
class PlmApplicationIT {
    @TestConfiguration(proxyBeanMethods = false)
    class Containers {
        @Bean
        @ServiceConnection
        fun postgres() = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }

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
}
