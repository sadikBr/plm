package com.plm.directory.domain

import com.plm.access.api.ContextService
import com.plm.access.api.SharedTeamService
import com.plm.access.api.TeamRole
import com.plm.directory.api.DirectoryGroup
import com.plm.directory.api.DirectoryGroupInput
import com.plm.directory.api.DirectoryPage
import com.plm.directory.api.DirectoryService
import com.plm.directory.api.DirectoryStatus
import com.plm.directory.api.DirectoryUser
import com.plm.directory.api.DirectoryUserInput
import com.plm.directory.api.PatchOperation
import com.plm.directory.infra.DirectoryRepository
import com.plm.directory.infra.Duplicate
import com.plm.directory.infra.GroupRow
import com.plm.identity.api.PrincipalDirectory
import com.plm.identity.api.ProvisionPrincipal
import com.plm.platform.actor.Actor
import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.ValidationException
import com.plm.platform.events.EventEnvelope
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import com.plm.platform.id.UuidV7
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** `attribute eq "value"` terms joined by `and` (what identity providers send). */
private val TERM =
    Regex("""^\s*([A-Za-z0-9_.:\[\]]+)\s+eq\s+(?:"((?:[^"\\]|\\.)*)"|(true|false|null))\s*$""", RegexOption.IGNORE_CASE)

/** `members[value eq "id"]` */
private val MEMBER_PATH = Regex("""^members\[\s*value\s+eq\s+"([^"]+)"\s*]$""", RegexOption.IGNORE_CASE)

internal fun parseFilter(filter: String?): List<Pair<String, String?>> {
    if (filter.isNullOrBlank()) return emptyList()
    return filter.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)).map { term ->
        val m =
            TERM.matchEntire(term)
                ?: throw ValidationException(
                    "SCIM_INVALID_FILTER",
                    "Only 'attribute eq \"value\"' filters are supported: $term",
                )
        val attribute = m.groupValues[1].substringAfterLast(':').lowercase()
        val value = m.groups[2]?.value?.replace("\\\"", "\"") ?: m.groupValues[3].lowercase().takeIf { it != "null" }
        attribute to value
    }
}

private fun truthy(value: Any?): Boolean =
    when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> throw ValidationException("SCIM_INVALID_VALUE", "Expected true or false, got $value")
    }

