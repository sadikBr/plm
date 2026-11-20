package com.plm.content.infra

import com.plm.content.api.ContentItem
import com.plm.content.api.ContentRole
import com.plm.content.api.ThumbnailStatus
import com.plm.platform.id.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class Upload(
    val id: UUID,
    val tenant: TenantId,
    val createdBy: String,
    val fileName: String,
    val contentType: String,
    val declaredSize: Long?,
    val blobKey: String?,
    val expiresAt: Instant,
    val usedAt: Instant?,
)

internal data class Blob(
    val key: String,
    val size: Long,
    val sha256: String,
    val contentType: String,
    val thumbnailKey: String?,
    val thumbnail: ThumbnailStatus,
)

/** A content item with what's needed to check access to it. */
internal data class StoredItem(
    val item: ContentItem,
    val tenant: TenantId,
    val blobKey: String,
    val thumbnailKey: String?,
    /** CHECKED_IN or WORKING */
    val iterationStatus: String,
    val checkedOutBy: String?,
)

@Repository
internal class ContentRepository(
    private val jdbc: JdbcClient,
) {
    private fun ts(i: Instant?) = i?.let { Timestamp.from(it) }

    // ---------- Uploads and blobs ----------

    fun insertUpload(
        u: Upload,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into vault_upload (id, tenant_id, created_by, file_name, content_type, declared_size, expires_at, created_at)
                values (:id, :tenant, :by, :name, :type, :size, :expires, :now)
                """.trimIndent(),
            ).param("id", u.id)
            .param("tenant", u.tenant.value)
            .param("by", u.createdBy)
            .param("name", u.fileName)
            .param("type", u.contentType)
            .param("size", u.declaredSize)
            .param("expires", ts(u.expiresAt))
            .param("now", ts(now))
            .update()
    }

    fun upload(id: UUID): Upload? =
        jdbc
            .sql("select * from vault_upload where id = :id")
            .param("id", id)
            .query { rs, _ ->
                Upload(
                    id = rs.getObject("id", UUID::class.java),
                    tenant = TenantId(rs.getObject("tenant_id", UUID::class.java)),
                    createdBy = rs.getString("created_by"),
                    fileName = rs.getString("file_name"),
                    contentType = rs.getString("content_type"),
                    declaredSize = rs.getObject("declared_size") as Long?,
                    blobKey = rs.getString("blob_key"),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    usedAt = rs.getTimestamp("used_at")?.toInstant(),
                )
            }.optional()
            .orElse(null)

    fun insertBlob(
        tenant: TenantId,
        blob: Blob,
        by: String,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into vault_blob (key, tenant_id, size, sha256, content_type, thumbnail, created_at, created_by)
                values (:key, :tenant, :size, :sha, :type, :thumb, :now, :by)
                """.trimIndent(),
            ).param("key", blob.key)
            .param("tenant", tenant.value)
            .param("size", blob.size)
            .param("sha", blob.sha256)
            .param("type", blob.contentType)
            .param("thumb", blob.thumbnail.name)
            .param("now", ts(now))
            .param("by", by)
            .update()
    }

    fun received(
        uploadId: UUID,
        blobKey: String,
    ) {
        jdbc
            .sql(
                "update vault_upload set blob_key = :key where id = :id",
            ).param("id", uploadId)
            .param("key", blobKey)
            .update()
    }

    fun used(
        uploadId: UUID,
        now: Instant,
    ) {
        jdbc
            .sql(
                "update vault_upload set used_at = :now where id = :id",
            ).param("id", uploadId)
            .param("now", ts(now))
            .update()
    }

    fun blob(key: String): Blob? =
        jdbc
            .sql("select * from vault_blob where key = :key")
            .param("key", key)
            .query { rs, _ ->
                Blob(
                    rs.getString("key"),
                    rs.getLong("size"),
                    rs.getString("sha256"),
                    rs.getString("content_type"),
                    rs.getString("thumbnail_key"),
                    ThumbnailStatus.valueOf(rs.getString("thumbnail")),
                )
            }.optional()
            .orElse(null)

    fun thumbnail(
        blobKey: String,
        status: ThumbnailStatus,
        thumbnailKey: String?,
    ) {
        jdbc
            .sql("update vault_blob set thumbnail = :status, thumbnail_key = :thumb where key = :key")
            .param("key", blobKey)
            .param("status", status.name)
            .param("thumb", thumbnailKey)
            .update()
    }

    fun pages(key: String): Int? =
        jdbc
            .sql("select pages from vault_blob where key = :key")
            .param("key", key)
            .query { rs, _ -> rs.getObject("pages") as Int? }
            .optional()
            .orElse(null)

    fun setPages(
        key: String,
        pages: Int,
    ) {
        jdbc
            .sql(
                "update vault_blob set pages = :pages where key = :key",
            ).param("key", key)
            .param("pages", pages)
            .update()
    }

    /** Blobs nothing refers to any more (no content, no live upload), older than [before]. */
    fun orphans(before: Instant): List<Blob> =
        jdbc
            .sql(
                """
                select * from vault_blob b
                where b.created_at < :before
                  and not exists (select 1 from content_item c where c.blob_key = b.key)
                  and not exists (select 1 from vault_upload u where u.blob_key = b.key and u.used_at is null and u.expires_at > :before)
                limit 500
                """.trimIndent(),
            ).param("before", ts(before))
            .query { rs, _ ->
                Blob(
                    rs.getString("key"),
                    rs.getLong("size"),
                    rs.getString("sha256"),
                    rs.getString("content_type"),
                    rs.getString("thumbnail_key"),
                    ThumbnailStatus.valueOf(rs.getString("thumbnail")),
                )
            }.list()

    fun deleteBlob(key: String) {
        jdbc.sql("delete from vault_upload where blob_key = :key").param("key", key).update()
        jdbc.sql("delete from vault_blob where key = :key").param("key", key).update()
    }

    fun deleteExpiredUploads(before: Instant): Int =
        jdbc
            .sql("delete from vault_upload where expires_at < :before and (blob_key is null or used_at is not null)")
            .param("before", ts(before))
            .update()

    // ---------- Content ----------

    private val itemSql =
        """
        select c.*, b.size, b.sha256, b.content_type, b.thumbnail, b.thumbnail_key,
               i.status as iteration_status, i.checked_out_by
        from content_item c
        join vault_blob b on b.key = c.blob_key
        join object_iteration i on i.id = c.iteration_id
        """.trimIndent()

    fun items(iterationId: UUID): List<StoredItem> =
        jdbc
            .sql(
                "$itemSql where c.iteration_id = :it order by case c.role when 'PRIMARY' then 0 else 1 end, lower(c.file_name)",
            ).param("it", iterationId)
            .query { rs, _ -> map(rs) }
            .list()

    fun item(id: UUID): StoredItem? =
        jdbc
            .sql("$itemSql where c.id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun insertItem(
        tenant: TenantId,
        id: UUID,
        masterId: UUID,
        iterationId: UUID,
        role: ContentRole,
        fileName: String,
        description: String?,
        blobKey: String,
        by: String,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into content_item (id, tenant_id, master_id, iteration_id, role, file_name, description, blob_key, added_at, added_by)
                values (:id, :tenant, :master, :it, :role, :name, :description, :blob, :now, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("tenant", tenant.value)
            .param("master", masterId)
            .param("it", iterationId)
            .param("role", role.name)
            .param("name", fileName)
            .param("description", description)
            .param("blob", blobKey)
            .param("now", ts(now))
            .param("by", by)
            .update()
    }

    fun updateItem(
        id: UUID,
        fileName: String,
        description: String?,
    ) {
        jdbc
            .sql("update content_item set file_name = :name, description = :description where id = :id")
            .param("id", id)
            .param("name", fileName)
            .param("description", description)
            .update()
    }

    fun deleteItem(id: UUID) {
        jdbc.sql("delete from content_item where id = :id").param("id", id).update()
    }

    /** Copies [sourceIteration]'s content into [targetIteration] (another object's). */
    fun copyInto(
        sourceIteration: UUID,
        targetMaster: UUID,
        targetIteration: UUID,
        by: String,
        now: Instant,
        newId: () -> UUID,
    ): Int {
        val rows =
            jdbc
                .sql("select id from content_item where iteration_id = :src order by added_at")
                .param("src", sourceIteration)
                .query { rs, _ -> rs.getObject("id", UUID::class.java) }
                .list()
        rows.forEach { from ->
            jdbc
                .sql(
                    """
                    insert into content_item (id, tenant_id, master_id, iteration_id, role, file_name, description, blob_key,
                                              copied_from, added_at, added_by)
                    select :id, tenant_id, :master, :it, role, file_name, description, blob_key, coalesce(copied_from, id), :now, :by
                    from content_item where id = :from
                    """.trimIndent(),
                ).param("id", newId())
                .param("master", targetMaster)
                .param("it", targetIteration)
                .param("from", from)
                .param("now", ts(now))
                .param("by", by)
                .update()
        }
        return rows.size
    }

    /**
     * Carries content over to a new iteration (a working copy, a new revision) from the iteration
     * it's based on: same files, new rows. Reads the versioning module's iteration table.
     */
    fun carryOver(
        iterationId: UUID,
        newIds: (Int) -> List<UUID>,
    ): Int {
        val source =
            jdbc
                .sql("select based_on_id from object_iteration where id = :id")
                .param("id", iterationId)
                .query { rs, _ -> rs.getObject("based_on_id", UUID::class.java) }
                .optional()
                .orElse(null) ?: return 0
        val rows =
            jdbc
                .sql("select id from content_item where iteration_id = :src order by added_at")
                .param("src", source)
                .query { rs, _ -> rs.getObject("id", UUID::class.java) }
                .list()
        val ids = newIds(rows.size)
        rows.forEachIndexed { i, from ->
            jdbc
                .sql(
                    """
                    insert into content_item (id, tenant_id, master_id, iteration_id, role, file_name, description, blob_key,
                                              copied_from, added_at, added_by)
                    select :id, tenant_id, master_id, :it, role, file_name, description, blob_key, coalesce(copied_from, id),
                           added_at, added_by
                    from content_item where id = :from
                    """.trimIndent(),
                ).param("id", ids[i])
                .param("it", iterationId)
                .param("from", from)
                .update()
        }
        return rows.size
    }

    private fun map(rs: ResultSet): StoredItem {
        val item =
            ContentItem(
                id = rs.getObject("id", UUID::class.java),
                masterId = rs.getObject("master_id", UUID::class.java),
                iterationId = rs.getObject("iteration_id", UUID::class.java),
                role = ContentRole.valueOf(rs.getString("role")),
                fileName = rs.getString("file_name"),
                description = rs.getString("description"),
                contentType = rs.getString("content_type"),
                size = rs.getLong("size"),
                sha256 = rs.getString("sha256"),
                thumbnail = ThumbnailStatus.valueOf(rs.getString("thumbnail")),
                addedAt = rs.getTimestamp("added_at").toInstant(),
                addedBy = rs.getString("added_by"),
                carriedOver = rs.getObject("copied_from") != null,
            )
        return StoredItem(
            item,
            TenantId(rs.getObject("tenant_id", UUID::class.java)),
            rs.getString("blob_key"),
            rs.getString("thumbnail_key"),
            rs.getString("iteration_status"),
            rs.getString("checked_out_by"),
        )
    }
}
