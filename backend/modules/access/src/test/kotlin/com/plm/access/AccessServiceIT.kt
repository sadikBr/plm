package com.plm.access

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.Permission
import com.plm.access.api.Permission.CREATE
import com.plm.access.api.Permission.MANAGE
import com.plm.access.api.Permission.MODIFY
import com.plm.access.api.Permission.PROMOTE
import com.plm.access.api.Permission.READ
import com.plm.access.api.Permission.REVISE
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.id.UuidV7
import com.plm.types.api.TypeId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class AccessServiceIT(
    @Autowired private val access: AccessService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val jdbc: JdbcClient,
) {
    private val admin = Actors.admin

    private inner class World {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Org ${UUID.randomUUID()}"),
            )
        val drone = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Drone"))
        val rover = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover"))
        val manager = Actors.user("manager").also { teams.put(admin, org.id, it.principal, TeamRole.MANAGER) }
        val member = Actors.user("member").also { teams.put(admin, drone.id, it.principal, TeamRole.MEMBER) }
        val viewer = Actors.user("viewer").also { teams.put(admin, org.id, it.principal, TeamRole.VIEWER) }
        val outsider = Actors.user("outsider")
    }

    private fun perms(
        actor: Actor,
        context: ContextId,
        type: TypeId? = TypeId.PART,
        state: String? = "IN_WORK",
    ) = access.permissions(actor, AccessTarget(context, type, state))

    private fun addPolicy(
        context: ContextId,
        role: TeamRole,
        permissions: Set<Permission>,
        type: TypeId? = null,
        state: String? = null,
    ) {
        jdbc
            .sql(
                """
                insert into access_policy (id, tenant_id, context_id, role, type_id, state, permissions)
                values (:id, '00000000-0000-7000-8000-000000000001', :context, :role, :type, :state, :bits)
                """,
            ).param("id", UuidV7.generate())
            .param("context", context.value)
            .param("role", role.key)
            .param("type", type?.value)
            .param("state", state)
            .param("bits", permissions.sumOf { it.bit })
            .update()
    }

    @Test
    fun `default policies grant by role, inherited down the tree`() {
        val w = World()

        assertThat(
            perms(w.manager, w.drone.id),
        ).containsExactlyInAnyOrder(READ, CREATE, MODIFY, REVISE, MANAGE, PROMOTE)
        assertThat(perms(w.member, w.drone.id)).containsExactlyInAnyOrder(READ, CREATE, MODIFY, REVISE)
        assertThat(perms(w.viewer, w.drone.id)).containsExactly(READ)
        assertThat(perms(w.outsider, w.drone.id)).isEmpty()
        // Membership of Drone says nothing about its sibling.
        assertThat(perms(w.member, w.rover.id)).isEmpty()
        assertThat(perms(admin, w.rover.id)).containsExactlyInAnyOrder(*Permission.entries.toTypedArray())
    }

    @Test
    fun `policies can target one type or one lifecycle state`() {
        val w = World()
        // Viewers of Drone may also edit documents (but not parts)...
        addPolicy(w.drone.id, TeamRole.VIEWER, setOf(MODIFY), type = TypeId.DOCUMENT)
        // ...and may revise anything once it is RELEASED.
        addPolicy(w.drone.id, TeamRole.VIEWER, setOf(REVISE), state = "RELEASED")

        assertThat(perms(w.viewer, w.drone.id, TypeId.DOCUMENT)).containsExactlyInAnyOrder(READ, MODIFY)
        assertThat(perms(w.viewer, w.drone.id, TypeId.PART)).containsExactly(READ)
        assertThat(perms(w.viewer, w.drone.id, TypeId.PART, "RELEASED")).containsExactlyInAnyOrder(READ, REVISE)
        assertThat(perms(w.viewer, w.rover.id, TypeId.DOCUMENT)).containsExactly(READ)
    }

    @Test
    fun `the SQL filter agrees with single-object checks`() {
        val w = World()
        addPolicy(w.drone.id, TeamRole.VIEWER, setOf(MODIFY), type = TypeId.DOCUMENT)
        addPolicy(w.rover.id, TeamRole.MEMBER, setOf(READ), state = "RELEASED")

        val rows =
            listOf(w.org.id, w.drone.id, w.rover.id).flatMap { ctx ->
                listOf(TypeId.PART, TypeId.DOCUMENT).flatMap { type ->
                    listOf("IN_WORK", "RELEASED").map { state -> Triple(ctx, type, state) }
                }
            }
        val values =
            rows.indices.joinToString {
                "(cast(:c$it as uuid), cast(:t$it as uuid), cast(:s$it as text), $it)"
            }

        for (actor in listOf(w.manager, w.member, w.viewer, w.outsider, admin)) {
            for (permission in Permission.entries) {
                val predicate = access.filter(actor, permission, AccessColumns("r.ctx", "r.type_id", "r.state"))
                var query =
                    jdbc
                        .sql("select r.idx from (values $values) as r(ctx, type_id, state, idx) where ${predicate.sql}")
                        .params(predicate.params)
                rows.forEachIndexed { i, (c, t, s) ->
                    query = query.param("c$i", c.value).param("t$i", t.value).param("s$i", s)
                }
                val viaSql = query.query(Int::class.java).list().toSet()
                val viaCheck =
                    rows.indices
                        .filter { i ->
                            permission in
                                perms(actor, rows[i].first, rows[i].second, rows[i].third)
                        }.toSet()

                assertThat(viaSql).describedAs("${actor.principal} / $permission").isEqualTo(viaCheck)
            }
        }
    }

    @Test
    fun `team changes need MANAGE`() {
        val w = World()

        assertThatThrownBy { teams.put(w.member, w.drone.id, w.outsider.principal, TeamRole.VIEWER) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ACCESS_DENIED") }

        teams.put(w.manager, w.drone.id, w.outsider.principal, TeamRole.VIEWER)
        assertThat(perms(w.outsider, w.drone.id)).containsExactly(READ)

        teams.put(w.manager, w.drone.id, w.outsider.principal, TeamRole.MEMBER)
        assertThat(perms(w.outsider, w.drone.id)).contains(MODIFY)

        teams.remove(w.manager, w.drone.id, w.outsider.principal)
        assertThat(perms(w.outsider, w.drone.id)).isEmpty()
        assertThatThrownBy { teams.remove(w.manager, w.drone.id, w.outsider.principal) }
            .isInstanceOfSatisfying(NotFoundException::class.java) { assertThat(it.code).isEqualTo("MEMBER_NOT_FOUND") }
    }

    @Test
    fun `the filter refuses anything but plain column references`() {
        assertThatThrownBy {
            access.filter(
                Actors.user("x"),
                READ,
                AccessColumns("m.context_id; drop table x", "m.t", "m.s"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
