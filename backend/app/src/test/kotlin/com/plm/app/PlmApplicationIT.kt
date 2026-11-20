package com.plm.app

import com.plm.app.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

class PlmApplicationIT : ApiTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `application boots against a real Postgres and reports healthy without a token`() {
        val health = get("/actuator/health", as_ = null)

        assertThat(health.status).isEqualTo(200)
        assertThat(health.body["status"]).isEqualTo("UP")
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

        assertThat(tables).contains(
            "tenant",
            "context",
            "type_def",
            "object_master",
            "object_iteration",
            "outbox_event",
            "audit_log",
            "principal",
            "context_closure",
            "context_member",
            "access_policy",
        )
    }
}
