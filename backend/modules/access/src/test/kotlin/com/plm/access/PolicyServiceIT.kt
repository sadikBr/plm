package com.plm.access

import com.plm.access.api.AccessColumns
import com.plm.access.api.AccessService
import com.plm.access.api.AccessTarget
import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.CreatePolicy
import com.plm.access.api.Permission
import com.plm.access.api.Permission.CREATE
import com.plm.access.api.Permission.MODIFY
import com.plm.access.api.Permission.PROMOTE
import com.plm.access.api.Permission.READ
import com.plm.access.api.Permission.REVISE
import com.plm.access.api.PolicyEffect.DENY
import com.plm.access.api.PolicyService
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamRoleService
import com.plm.access.api.TeamRoleSettings
import com.plm.access.api.TeamService
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.types.api.CreateSubtype
import com.plm.types.api.TypeId
import com.plm.types.api.TypeService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import plm.testing.PlmIntegrationTest
import java.util.UUID

@PlmIntegrationTest
class PolicyServiceIT(
    @Autowired private val access: AccessService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val policies: PolicyService,
    @Autowired private val roles: TeamRoleService,
    @Autowired private val types: TypeService,
    @Autowired private val jdbc: JdbcClient,
) {
    private val admin = Actors.admin

    private fun unique(prefix: String) =
        prefix +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
                .uppercase()

    private inner class World {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Org ${UUID.randomUUID()}"),
            )
        val drone = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Drone"))
        val manager = Actors.user("manager").also { teams.put(admin, org.id, it.principal, TeamRole.MANAGER) }
        val member = Actors.user("member").also { teams.put(admin, drone.id, it.principal, TeamRole.MEMBER) }
        val viewer = Actors.user("viewer").also { teams.put(admin, drone.id, it.principal, TeamRole.VIEWER) }
        val fastener = types.createSubtype(admin, CreateSubtype(TypeId.PART, unique("Fastener"), "Fastener")).id
    }

    private fun perms(
        actor: Actor,
        context: ContextId,
        type: TypeId? = TypeId.PART,
        state: String? = "IN_WORK",
    ) = access.permissions(actor, AccessTarget(context, type, state))

    @Test
    fun `a policy on a type also applies to its subtypes`() {
        val w = World()
        policies.create(
            w.manager,
            w.drone.id,
            CreatePolicy(TeamRole.VIEWER, typeId = TypeId.PART, permissions = setOf(MODIFY)),
        )

        assertThat(perms(w.viewer, w.drone.id, w.fastener)).containsExactlyInAnyOrder(READ, MODIFY)
        assertThat(perms(w.viewer, w.drone.id, TypeId.PART)).containsExactlyInAnyOrder(READ, MODIFY)
        assertThat(perms(w.viewer, w.drone.id, TypeId.DOCUMENT)).containsExactly(READ)

        // A policy on the subtype doesn't reach its parent type.
        policies.create(
            w.manager,
            w.drone.id,
            CreatePolicy(TeamRole.VIEWER, typeId = w.fastener, permissions = setOf(REVISE)),
        )
        assertThat(perms(w.viewer, w.drone.id, w.fastener)).contains(REVISE)
        assertThat(perms(w.viewer, w.drone.id, TypeId.PART)).doesNotContain(REVISE)
    }

    @Test
    fun `deny takes permissions away whatever any allow says`() {
        val w = World()
        // Members of this product may not edit fasteners, even though the site lets members edit everything.
        policies.create(
            w.manager,
            w.drone.id,
            CreatePolicy(TeamRole.MEMBER, typeId = w.fastener, effect = DENY, permissions = setOf(MODIFY)),
        )

        assertThat(perms(w.member, w.drone.id, w.fastener)).containsExactlyInAnyOrder(READ, CREATE, REVISE)
        assertThat(perms(w.member, w.drone.id, TypeId.PART)).contains(MODIFY)
        // The deny is on the MEMBER role only; site admins are never limited.
        assertThat(perms(w.manager, w.drone.id, w.fastener)).contains(MODIFY)
        assertThat(perms(admin, w.drone.id, w.fastener)).contains(MODIFY)
    }

    @Test
    fun `the SQL filter agrees with single checks, with denies and subtypes`() {
        val w = World()
        policies.create(
            w.manager,
            w.drone.id,
            CreatePolicy(TeamRole.MEMBER, typeId = w.fastener, effect = DENY, permissions = setOf(MODIFY, READ)),
        )
        policies.create(
            w.manager,
            w.drone.id,
            CreatePolicy(TeamRole.VIEWER, typeId = TypeId.PART, state = "RELEASED", permissions = setOf(REVISE)),
        )
        val rows =
            listOf(w.org.id, w.drone.id).flatMap { ctx ->
                listOf(TypeId.PART, TypeId.DOCUMENT, w.fastener).flatMap { type ->
                    listOf("IN_WORK", "RELEASED").map { state -> Triple(ctx, type, state) }
                }
            }
        val values =
            rows.indices.joinToString {
                "(cast(:c$it as uuid), cast(:t$it as uuid), cast(:s$it as text), $it)"
            }

        for (actor in listOf(w.manager, w.member, w.viewer)) {
            for (permission in Permission.entries) {
                val predicate = access.filter(actor, permission, AccessColumns("r.ctx", "r.type_id", "r.state"))
                var query =
                    jdbc
                        .sql("select r.idx from (values $values) as r(ctx, type_id, state, idx) where ${predicate.sql}")
                        .params(predicate.params)
                rows.forEachIndexed { i, (c, t, s) ->
                    query =
                        query.param("c$i", c.value).param("t$i", t.value).param("s$i", s)
                }
                val viaSql = query.query(Int::class.java).list().toSet()
                val viaCheck =
                    rows.indices
                        .filter {
                            permission in
                                perms(actor, rows[it].first, rows[it].second, rows[it].third)
                        }.toSet()

                assertThat(viaSql).describedAs("${actor.principal} / $permission").isEqualTo(viaCheck)
            }
        }
    }

    @Test
    fun `custom roles get exactly what their policies grant`() {
        val w = World()
        val engineer = TeamRole(unique("ENGINEER_"))
        roles.create(admin, engineer, TeamRoleSettings(mapOf("en" to "Design engineer")))
        val dana = Actors.user("dana").also { teams.put(w.manager, w.drone.id, it.principal, engineer) }

        assertThat(perms(dana, w.drone.id)).isEmpty()
        policies.create(w.manager, w.drone.id, CreatePolicy(engineer, permissions = setOf(READ, CREATE, MODIFY)))
        assertThat(perms(dana, w.drone.id)).containsExactlyInAnyOrder(READ, CREATE, MODIFY)
        assertThat(roles.list(dana).map { it.role }).contains(TeamRole.VIEWER, engineer)
    }

    @Test
    fun `roles are managed by site admins, and only active ones can be given`() {
        val w = World()
        val role = TeamRole(unique("REVIEWER_"))
        assertThatThrownBy { roles.create(w.manager, role, TeamRoleSettings(mapOf("en" to "Reviewer"))) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ADMIN_REQUIRED") }
        assertThatThrownBy { roles.create(admin, TeamRole("lower"), TeamRoleSettings(mapOf("en" to "x"))) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("INVALID_ROLE") }

        roles.create(admin, role, TeamRoleSettings(mapOf("en" to "Reviewer")))
        assertThatThrownBy { roles.create(admin, role, TeamRoleSettings(mapOf("en" to "Again"))) }
            .isInstanceOfSatisfying(ConflictException::class.java) { assertThat(it.code).isEqualTo("DUPLICATE_ROLE") }
        roles.update(admin, role, TeamRoleSettings(mapOf("en" to "Reviewer"), active = false))

        assertThatThrownBy { teams.put(w.manager, w.drone.id, Actors.user("x").principal, role) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("UNKNOWN_ROLE") }
        assertThatThrownBy { teams.put(w.manager, w.drone.id, Actors.user("x").principal, TeamRole("NO_SUCH_ROLE")) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("UNKNOWN_ROLE") }
        assertThatThrownBy {
            roles.update(
                admin,
                TeamRole.VIEWER,
                TeamRoleSettings(mapOf("en" to "Viewer"), active = false),
            )
        }.isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("INVALID_ROLE") }
    }

    @Test
    fun `managers change policies of their contexts, only site admins those of the site`() {
        val w = World()
        val rule = CreatePolicy(TeamRole.VIEWER, state = "RELEASED", permissions = setOf(PROMOTE))

        assertThatThrownBy { policies.create(w.member, w.drone.id, rule) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ACCESS_DENIED") }
        assertThatThrownBy { policies.create(w.manager, ContextId.DEFAULT_SITE, rule) }
            .isInstanceOfSatisfying(ForbiddenException::class.java) { assertThat(it.code).isEqualTo("ADMIN_REQUIRED") }

        val created = policies.create(w.manager, w.drone.id, rule)
        assertThatThrownBy { policies.create(w.manager, w.drone.id, rule) }
            .isInstanceOfSatisfying(ConflictException::class.java) { assertThat(it.code).isEqualTo("DUPLICATE_POLICY") }

        val inEffect = policies.policies(w.manager, w.drone.id)
        assertThat(inEffect.first().contextId).isEqualTo(ContextId.DEFAULT_SITE) // root first
        assertThat(inEffect.last()).isEqualTo(created)
        assertThatThrownBy { policies.policies(w.member, w.drone.id) }.isInstanceOf(ForbiddenException::class.java)

        val updated = policies.update(w.manager, created.id, setOf(PROMOTE, REVISE))
        assertThat(updated.permissions).containsExactlyInAnyOrder(PROMOTE, REVISE)
        assertThat(perms(w.viewer, w.drone.id, state = "RELEASED")).containsExactlyInAnyOrder(READ, PROMOTE, REVISE)

        policies.delete(w.manager, created.id)
        assertThat(perms(w.viewer, w.drone.id, state = "RELEASED")).containsExactly(READ)
        assertThatThrownBy { policies.delete(w.manager, created.id) }
            .isInstanceOfSatisfying(NotFoundException::class.java) { assertThat(it.code).isEqualTo("POLICY_NOT_FOUND") }
    }

    @Test
    fun `invalid policies are refused`() {
        val w = World()

        assertThatThrownBy {
            policies.create(
                w.manager,
                w.drone.id,
                CreatePolicy(TeamRole.VIEWER, state = "released", permissions = emptySet()),
            )
        }.isInstanceOfSatisfying(ValidationException::class.java) {
            assertThat(it.code).isEqualTo("INVALID_POLICY")
            assertThat(it.details["errors"] as List<*>).hasSize(2)
        }
        assertThatThrownBy {
            policies.create(w.manager, w.drone.id, CreatePolicy(TeamRole("GHOST"), permissions = setOf(READ)))
        }.isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("UNKNOWN_ROLE") }
        assertThatThrownBy {
            policies.create(
                w.manager,
                w.drone.id,
                CreatePolicy(TeamRole.VIEWER, typeId = TypeId(UUID.randomUUID()), permissions = setOf(READ)),
            )
        }.isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo("INVALID_POLICY") }
    }

    @Test
    fun `explain says which roles and policies grant or deny each permission`() {
        val w = World()
        val deny =
            policies.create(
                w.manager,
                w.drone.id,
                CreatePolicy(TeamRole.MEMBER, typeId = w.fastener, effect = DENY, permissions = setOf(MODIFY)),
            )

        val why = policies.explain(w.manager, w.member.principal, AccessTarget(w.drone.id, w.fastener, "IN_WORK"))

        assertThat(why.roles.map { it.contextId to it.role }).containsExactly(w.drone.id to TeamRole.MEMBER)
        val byPermission = why.permissions.associateBy { it.permission }
        assertThat(byPermission.getValue(READ).granted).isTrue()
        assertThat(
            byPermission
                .getValue(READ)
                .allowedBy
                .single()
                .contextId,
        ).isEqualTo(ContextId.DEFAULT_SITE)
        assertThat(byPermission.getValue(MODIFY).granted).isFalse()
        assertThat(byPermission.getValue(MODIFY).deniedBy).containsExactly(deny)
        assertThat(byPermission.getValue(PROMOTE).let { it.granted || it.allowedBy.isNotEmpty() }).isFalse()
        assertThat(
            why.permissions
                .filter { it.granted }
                .map { it.permission }
                .toSet(),
        ).isEqualTo(perms(w.member, w.drone.id, w.fastener))

        // About yourself, always; about others, only with MANAGE.
        assertThat(policies.explain(w.member, w.member.principal, AccessTarget(w.drone.id)).roles).hasSize(1)
        assertThatThrownBy { policies.explain(w.member, w.viewer.principal, AccessTarget(w.drone.id)) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThat(
            policies.explain(admin, admin.principal, AccessTarget(w.drone.id)).permissions.all { it.granted },
        ).isTrue()
    }

    @Test
    fun `effective members include the parents' teams, nearest first`() {
        val w = World()

        val members = teams.effectiveMembers(w.viewer, w.drone.id)

        // The site admin created both contexts, so is MANAGER of each.
        assertThat(members.map { it.principalId to it.contextId }).containsExactlyInAnyOrder(
            w.member.principal to w.drone.id,
            w.viewer.principal to w.drone.id,
            admin.principal to w.drone.id,
            w.manager.principal to w.org.id,
            admin.principal to w.org.id,
        )
        assertThat(members.takeLast(2).map { it.contextId }).containsOnly(w.org.id)
    }

    @Test
    fun `policies of contexts you can't see don't exist for you`() {
        val w = World()
        val other = World()
        val policy = policies.create(w.manager, w.drone.id, CreatePolicy(TeamRole.VIEWER, permissions = setOf(MODIFY)))

        assertThatThrownBy { policies.delete(other.manager, policy.id) }
            .isInstanceOfSatisfying(NotFoundException::class.java) { assertThat(it.code).isEqualTo("POLICY_NOT_FOUND") }
        assertThat(policies.policies(w.manager, w.drone.id)).contains(policy)
    }
}