@Service
@Transactional
internal class DefaultDirectoryService(
    private val repository: DirectoryRepository,
    private val principals: PrincipalDirectory,
    private val sharedTeams: SharedTeamService,
    private val contexts: ContextService,
    private val clock: Clock,
) : DirectoryService {
    private fun requireAdmin(actor: Actor) {
        if (!actor.siteAdmin) {
            throw ForbiddenException(
                "ADMIN_REQUIRED",
                "Only site administrators (or a directory token) manage the directory",
            )
        }
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun <T> unique(block: () -> T): T =
        try {
            block()
        } catch (e: Duplicate) {
            throw ConflictException("SCIM_UNIQUENESS", e.message ?: "Already exists").apply { initCause(e) }
        }

    @Transactional(readOnly = true)
    override fun status(actor: Actor): DirectoryStatus {
        requireAdmin(actor)
        val users = repository.users(actor.tenant)
        return DirectoryStatus(
            users.size,
            users.count {
                it.active
            },
            repository.groups(actor.tenant).size,
            repository.lastChange(actor.tenant),
        )
    }

    // ---------- Users ----------

    private fun userMatches(
        user: DirectoryUser,
        term: Pair<String, String?>,
    ): Boolean {
        val (attribute, value) = term
        return when (attribute) {
            "id" -> user.id.toString() == value
            "username" -> user.userName.equals(value, ignoreCase = true)
            "externalid" -> user.externalId == value
            "displayname" -> user.displayName == value
            "emails", "emails.value", "emails[type eq \"work\"].value" -> user.email.equals(value, ignoreCase = true)
            "active" -> user.active.toString() == value
            else -> throw ValidationException("SCIM_INVALID_FILTER", "Users can't be filtered by $attribute")
        }
    }

    @Transactional(readOnly = true)
    override fun users(
        actor: Actor,
        filter: String?,
        startIndex: Int,
        count: Int,
    ): DirectoryPage<DirectoryUser> {
        requireAdmin(actor)
        val terms = parseFilter(filter)
        val all = repository.users(actor.tenant).filter { u -> terms.all { userMatches(u, it) } }
        return page(all, startIndex, count)
    }

    private fun <T> page(
        all: List<T>,
        startIndex: Int,
        count: Int,
    ): DirectoryPage<T> {
        val start = startIndex.coerceAtLeast(1)
        return DirectoryPage(all.size, start, all.drop(start - 1).take(count.coerceIn(0, 1000)))
    }

    @Transactional(readOnly = true)
    override fun user(
        actor: Actor,
        id: UUID,
    ): DirectoryUser {
        requireAdmin(actor)
        return find(actor.tenant, id)
    }

    private fun find(
        tenant: TenantId,
        id: UUID,
    ) = repository.user(tenant, id)
        ?: throw NotFoundException("SCIM_USER_NOT_FOUND", "User $id is not in the directory", mapOf("id" to id))

    private fun displayNameOf(input: DirectoryUserInput) =
        input.displayName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(input.givenName, input.familyName).joinToString(" ").takeIf { it.isNotBlank() }
            ?: input.userName

    private fun save(
        actor: Actor,
        existing: DirectoryUser?,
        input: DirectoryUserInput,
    ): DirectoryUser {
        val userName = input.userName.trim()
        if (userName.isEmpty()) throw ValidationException("SCIM_INVALID_VALUE", "userName is required")
        // A new person's id until their first sign-in: the identity provider's id for them, if it sent one.
        val principal =
            principals.provision(
                actor,
                ProvisionPrincipal(
                    PrincipalId(existing?.principalId ?: input.externalId?.takeIf { it.isNotBlank() } ?: userName),
                    userName,
                    displayNameOf(input),
                    input.email,
                    input.active,
                ),
            )
        val now = now()
        val user =
            DirectoryUser(
                id = existing?.id ?: UuidV7.generate(clock),
                principalId = principal.id.value,
                externalId = input.externalId?.takeIf { it.isNotBlank() },
                userName = userName,
                displayName = input.displayName?.takeIf { it.isNotBlank() },
                givenName = input.givenName,
                familyName = input.familyName,
                email = input.email?.takeIf { it.isNotBlank() },
                active = input.active,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
        unique { repository.saveUser(actor.tenant, user) }
        // Joining or leaving (active) changes who is on each of their groups' teams.
        if (existing != null && (existing.active != user.active || existing.principalId != user.principalId)) {
            repository.groupsOf(user.id).forEach { syncTeam(actor, it) }
        }
        return user
    }

    override fun createUser(
        actor: Actor,
        input: DirectoryUserInput,
    ): DirectoryUser {
        requireAdmin(actor)
        if (repository.users(actor.tenant).any { it.userName.equals(input.userName.trim(), ignoreCase = true) }) {
            throw ConflictException("SCIM_UNIQUENESS", "A user named ${input.userName} already exists")
        }
        return save(actor, null, input)
    }

    override fun replaceUser(
        actor: Actor,
        id: UUID,
        input: DirectoryUserInput,
    ): DirectoryUser {
        requireAdmin(actor)
        return save(actor, find(actor.tenant, id), input)
    }

    override fun patchUser(
        actor: Actor,
        id: UUID,
        operations: List<PatchOperation>,
    ): DirectoryUser {
        requireAdmin(actor)
        val existing = find(actor.tenant, id)
        var input =
            DirectoryUserInput(
                existing.externalId,
                existing.userName,
                existing.displayName,
                existing.givenName,
                existing.familyName,
                existing.email,
                existing.active,
            )
        for (op in operations) {
            val kind = op.op.lowercase()
            if (kind !in setOf("add", "replace", "remove")) {
                throw ValidationException("SCIM_INVALID_VALUE", "Unknown operation ${op.op}")
            }
            // Without a path, the value is an object of attributes to set.
            val changes: Map<String, Any?> =
                if (op.path == null) {
                    (op.value as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
                        ?: throw ValidationException(
                            "SCIM_INVALID_VALUE",
                            "A patch without a path needs an object value",
                        )
                } else {
                    mapOf(op.path to if (kind == "remove") null else op.value)
                }
            for ((path, value) in changes) {
                input = applyUser(input, path, value)
            }
        }
        return save(actor, existing, input)
    }

    private fun applyUser(
        input: DirectoryUserInput,
        rawPath: String,
        value: Any?,
    ): DirectoryUserInput {
        val path = rawPath.substringAfterLast("User:").lowercase()

        fun text() = value?.toString()
        return when (path) {
            "active" -> input.copy(active = value != null && truthy(value))
            "username" -> input.copy(userName = text() ?: input.userName)
            "displayname" -> input.copy(displayName = text())
            "externalid" -> input.copy(externalId = text())
            "name.givenname" -> input.copy(givenName = text())
            "name.familyname" -> input.copy(familyName = text())
            "name" -> {
                val name = value as? Map<*, *>
                input.copy(
                    givenName = name?.get("givenName")?.toString(),
                    familyName = name?.get("familyName")?.toString(),
                )
            }
            "emails", "emails.value", "emails[type eq \"work\"].value", "emails[primary eq true].value" ->
                input.copy(email = (value as? List<*>)?.let(::primaryEmail) ?: text())
            // Attributes this system does not keep (title, phone numbers, addresses...).
            else -> input
        }
    }

    override fun deleteUser(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        val existing = find(actor.tenant, id)
        val groups = repository.groupsOf(id)
        principals.provision(
            actor,
            ProvisionPrincipal(
                PrincipalId(existing.principalId),
                existing.userName,
                existing.displayName ?: existing.userName,
                existing.email,
                active = false,
            ),
        )
        repository.deleteUser(id)
        groups.forEach { syncTeam(actor, it) }
    }

    // ---------- Groups ----------

    private fun groupOf(
        actor: Actor,
        row: GroupRow,
    ): DirectoryGroup {
        val team = sharedTeams.list(actor).firstOrNull { it.directoryGroup == row.id.toString() }
        val contextNames =
            team?.let { t ->
                sharedTeams.attachedTo(actor, t.id).map { contexts.get(actor, it).name }
            } ?: emptyList()
        return DirectoryGroup(
            id = row.id,
            externalId = row.externalId,
            displayName = row.displayName,
            role = row.role,
            teamId = team?.id?.value,
            members = repository.members(row.id),
            contexts = contextNames,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
            version = row.version,
        )
    }

    private fun findGroup(
        tenant: TenantId,
        id: UUID,
    ) = repository.group(tenant, id)
        ?: throw NotFoundException("SCIM_GROUP_NOT_FOUND", "Group $id is not in the directory", mapOf("id" to id))

    /** Makes the group's shared team match its active members. */
    private fun syncTeam(
        actor: Actor,
        groupId: UUID,
    ) {
        val row = repository.group(actor.tenant, groupId) ?: return
        val role = TeamRole(row.role)
        sharedTeams.syncDirectoryTeam(
            actor,
            row.id.toString(),
            row.displayName,
            repository.activePrincipals(row.id).associate { PrincipalId(it) to role },
        )
    }

    @Transactional(readOnly = true)
    override fun groups(
        actor: Actor,
        filter: String?,
        startIndex: Int,
        count: Int,
    ): DirectoryPage<DirectoryGroup> {
        requireAdmin(actor)
        val terms = parseFilter(filter)
        val all =
            repository.groups(actor.tenant).filter { g ->
                terms.all { (attribute, value) ->
                    when (attribute) {
                        "id" -> g.id.toString() == value
                        "displayname" -> g.displayName.equals(value, ignoreCase = true)
                        "externalid" -> g.externalId == value
                        "members.value", "members" -> repository.members(g.id).any { it.userId.toString() == value }
                        else -> throw ValidationException(
                            "SCIM_INVALID_FILTER",
                            "Groups can't be filtered by $attribute",
                        )
                    }
                }
            }
        val p = page(all, startIndex, count)
        return DirectoryPage(p.total, p.startIndex, p.items.map { groupOf(actor, it) })
    }

    @Transactional(readOnly = true)
    override fun group(
        actor: Actor,
        id: UUID,
    ): DirectoryGroup {
        requireAdmin(actor)
        return groupOf(actor, findGroup(actor.tenant, id))
    }

    private fun saveGroup(
        actor: Actor,
        existing: GroupRow?,
        externalId: String?,
        displayName: String,
        members: Collection<UUID>?,
        role: String? = null,
    ): DirectoryGroup {
        val name = displayName.trim()
        if (name.isEmpty()) throw ValidationException("SCIM_INVALID_VALUE", "displayName is required")
        val now = now()
        val row =
            GroupRow(
                id = existing?.id ?: UuidV7.generate(clock),
                externalId = externalId?.takeIf { it.isNotBlank() },
                displayName = name,
                role = role ?: existing?.role ?: "MEMBER",
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
        unique { repository.saveGroup(actor.tenant, row) }
        if (members != null) repository.setMembers(row.id, members)
        syncTeam(actor, row.id)
        return groupOf(actor, row)
    }

    override fun createGroup(
        actor: Actor,
        input: DirectoryGroupInput,
    ): DirectoryGroup {
        requireAdmin(actor)
        if (repository
                .groups(
                    actor.tenant,
                ).any { it.displayName.equals(input.displayName.trim(), ignoreCase = true) }
        ) {
            throw ConflictException("SCIM_UNIQUENESS", "A group named ${input.displayName} already exists")
        }
        return saveGroup(actor, null, input.externalId, input.displayName, input.members)
    }

    override fun replaceGroup(
        actor: Actor,
        id: UUID,
        input: DirectoryGroupInput,
    ): DirectoryGroup {
        requireAdmin(actor)
        return saveGroup(actor, findGroup(actor.tenant, id), input.externalId, input.displayName, input.members)
    }

    private fun memberIds(value: Any?): List<UUID> =
        when (value) {
            null -> emptyList()
            is List<*> -> value.flatMap(::memberIds)
            is Map<*, *> -> listOfNotNull(value["value"]?.toString()?.let(::uuidOrNull))
            else -> listOfNotNull(uuidOrNull(value.toString()))
        }

    private fun uuidOrNull(s: String) = runCatching { UUID.fromString(s) }.getOrNull()

    override fun patchGroup(
        actor: Actor,
        id: UUID,
        operations: List<PatchOperation>,
    ): DirectoryGroup {
        requireAdmin(actor)
        val existing = findGroup(actor.tenant, id)
        var name = existing.displayName
        var externalId = existing.externalId
        val members = repository.members(id).map { it.userId }.toMutableSet()
        for (op in operations) {
            val kind = op.op.lowercase()
            val path = op.path?.trim()
            val memberPath = path?.let { MEMBER_PATH.matchEntire(it) }
            when {
                kind !in setOf("add", "replace", "remove") ->
                    throw ValidationException("SCIM_INVALID_VALUE", "Unknown operation ${op.op}")
                memberPath != null && kind == "remove" -> uuidOrNull(memberPath.groupValues[1])?.let(members::remove)
                path.equals("members", ignoreCase = true) ->
                    when (kind) {
                        "add" -> members += memberIds(op.value)
                        "replace" -> {
                            members.clear()
                            members += memberIds(op.value)
                        }
                        // Without a value: everyone leaves.
                        else -> if (op.value == null) members.clear() else members -= memberIds(op.value).toSet()
                    }
                path.equals("displayName", ignoreCase = true) -> name = op.value?.toString() ?: name
                path.equals("externalId", ignoreCase = true) -> externalId = op.value?.toString()
                path == null && op.value is Map<*, *> -> {
                    val v = op.value
                    v["displayName"]?.let { name = it.toString() }
                    if (v.containsKey("externalId")) externalId = v["externalId"]?.toString()
                    v["members"]?.let {
                        if (kind == "replace") members.clear()
                        members += memberIds(it)
                    }
                }
                else -> throw ValidationException("SCIM_INVALID_PATH", "Groups can't be patched at ${op.path}")
            }
        }
        return saveGroup(actor, existing, externalId, name, members)
    }

    override fun deleteGroup(
        actor: Actor,
        id: UUID,
    ) {
        requireAdmin(actor)
        findGroup(actor.tenant, id)
        repository.deleteGroup(id)
        sharedTeams.deleteDirectoryTeam(actor, id.toString())
    }

    override fun setGroupRole(
        actor: Actor,
        id: UUID,
        role: String,
    ): DirectoryGroup {
        requireAdmin(actor)
        val existing = findGroup(actor.tenant, id)
        return saveGroup(actor, existing, existing.externalId, existing.displayName, null, role.trim().uppercase())
    }

    /** A person the directory made signed in: they keep their directory entry under their real id. */
    @EventListener(EventEnvelope::class)
    fun on(envelope: EventEnvelope) {
        if (envelope.eventType != "identity.PrincipalRekeyed.v1") return
        val from = envelope.payload["previousId"] as? String ?: return
        val to = envelope.payload["newId"] as? String ?: return
        repository.rekey(TenantId(envelope.tenantId), from, to)
    }
}

/** The primary (else the first) address of a SCIM `emails` list. */
internal fun primaryEmail(emails: List<*>): String? {
    val maps = emails.filterIsInstance<Map<*, *>>()
    return (maps.firstOrNull { it["primary"] == true } ?: maps.firstOrNull())?.get("value")?.toString()
}
