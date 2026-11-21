package com.plm.variants

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.variants.api.OptionService
import com.plm.variants.api.SaveChoice
import com.plm.variants.api.SaveOption
import com.plm.variants.api.SaveOptionSet
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Option sets, options and choices, assigned to configurable parts (roadmap 7.5). */
@PlmIntegrationTest
internal class OptionsIT(
    @Autowired private val options: OptionService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
) {
    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))
    private val vera = Actor(TenantId.DEFAULT, PrincipalId("op-vera-${UUID.randomUUID()}"))

    @Test
    fun `a library keeps option sets that make parts configurable`() {
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Opt ${UUID.randomUUID()}"),
            )
        val lib = contexts.create(admin, CreateContext(org.id, ContextKind.LIBRARY, "Options")).id
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        for (c in listOf(lib, p)) teams.put(admin, c, alice.principal, TeamRole.MEMBER)

        val set = options.create(alice, lib.value, SaveOptionSet("Rover options", "Drive and finish"))
        val motor = options.addOption(alice, set.id, SaveOption("motor", "Motor")).options.single()
        assertThat(motor.code).isEqualTo("MOTOR")
        options.addChoice(alice, motor.id, SaveChoice("STD", "Standard"))
        options.addChoice(alice, motor.id, SaveChoice("HT", "High torque"))
        val color = options.addOption(alice, set.id, SaveOption("COLOR", "Colour", required = false)).options.last()
        options.addChoice(alice, color.id, SaveChoice("RED", "Red"))
        val withBlue = options.addChoice(alice, color.id, SaveChoice("BLUE", "Blue"))
        assertThat(withBlue.options.map { o -> o.code + ":" + o.choices.joinToString(",") { it.code } })
            .containsExactly("MOTOR:STD,HT", "COLOR:RED,BLUE")

        assertThatThrownBy {
            options.addOption(alice, set.id, SaveOption("Motor", "Again"))
        }.hasMessageContaining("already used")
        assertThatThrownBy {
            options.addChoice(
                alice,
                motor.id,
                SaveChoice("HT", "Twice"),
            )
        }.hasMessageContaining("MOTOR=HT")
        assertThatThrownBy {
            options.addOption(
                alice,
                set.id,
                SaveOption("1ST", "Bad"),
            )
        }.hasMessageContaining("isn't a code")

        // Retired, not deleted.
        val red =
            withBlue.options
                .last()
                .choices
                .first()
        val retired = options.updateChoice(alice, red.id, SaveChoice("RED", "Red", retired = true))
        assertThat(
            retired.options
                .last()
                .choices
                .first()
                .retired,
        ).isTrue()

        // A part becomes configurable with it; the set can't go while it is.
        val rover = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Rover")).id
        assertThat(options.partOptions(alice, rover.value).optionSet).isNull()
        assertThat(options.assign(alice, rover.value, set.id).optionSet!!.name).isEqualTo("Rover options")
        assertThat(options.get(alice, set.id).usedBy).isEqualTo(1)
        assertThatThrownBy { options.delete(alice, set.id) }.hasMessageContaining("use it")
        options.assign(alice, rover.value, null)
        assertThat(options.sets(alice, lib.value).map { it.name }).containsExactly("Rover options")

        // Off the library's team: nothing to see.
        assertThat(options.sets(vera)).noneMatch { it.id == set.id }
        assertThatThrownBy { options.get(vera, set.id) }.hasMessageContaining("no such option set")
        options.delete(alice, set.id)
    }
}
