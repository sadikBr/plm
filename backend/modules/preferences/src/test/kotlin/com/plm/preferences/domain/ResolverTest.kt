package com.plm.preferences.domain

import com.plm.access.api.ContextId
import com.plm.preferences.api.PreferenceDefinition
import com.plm.preferences.api.PreferenceLevel
import com.plm.preferences.api.PreferenceSource
import com.plm.preferences.api.PreferenceType
import com.plm.preferences.api.PreferenceValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ResolverTest {
    private val site = ContextId(UUID.randomUUID())
    private val org = ContextId(UUID.randomUUID())
    private val product = ContextId(UUID.randomUUID())
    private val def =
        PreferenceDefinition(
            "ui.pageSize",
            "Rows",
            "",
            "Display",
            PreferenceType.INTEGER,
            50,
            PreferenceLevel.entries.toSet(),
        )

    private fun v(
        value: Int,
        locked: Boolean = false,
    ) = PreferenceValue("ui.pageSize", value, locked, Instant.EPOCH, "x")

    @Test
    fun `nothing set gives the default`() {
        val e = Resolver.resolve(def, listOf(site to null, org to null), null)
        assertThat(e.value).isEqualTo(50)
        assertThat(e.source).isEqualTo(PreferenceSource.DEFAULT)
    }

    @Test
    fun `the nearest context wins, then the user`() {
        val e = Resolver.resolve(def, listOf(site to v(20), org to v(30), product to null), null)
        assertThat(e.value).isEqualTo(30)
        assertThat(e.sourceContextId).isEqualTo(org)
        assertThat(Resolver.resolve(def, listOf(site to v(20), org to v(30)), v(99)).value).isEqualTo(99)
    }

    @Test
    fun `a lock stops everything below, users included`() {
        val e = Resolver.resolve(def, listOf(site to v(20), org to v(30, locked = true), product to v(40)), v(99))
        assertThat(e.value).isEqualTo(30)
        assertThat(e.lockedAt).isEqualTo(org)
    }

    @Test
    fun `user values only count where users may set the preference`() {
        val contextOnly = def.copy(levels = setOf(PreferenceLevel.SITE, PreferenceLevel.CONTEXT))
        assertThat(Resolver.resolve(contextOnly, listOf(site to v(20)), v(99)).value).isEqualTo(20)
    }
}
