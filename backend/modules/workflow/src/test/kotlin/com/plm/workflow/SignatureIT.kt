package com.plm.workflow

import com.plm.access.api.ContextId
import com.plm.access.api.ContextKind
import com.plm.access.api.ContextService
import com.plm.access.api.CreateContext
import com.plm.access.api.TeamRole
import com.plm.access.api.TeamService
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.SignIn
import com.plm.platform.actor.Actor
import com.plm.platform.error.ValidationException
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.types.api.TypeId
import com.plm.versioning.api.CreateVersionedObject
import com.plm.versioning.api.VersioningService
import com.plm.workflow.api.CompleteWorkItem
import com.plm.workflow.api.NodeKind
import com.plm.workflow.api.ProcessDefinition
import com.plm.workflow.api.ProcessNode
import com.plm.workflow.api.ProcessService
import com.plm.workflow.api.ProcessTemplateService
import com.plm.workflow.api.SaveProcessTemplate
import com.plm.workflow.api.SignatureService
import com.plm.workflow.api.StartProcess
import com.plm.workflow.api.WorkItemService
import com.plm.workflow.domain.PasswordVerifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import plm.testing.PlmIntegrationTest
import java.util.UUID

/** Electronic signatures (roadmap 5.6): the password again, then a record nobody can change. */
@PlmIntegrationTest
@Import(SignatureIT.Passwords::class)
internal class SignatureIT(
    @Autowired private val processes: ProcessService,
    @Autowired private val templates: ProcessTemplateService,
    @Autowired private val items: WorkItemService,
    @Autowired private val signatures: SignatureService,
    @Autowired private val versioning: VersioningService,
    @Autowired private val contexts: ContextService,
    @Autowired private val teams: TeamService,
    @Autowired private val directory: PrincipalDirectory,
    @Autowired private val jdbc: JdbcClient,
) {
    @TestConfiguration
    class Passwords {
        /** Stands in for the identity provider: alice's password is "correct horse". */
        @Bean
        @Primary
        fun testPasswords() = PasswordVerifier { user, password -> user == "alice" && password == "correct horse" }
    }

    private val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)
    private val alice = Actor(TenantId.DEFAULT, PrincipalId("alice"))

    @Test
    fun `a signing task needs the password again and leaves a record that can't be changed`() {
        directory.recordSignIn(SignIn(TenantId.DEFAULT, alice.principal, "alice", "Alice Admin", null))
        val org =
            contexts.create(
                admin,
                CreateContext(ContextId.DEFAULT_SITE, ContextKind.ORGANIZATION, "Sign ${UUID.randomUUID()}"),
            )
        val p = contexts.create(admin, CreateContext(org.id, ContextKind.PRODUCT, "Rover")).id
        teams.put(admin, p, alice.principal, TeamRole.MEMBER)
        val template =
            templates.create(
                admin,
                SaveProcessTemplate(
                    "Signed approval ${UUID.randomUUID().toString().take(6)}",
                    definition =
                        ProcessDefinition(
                            "approve",
                            listOf(
                                ProcessNode(
                                    "approve",
                                    NodeKind.TASK,
                                    "Quality approval",
                                    assignees = listOf("starter"),
                                    choices = listOf("APPROVE", "REJECT"),
                                    signature = true,
                                    next = "end",
                                ),
                                ProcessNode("end", NodeKind.END),
                            ),
                        ),
                ),
            )
        val part = versioning.create(alice, CreateVersionedObject(TypeId.PART, p, "Bracket"))
        val run = processes.start(alice, StartProcess(template.id, part.id.value))
        var task = items.list(alice).firstOrNull { it.instanceId == run.id }
        repeat(50) {
            if (task == null) {
                Thread.sleep(100)
                task = items.list(alice).firstOrNull { it.instanceId == run.id }
            }
        }
        val id = task!!.id
        assertThat(task.signature).isTrue()
        assertThatThrownBy { items.complete(alice, id, CompleteWorkItem("APPROVE")) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("not signed")
        assertThatThrownBy { items.complete(alice, id, CompleteWorkItem("APPROVE", password = "wrong")) }
            .hasMessageContaining("not signed")
        items.complete(alice, id, CompleteWorkItem("APPROVE", "Dimensions verified", "correct horse"))

        val signed = signatures.forObject(alice, part.id.value).single()
        assertThat(signed.displayName).isEqualTo("Alice Admin")
        assertThat(signed.meaning).isEqualTo("Approve — Quality approval")
        assertThat(signed.objectVersion).isEqualTo("A.1")
        assertThat(signed.intact).isTrue()
        assertThatThrownBy {
            jdbc.sql("update e_signature set meaning = 'Reject' where id = :id").param("id", signed.id).update()
        }.hasMessageContaining("cannot be changed")
        // Refused attempts are in the audit trail.
        val refusals =
            jdbc
                .sql("select count(*) from outbox_event where event_type = 'workflow.SignatureRefused.v1'")
                .query(Int::class.java)
                .single()
        assertThat(refusals).isGreaterThanOrEqualTo(2)
    }
}
