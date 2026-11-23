package com.plm.directory.infra.web

import com.plm.directory.api.DirectoryService
import com.plm.platform.actor.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class GroupRole(
    val role: String,
)

/** The directory for Administration (roadmap 9.1): what the identity provider pushed, and each group's role. */
@RestController
internal class DirectoryController(
    private val directory: DirectoryService,
) {
    @GetMapping("/api/v1/directory")
    fun status(actor: Actor) = directory.status(actor)

    @GetMapping("/api/v1/directory/users")
    fun users(actor: Actor) = directory.users(actor, null, 1, 1000).items

    @GetMapping("/api/v1/directory/groups")
    fun groups(actor: Actor) = directory.groups(actor, null, 1, 1000).items

    @PutMapping("/api/v1/directory/groups/{id}/role")
    fun role(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody body: GroupRole,
    ) = directory.setGroupRole(actor, id, body.role)
}
