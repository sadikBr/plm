package com.plm.directory.infra.web

import com.plm.directory.api.DirectoryGroup
import com.plm.directory.api.DirectoryGroupInput
import com.plm.directory.api.DirectoryPage
import com.plm.directory.api.DirectoryService
import com.plm.directory.api.DirectoryUser
import com.plm.directory.api.DirectoryUserInput
import com.plm.directory.api.PatchOperation
import com.plm.directory.domain.primaryEmail
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val USER = "urn:ietf:params:scim:schemas:core:2.0:User"
private const val GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group"
private const val LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse"
private const val PATCH = "urn:ietf:params:scim:api:messages:2.0:PatchOp"
private const val ERROR = "urn:ietf:params:scim:api:messages:2.0:Error"
private const val BASE = "/api/v1/scim/v2"
private const val SCIM_JSON = "application/scim+json"

/**
 * SCIM 2.0 (RFC 7643/7644) for identity providers (roadmap 9.1): `Users` and `Groups` with the
 * filters, paging and PATCH operations providers use. Authenticate with an API token of a service
 * account that may administer (scopes read, write and admin).
 */
@RestController
@RequestMapping(BASE, produces = [SCIM_JSON, MediaType.APPLICATION_JSON_VALUE])
internal class ScimController(
    private val directory: DirectoryService,
) {
    private fun user(u: DirectoryUser): Map<String, Any?> =
        mapOf(
            "schemas" to listOf(USER),
            "id" to u.id.toString(),
            "externalId" to u.externalId,
            "userName" to u.userName,
            "displayName" to
                (u.displayName ?: listOfNotNull(u.givenName, u.familyName).joinToString(" ").ifBlank { null }),
            "name" to mapOf("givenName" to u.givenName, "familyName" to u.familyName),
            "emails" to listOfNotNull(u.email?.let { mapOf("value" to it, "type" to "work", "primary" to true) }),
            "active" to u.active,
            "meta" to
                mapOf(
                    "resourceType" to "User",
                    "created" to u.createdAt.toString(),
                    "lastModified" to u.updatedAt.toString(),
                    "version" to "W/\"${u.version}\"",
                    "location" to "$BASE/Users/${u.id}",
                ),
        ).filterValues { it != null }

    private fun group(
        g: DirectoryGroup,
        withMembers: Boolean = true,
    ): Map<String, Any?> =
        mapOf(
            "schemas" to listOf(GROUP),
            "id" to g.id.toString(),
            "externalId" to g.externalId,
            "displayName" to g.displayName,
            "members" to
                if (withMembers) {
                    g.members.map {
                        mapOf(
                            "value" to it.userId.toString(),
                            "display" to it.userName,
                            "\$ref" to "$BASE/Users/${it.userId}",
                        )
                    }
                } else {
                    null
                },
            "meta" to
                mapOf(
                    "resourceType" to "Group",
                    "created" to g.createdAt.toString(),
                    "lastModified" to g.updatedAt.toString(),
                    "version" to "W/\"${g.version}\"",
                    "location" to "$BASE/Groups/${g.id}",
                ),
        ).filterValues { it != null }

    private fun <T> list(
        page: DirectoryPage<T>,
        map: (T) -> Map<String, Any?>,
    ) = mapOf(
        "schemas" to listOf(LIST),
        "totalResults" to page.total,
        "startIndex" to page.startIndex,
        "itemsPerPage" to page.items.size,
        "Resources" to page.items.map(map),
    )

    private fun userInput(body: Map<String, Any?>): DirectoryUserInput {
        val name = body["name"] as? Map<*, *>
        return DirectoryUserInput(
            externalId = body["externalId"]?.toString(),
            userName =
                body["userName"]?.toString()
                    ?: throw ValidationException("SCIM_INVALID_VALUE", "userName is required"),
            displayName = body["displayName"]?.toString(),
            givenName = name?.get("givenName")?.toString(),
            familyName = name?.get("familyName")?.toString(),
            email = (body["emails"] as? List<*>)?.let(::primaryEmail),
            active =
                when (val a = body["active"]) {
                    null -> true
                    is Boolean -> a
                    else -> a.toString().equals("true", ignoreCase = true)
                },
        )
    }

    private fun groupInput(body: Map<String, Any?>) =
        DirectoryGroupInput(
            externalId = body["externalId"]?.toString(),
            displayName =
                body["displayName"]?.toString()
                    ?: throw ValidationException("SCIM_INVALID_VALUE", "displayName is required"),
            members =
                (body["members"] as? List<*>)
                    ?.mapNotNull { (it as? Map<*, *>)?.get("value")?.toString() }
                    ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: emptyList(),
        )

    private fun operations(body: Map<String, Any?>): List<PatchOperation> {
        val schemas = body["schemas"] as? List<*>
        if (schemas != null && PATCH !in schemas) throw ValidationException("SCIM_INVALID_VALUE", "Expected a PatchOp")
        return (
            body["Operations"] as? List<*>
                ?: throw ValidationException("SCIM_INVALID_VALUE", "Operations are required")
        ).map {
            val op =
                it as? Map<*, *>
                    ?: throw ValidationException("SCIM_INVALID_VALUE", "An operation must be an object")
            PatchOperation(op["op"]?.toString() ?: "", op["path"]?.toString(), op["value"])
        }
    }

    @GetMapping("/ServiceProviderConfig")
    fun config() =
        mapOf(
            "schemas" to listOf("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"),
            "patch" to mapOf("supported" to true),
            "bulk" to mapOf("supported" to false, "maxOperations" to 0, "maxPayloadSize" to 0),
            "filter" to mapOf("supported" to true, "maxResults" to 1000),
            "changePassword" to mapOf("supported" to false),
            "sort" to mapOf("supported" to false),
            "etag" to mapOf("supported" to false),
            "authenticationSchemes" to
                listOf(
                    mapOf(
                        "type" to "oauthbearertoken",
                        "name" to "API token",
                        "description" to "A service account's API token",
                    ),
                ),
        )

    @GetMapping("/ResourceTypes")
    fun resourceTypes() =
        mapOf(
            "schemas" to listOf(LIST),
            "totalResults" to 2,
            "Resources" to
                listOf(
                    mapOf("id" to "User", "name" to "User", "endpoint" to "/Users", "schema" to USER),
                    mapOf("id" to "Group", "name" to "Group", "endpoint" to "/Groups", "schema" to GROUP),
                ),
        )

    @GetMapping("/Users")
    fun users(
        actor: Actor,
        @RequestParam(required = false) filter: String?,
        @RequestParam(defaultValue = "1") startIndex: Int,
        @RequestParam(defaultValue = "100") count: Int,
    ) = list(directory.users(actor, filter, startIndex, count), ::user)

    @GetMapping("/Users/{id}")
    fun getUser(
        actor: Actor,
        @PathVariable id: UUID,
    ) = user(directory.user(actor, id))

    @PostMapping("/Users", consumes = [SCIM_JSON, MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun createUser(
        actor: Actor,
        @RequestBody body: Map<String, Any?>,
    ) = user(directory.createUser(actor, userInput(body)))

    @PutMapping("/Users/{id}", consumes = [SCIM_JSON, MediaType.APPLICATION_JSON_VALUE])
    fun replaceUser(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody body: Map<String, Any?>,
    ) = user(directory.replaceUser(actor, id, userInput(body)))

    @PatchMapping("/Users/{id}", consumes = [SCIM_JSON, MediaType.APPLICATION_JSON_VALUE])
    fun patchUser(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody body: Map<String, Any?>,
    ) = user(directory.patchUser(actor, id, operations(body)))

    @DeleteMapping("/Users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(
        actor: Actor,
        @PathVariable id: UUID,
    ) = directory.deleteUser(actor, id)

    @GetMapping("/Groups")
    fun groups(
        actor: Actor,
        @RequestParam(required = false) filter: String?,
        @RequestParam(defaultValue = "1") startIndex: Int,
        @RequestParam(defaultValue = "100") count: Int,
        @RequestParam(required = false) excludedAttributes: String?,
    ): Map<String, Any?> {
        val members = excludedAttributes?.split(",")?.none { it.trim().equals("members", ignoreCase = true) } ?: true
        return list(directory.groups(actor, filter, startIndex, count)) { group(it, members) }
    }

    @GetMapping("/Groups/{id}")
    fun getGroup(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestParam(required = false) excludedAttributes: String?,
    ) = group(
        directory.group(actor, id),
        excludedAttributes?.split(",")?.none { it.trim().equals("members", ignoreCase = true) } ?: true,
    )

    @PostMapping("/Groups", consumes = [SCIM_JSON, MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun createGroup(
        actor: Actor,
        @RequestBody body: Map<String, Any?>,
    ) = group(directory.createGroup(actor, groupInput(body)))

    @PutMapping("/Groups/{id}", consumes = [SCIM_JSON, MediaType.APPLICATION_JSON_VALUE])
    fun replaceGroup(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody body: Map<String, Any?>,
    ) = group(directory.replaceGroup(actor, id, groupInput(body)))

    @PatchMapping("/Groups/{id}", consumes = [SCIM_JSON, MediaType.APPLICATION_JSON_VALUE])
    fun patchGroup(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody body: Map<String, Any?>,
    ) = group(directory.patchGroup(actor, id, operations(body)))

    @DeleteMapping("/Groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteGroup(
        actor: Actor,
        @PathVariable id: UUID,
    ) = directory.deleteGroup(actor, id)

    /** SCIM's own error format, which identity providers read. */
    @ExceptionHandler(PlmException::class)
    fun error(e: PlmException): ResponseEntity<Map<String, Any?>> {
        val status =
            when (e) {
                is NotFoundException -> HttpStatus.NOT_FOUND
                is ConflictException -> HttpStatus.CONFLICT
                is ForbiddenException -> HttpStatus.FORBIDDEN
                is ValidationException -> HttpStatus.BAD_REQUEST
            }
        val scimType =
            when (e.code) {
                "SCIM_UNIQUENESS" -> "uniqueness"
                "SCIM_INVALID_FILTER" -> "invalidFilter"
                "SCIM_INVALID_PATH" -> "invalidPath"
                "SCIM_INVALID_VALUE" -> "invalidValue"
                else -> null
            }
        return ResponseEntity
            .status(status)
            .contentType(MediaType.parseMediaType(SCIM_JSON))
            .body(
                mapOf(
                    "schemas" to listOf(ERROR),
                    "status" to status.value().toString(),
                    "scimType" to scimType,
                    "detail" to e.message,
                ).filterValues { it != null },
            )
    }
}
