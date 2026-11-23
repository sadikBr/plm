package com.plm.migration.api

import com.plm.jobs.api.Job
import com.plm.platform.actor.Actor
import java.time.Instant
import java.util.UUID

/** How the other system's names become this system's (all optional: same name by default). */
data class MigrationMappings(
    /** Legacy type → a type name or display name here. */
    val types: Map<String, String> = emptyMap(),
    /** Legacy state → a lifecycle state key here. */
    val states: Map<String, String> = emptyMap(),
    /** Legacy attribute → an attribute name here; an empty value drops it. */
    val attributes: Map<String, String> = emptyMap(),
    /** Legacy user id → a user name here. Users not mapped are brought in from the manifest. */
    val users: Map<String, String> = emptyMap(),
)

data class MigrationIssue(
    val legacyId: String?,
    val number: String?,
    /** ERROR stops the run; WARNING doesn't. */
    val severity: String,
    val message: String,
)

/** The dry run: what the package holds and what a run would do with the current mappings. */
data class MigrationAnalysis(
    val source: String?,
    val objects: Int,
    val iterations: Int,
    val files: Int,
    val usages: Int,
    val documentLinks: Int,
    val users: Int,
    /** Objects a previous run brought in: they are skipped. */
    val alreadyDone: Int,
    /** Legacy names nothing here matches, with how often they occur: map them. */
    val unmappedTypes: Map<String, Int>,
    val unmappedStates: Map<String, Int>,
    val unmappedAttributes: Map<String, Int>,
    val errors: Int,
    val warnings: Int,
    /** At most 500. */
    val issues: List<MigrationIssue>,
)

data class Migration(
    val id: UUID,
    val name: String,
    val contextId: UUID,
    val contextName: String,
    val fileName: String,
    val size: Long,
    val mappings: MigrationMappings,
    val analysis: MigrationAnalysis,
    val done: Int,
    val failed: Int,
    val lastJobId: UUID?,
    val createdAt: Instant,
    val createdBy: String,
)

data class MigrationItem(
    val legacyId: String,
    val number: String,
    val objectId: UUID?,
    val status: String,
    val message: String?,
)

/**
 * Migration from other PLM systems (roadmap 9.3). The package is a zip with a `manifest.json`
 * (format `plm-migration/1`, see docs/migration-format.md) and the files it names. Every object
 * keeps its number, its versions and iterations with their dates, authors, states and attributes,
 * its files per iteration, and its structure and document links. Mappings translate types,
 * states, attributes and users; the analysis is the dry run. A run is a job; objects a run
 * already brought in are skipped, so a stopped or partly failed run can simply be run again.
 * Site administrators only.
 *
 * Errors: `ADMIN_REQUIRED`, `MIGRATION_NOT_FOUND`, `INVALID_MIGRATION_PACKAGE`, `INVALID_MIGRATION`.
 */
interface MigrationService {
    fun list(actor: Actor): List<Migration>

    fun get(
        actor: Actor,
        id: UUID,
    ): Migration

    fun create(
        actor: Actor,
        name: String,
        contextId: UUID,
        fileName: String,
        bytes: ByteArray,
    ): Migration

    fun updateMappings(
        actor: Actor,
        id: UUID,
        mappings: MigrationMappings,
    ): Migration

    fun items(
        actor: Actor,
        id: UUID,
    ): List<MigrationItem>

    /** Starts (or resumes) the run. Refused while the analysis has errors. */
    fun run(
        actor: Actor,
        id: UUID,
    ): Job

    fun delete(
        actor: Actor,
        id: UUID,
    )
}
