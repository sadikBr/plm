package com.plm.versioning

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.platform.actor.Actor
import com.plm.platform.error.PlmException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.DeleteRules
import com.plm.versioning.api.Deleter
import com.plm.versioning.api.MoveObject
import com.plm.versioning.api.ObjectQuery
import com.plm.versioning.api.OrganizingService
import com.plm.versioning.api.SetState
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Folders, move, delete rules, owners and reservations (roadmap 3.4). */
@PlmIntegrationTest
class OrganizingIT(
    @Autowired private val organizing: OrganizingService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val directory: PrincipalDirectory,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val bob = Actor(TenantId.DEFAULT, PrincipalId("bob"))
    private val mona = Actor(TenantId.DEFAULT, PrincipalId("mona-manager"))

    private fun code(call: () -> Unit) = (runCatching(call).exceptionOrNull() as PlmException).code

    private fun product(): ContextId {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Org ${UUID.randomUUID()}"),
            )
        return contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id.also {
            teams.put(admin, it, alice.principal, TeamRole.MEMBER)
            teams.put(admin, it, bob.principal, TeamRole.MEMBER)
            teams.put(admin, it, mona.principal, TeamRole.MANAGER)
        }
    }

    private fun part(
        context: ContextId,
        folder: UUID? = null,
    ) = versioning.create(
        alice,
        CreateVersionedObject(
            TypeId.PART,
            context,
            "Wheel",
            attributes =
                mapOf(
                    "material" to "Al",
                ),
            folderId = folder,
        ),
    )

    @BeforeEach
    fun people() {
        listOf("alice", "bob").forEach {
            directory.recordSignIn(
                SignIn(TenantId.DEFAULT, PrincipalId(it), it, it.replaceFirstChar(Char::uppercase), null),
            )
        }
    }

    @AfterEach
    fun defaults() {
        organizing.setDeleteRules(admin, DeleteRules())
    }

    @Test
    fun `folders hold objects, and objects move between folders and contexts`() {
        val p = product()
        val drawings = organizing.createFolder(alice, p.value, null, "Drawings")
        val sheets = organizing.createFolder(alice, p.value, drawings.id, "Sheets")
        assertThat(code { organizing.createFolder(alice, p.value, null, "drawings") }).isEqualTo("DUPLICATE_FOLDER")
        assertThat(organizing.folders(alice, p.value).map { it.name }).containsExactly("Drawings")
        assertThat(organizing.folderPath(alice, sheets.id).map { it.name }).containsExactly("Drawings", "Sheets")

        val inSheets = part(p, sheets.id)
        val loose = part(p)
        assertThat(inSheets.folderId).isEqualTo(sheets.id)
        assertThat(
            versioning.list(alice, ObjectQuery(p, folderId = sheets.id)).map { it.id },
        ).containsExactly(inSheets.id)
        assertThat(versioning.list(alice, ObjectQuery(p, topLevelOnly = true)).map { it.id }).containsExactly(loose.id)

        val moved = organizing.move(alice, loose.id, MoveObject(p.value, drawings.id))
        assertThat(moved.folderId).isEqualTo(drawings.id)
        assertThat(organizing.folders(alice, p.value).single().objects).isEqualTo(1)
        assertThat(code { organizing.deleteFolder(mona, drawings.id) }).isEqualTo("FOLDER_NOT_EMPTY")
        assertThat(code { organizing.renameFolder(alice, drawings.id, "Plans") }).isEqualTo("ACCESS_DENIED")
        assertThat(organizing.renameFolder(mona, drawings.id, "Plans").name).isEqualTo("Plans")

        // Elsewhere: needs CREATE there; alice isn't on the other product's team.
        val other = product()
        teams.remove(admin, other, alice.principal)
        assertThat(
            code {
                organizing.move(alice, moved.id, MoveObject(other.value))
            },
        ).isIn("CONTEXT_NOT_FOUND", "ACCESS_DENIED")
        val there = organizing.move(admin, moved.id, MoveObject(other.value))
        assertThat(there.contextId).isEqualTo(other)
        assertThat(there.folderId).isNull()
        assertThat(
            code {
                organizing.move(alice, inSheets.id, MoveObject(p.value, UUID.randomUUID()))
            },
        ).isEqualTo("FOLDER_NOT_FOUND")
    }

    @Test
    fun `delete rules decide what may be deleted and by whom`() {
        val p = product()
        val id = part(p).id
        assertThat(code { organizing.delete(bob, id) }).isEqualTo("DELETE_NOT_ALLOWED")
        assertThat(organizing.whyNotDelete(bob, id)).contains("owner")

        versioning.checkOut(alice, id)
        assertThat(organizing.whyNotDelete(alice, id)).contains("checked out")
        versioning.undoCheckOut(alice, id)

        versioning.setState(admin, id, SetState("RELEASED", override = true))
        assertThat(organizing.whyNotDelete(alice, id)).contains("RELEASED")
        organizing.setDeleteRules(admin, DeleteRules(states = emptyList(), who = setOf(Deleter.MANAGERS)))
        assertThat(organizing.whyNotDelete(alice, id)).isNotNull()
        // Managers may now, in any state; the object and its history go.
        versioning.revise(admin, id)
        organizing.delete(mona, id)
        assertThat(code { versioning.get(alice, id) }).isEqualTo("OBJECT_NOT_FOUND")
        assertThat(code { organizing.setDeleteRules(alice, DeleteRules()) }).isEqualTo("ADMIN_REQUIRED")
    }

    @Test
    fun `owners hand objects over, and a reservation keeps others from checking out`() {
        val p = product()
        val id = part(p).id
        assertThat(versioning.get(alice, id).owner).isEqualTo("alice")
        assertThat(code { organizing.changeOwner(bob, id, "bob") }).isEqualTo("ACCESS_DENIED")
        assertThat(organizing.changeOwner(alice, id, "bob").owner).isEqualTo("bob")
        // The new owner may delete it (In Work, owner): alice no longer may.
        assertThat(organizing.whyNotDelete(alice, id)).isNotNull()
        assertThat(organizing.whyNotDelete(bob, id)).isNull()

        assertThat(organizing.reserve(alice, id).reservedBy).isEqualTo("alice")
        assertThat(code { versioning.checkOut(bob, id) }).isEqualTo("RESERVED_BY_ANOTHER_USER")
        assertThat(code { organizing.reserve(bob, id) }).isEqualTo("RESERVED_BY_ANOTHER_USER")
        assertThat(code { organizing.release(bob, id) }).isEqualTo("ACCESS_DENIED")
        versioning.checkOut(alice, id)
        versioning.undoCheckOut(alice, id)
        assertThat(organizing.release(alice, id).reservedBy).isNull()
        versioning.checkOut(bob, id)
        assertThat((versioning.expressionModel(bob, id)["object"] as Map<*, *>)["owner"]).isEqualTo("bob")
    }
}
