package com.plm.cad

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.cad.api.AddDependency
import com.plm.cad.api.CadService
import com.plm.cad.api.CollectOptions
import com.plm.cad.api.CollectReason
import com.plm.cad.api.CollectorOperation
import com.plm.cad.api.CollectorRequest
import com.plm.cad.api.CollectorService
import com.plm.cad.api.DependencyKind
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** The collector (roadmap 6.2). */
@PlmIntegrationTest
internal class CollectorIT(
    @Autowired private val collector: CollectorService,
    @Autowired private val cad: CadService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))

    @Test
    fun `a model is collected with what it needs and its drawings, and acted on all or nothing`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Collect ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        teams.put(admin, p, bob.principal, TeamRole.MEMBER)

        fun make(
            type: TypeId,
            name: String,
        ) = versioning.create(alice, CreateVersionedObject(type, p, name)).id
        val bolt = make(TypeId.CAD_PART, "Bolt")
        val skeleton = make(TypeId.CAD_PART, "Skeleton")
        val bracket = make(TypeId.CAD_ASSEMBLY, "Bracket")
        val top = make(TypeId.CAD_ASSEMBLY, "Gripper")
        val drawing = make(TypeId.CAD_DRAWING, "Bracket drawing")

        fun build(
            parent: MasterId,
            vararg deps: AddDependency,
        ) {
            versioning.checkOut(alice, parent)
            deps.forEach { cad.add(alice, parent.value, it) }
            versioning.checkIn(alice, parent)
        }
        build(
            bracket,
            AddDependency(bolt.value),
            AddDependency(skeleton.value, DependencyKind.REFERENCE, required = false),
        )
        build(top, AddDependency(bracket.value))
        build(drawing, AddDependency(bracket.value, DependencyKind.DRAWING))

        val required = collector.collect(alice, bracket.value)
        assertThat(required.associate { it.model.name to it.reason })
            .isEqualTo(
                mapOf(
                    "Bracket" to CollectReason.SELF,
                    "Bolt" to CollectReason.REQUIRED,
                    "Bracket drawing" to CollectReason.DRAWING,
                ),
            )
        val all = collector.collect(alice, bracket.value, CollectOptions("ALL", drawings = false, parents = true))
        assertThat(all.associate { it.model.name to it.reason })
            .isEqualTo(
                mapOf(
                    "Bracket" to CollectReason.SELF,
                    "Bolt" to CollectReason.REQUIRED,
                    "Skeleton" to CollectReason.OPTIONAL,
                    "Gripper" to CollectReason.PARENT,
                ),
            )

        // All or nothing: bob holds the bolt, so alice's check-out changes nothing.
        versioning.checkOut(bob, bolt)
        val ids = required.map { it.model.id }
        assertThatThrownBy { collector.apply(alice, CollectorRequest(CollectorOperation.CHECK_OUT, ids)) }
            .hasMessageContaining(versioning.get(alice, bolt).number)
        assertThat(ids.map { versioning.get(alice, MasterId(it)).checkedOutBy }).containsOnly(null, "bob")
        versioning.undoCheckOut(bob, bolt)

        val out = collector.apply(alice, CollectorRequest(CollectorOperation.CHECK_OUT, ids))
        assertThat(out.map { it.changed }).containsOnly(true)
        val back = collector.apply(alice, CollectorRequest(CollectorOperation.CHECK_IN, ids, "Thread length"))
        assertThat(back.map { it.version }).containsOnly("A.3", "A.2")
        assertThat(ids.map { versioning.get(alice, MasterId(it)).checkedOutBy }).containsOnly(null)
        assertThat(versioning.get(alice, bracket).latest.comment).isEqualTo("Thread length")
    }
}
