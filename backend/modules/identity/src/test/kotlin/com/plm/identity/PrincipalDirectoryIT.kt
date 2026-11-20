package com.plm.identity

import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.MutableClock
import plm.testing.PlmIntegrationTest
import java.time.Duration
import java.util.UUID

@PlmIntegrationTest
class PrincipalDirectoryIT(
    @Autowired private val directory: PrincipalDirectory,
    @Autowired private val clock: MutableClock,
) {
    private val actor = Actor(TenantId.DEFAULT, PrincipalId("someone"))

    @Test
    fun `sign-in creates, then refreshes, the directory entry`() {
        val id = PrincipalId(UUID.randomUUID().toString())
        val username = "u" + id.value.take(8)
        directory.recordSignIn(SignIn(TenantId.DEFAULT, id, username, "Dana Doe", "dana@example.com"))
        val first = directory.get(actor, id)

        clock.advance(Duration.ofHours(1))
        directory.recordSignIn(SignIn(TenantId.DEFAULT, id, username, "Dana Smith", "dana@example.com"))
        val second = directory.get(actor, id)

        assertThat(second.displayName).isEqualTo("Dana Smith")
        assertThat(second.firstSeen).isEqualTo(first.firstSeen)
        assertThat(second.lastSeen).isAfter(first.lastSeen)
        assertThat(directory.findByUsername(actor, username.uppercase())?.id).isEqualTo(id)
        assertThat(directory.displayName(TenantId.DEFAULT, id)).isEqualTo("Dana Smith")
    }

    @Test
    fun `unknown users fall back to their id for display`() {
        assertThat(directory.displayName(TenantId.DEFAULT, PrincipalId("ghost"))).isEqualTo("ghost")
    }
}
