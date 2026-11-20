package com.plm.preferences

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.preferences.api.PreferenceService
import com.plm.preferences.api.PreferenceSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

private const val PAGE_SIZE = "ui.tablePageSize"

@PlmIntegrationTest
class PreferenceServiceIT(
    @Autowired private val preferences: PreferenceService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)

    private fun user(name: String) = Actor(TenantId.DEFAULT, PrincipalId("$name-${UUID.randomUUID()}"))

    private inner class World {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Org ${UUID.randomUUID()}"),
            )
        val product = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "P"))
        val manager = user("manager").also { teams.put(admin, product.id, it.principal, TeamRole.MANAGER) }
        val orgManager = user("org-manager").also { teams.put(admin, org.id, it.principal, TeamRole.MANAGER) }
        val member = user("member").also { teams.put(admin, product.id, it.principal, TeamRole.MEMBER) }
    }

    private fun pageSize(
        actor: Actor,
        context: ContextId?,
    ) = preferences.effective(actor, context).single { it.key == PAGE_SIZE }

    @Test
    fun `values inherit down the tree and the nearest one wins`() {
        val w = World()
        assertThat(pageSize(w.member, w.product.id).value).isEqualTo(50)

        preferences.setForContext(w.orgManager, w.org.id, PAGE_SIZE, 100, locked = false)
        assertThat(pageSize(w.member, w.product.id).value).isEqualTo(100)
        assertThat(pageSize(w.member, w.product.id).sourceContextId).isEqualTo(w.org.id)

        preferences.setForContext(w.manager, w.product.id, PAGE_SIZE, 25, locked = false)
        assertThat(pageSize(w.member, w.product.id).value).isEqualTo(25)

        // Users override where nothing is locked.
        preferences.setForUser(w.member, PAGE_SIZE, 200)
        assertThat(pageSize(w.member, w.product.id).value).isEqualTo(200)
        assertThat(pageSize(w.member, w.product.id).source).isEqualTo(PreferenceSource.USER)
        preferences.clearForUser(w.member, PAGE_SIZE)
        assertThat(pageSize(w.member, w.product.id).value).isEqualTo(25)
    }

    @Test
    fun `a lock wins over everything below it, users included`() {
        val w = World()
        preferences.setForContext(w.manager, w.product.id, PAGE_SIZE, 25, locked = false)
        preferences.setForUser(w.member, PAGE_SIZE, 200)

        preferences.setForContext(w.orgManager, w.org.id, PAGE_SIZE, 40, locked = true)

        val effective = pageSize(w.member, w.product.id)
        assertThat(effective.value).isEqualTo(40)
        assertThat(effective.lockedAt).isEqualTo(w.org.id)
        val here = preferences.forContext(w.manager, w.product.id).single { it.definition.key == PAGE_SIZE }
        assertThat(here.canSet).isFalse()
        assertThat(here.own?.value).isEqualTo(25) // kept, just not in effect
        assertThatThrownBy { preferences.setForContext(w.manager, w.product.id, PAGE_SIZE, 30, locked = false) }
            .isInstanceOfSatisfying(
                ConflictException::class.java,
            ) { assertThat(it.code).isEqualTo("PREFERENCE_LOCKED") }
        // The context that locked it can still change it.
        preferences.setForContext(w.orgManager, w.org.id, PAGE_SIZE, 60, locked = true)
        assertThat(pageSize(w.member, w.product.id).value).isEqualTo(60)
        preferences.clearForContext(w.orgManager, w.org.id, PAGE_SIZE)
        assertThat(pageSize(w.member, w.product.id).value).isEqualTo(200)
    }

    @Test
    fun `setting needs MANAGE here, site admins for the site, and a valid value`() {
        val w = World()
        assertThatThrownBy { preferences.setForContext(w.member, w.product.id, PAGE_SIZE, 30, false) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ACCESS_DENIED") }
        assertThatThrownBy { preferences.setForContext(w.orgManager, ContextId.DEFAULT_SITE, PAGE_SIZE, 30, false) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ADMIN_REQUIRED") }
        assertThatThrownBy { preferences.setForContext(w.manager, w.product.id, PAGE_SIZE, 5, false) }
            .isInstanceOfSatisfying(
                ValidationException::class.java,
            ) { assertThat(it.code).isEqualTo("INVALID_PREFERENCE_VALUE") }
        assertThatThrownBy { preferences.setForUser(w.member, PAGE_SIZE, "lots") }
            .isInstanceOfSatisfying(
                ValidationException::class.java,
            ) { assertThat(it.code).isEqualTo("INVALID_PREFERENCE_VALUE") }
        assertThatThrownBy { preferences.setForUser(w.member, "no.suchThing", 1) }
            .isInstanceOfSatisfying(
                NotFoundException::class.java,
            ) { assertThat(it.code).isEqualTo("PREFERENCE_NOT_FOUND") }
        assertThat(
            preferences.forContext(w.member, w.product.id).single { it.definition.key == PAGE_SIZE }.canSet,
        ).isFalse()
    }

    @Test
    fun `site admins set site-wide values, and a site lock stops users`() {
        val w = World()
        try {
            preferences.setForContext(admin, ContextId.DEFAULT_SITE, PAGE_SIZE, 75, locked = true)
            assertThat(pageSize(w.member, null).value).isEqualTo(75)
            assertThat(preferences.forUser(w.member).single { it.definition.key == PAGE_SIZE }.canSet).isFalse()
            assertThatThrownBy { preferences.setForUser(w.member, PAGE_SIZE, 100) }
                .isInstanceOfSatisfying(
                    ConflictException::class.java,
                ) { assertThat(it.code).isEqualTo("PREFERENCE_LOCKED") }
        } finally {
            preferences.clearForContext(admin, ContextId.DEFAULT_SITE, PAGE_SIZE)
        }
    }
}
