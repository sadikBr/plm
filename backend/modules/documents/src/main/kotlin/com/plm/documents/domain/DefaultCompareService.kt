package com.plm.documents.domain

import com.plm.content.api.ContentItem
import com.plm.content.api.ContentRole
import com.plm.content.api.ContentService
import com.plm.documents.api.AttributeDifference
import com.plm.documents.api.CompareService
import com.plm.documents.api.CompareSide
import com.plm.documents.api.ComparedVersion
import com.plm.documents.api.Comparison
import com.plm.documents.api.DiffLine
import com.plm.documents.api.FileChange
import com.plm.documents.api.FileDifference
import com.plm.documents.api.LineChange
import com.plm.documents.api.TextDifference
import com.plm.platform.actor.Actor
import com.plm.platform.error.NotFoundException
import com.plm.versioning.api.Iteration
import com.plm.versioning.api.MasterId
import com.plm.versioning.api.VersioningService
import org.springframework.stereotype.Service

private const val MAX_TEXT = 512 * 1024
private const val MAX_LINES = 3000

@Service
internal class DefaultCompareService(
    private val versioning: VersioningService,
    private val content: ContentService,
) : CompareService {
    override fun compare(
        actor: Actor,
        left: CompareSide,
        right: CompareSide,
    ): Comparison {
        val (l, lIteration) = resolve(actor, left)
        val (r, rIteration) = resolve(actor, right)
        val names = (lIteration.attributes.keys + rIteration.attributes.keys).sorted()
        val attributes =
            names.map { n ->
                val a = lIteration.attributes[n]
                val b = rIteration.attributes[n]
                AttributeDifference(n, a, b, a != b)
            }
        val lFiles = content.list(actor, MasterId(l.objectId), lIteration.id.value)
        val rFiles = content.list(actor, MasterId(r.objectId), rIteration.id.value)
        return Comparison(l, r, attributes, files(lFiles, rFiles), primaryText(actor, lFiles, rFiles))
    }

    private fun resolve(
        actor: Actor,
        side: CompareSide,
    ): Pair<ComparedVersion, Iteration> {
        val obj = versioning.get(actor, MasterId(side.objectId))
        val iteration =
            if (side.iterationId == null) {
                obj.latest
            } else {
                versioning.history(actor, obj.id).firstOrNull { it.id.value == side.iterationId }
                    ?: throw NotFoundException("ITERATION_NOT_FOUND", "There's no such version of ${obj.number}")
            }
        return ComparedVersion(
            obj.id.value,
            iteration.id.value,
            obj.number,
            obj.name,
            iteration.version,
            iteration.state,
            obj.typeId.value,
        ) to
            iteration
    }

    /** Files matched by role and name (the primary files with each other, whatever their names). */
    private fun files(
        left: List<ContentItem>,
        right: List<ContentItem>,
    ): List<FileDifference> {
        fun key(c: ContentItem) = if (c.role == ContentRole.PRIMARY) "PRIMARY" else "A:${c.fileName.lowercase()}"
        val l = left.associateBy(::key)
        val r = right.associateBy(::key)
        return (l.keys + r.keys).distinct().sortedWith(compareBy({ it != "PRIMARY" }, { it })).map { k ->
            val a = l[k]
            val b = r[k]
            FileDifference(
                role = (a ?: b)!!.role.name,
                fileName =
                    if (a != null &&
                        b != null &&
                        a.fileName != b.fileName
                    ) {
                        "${a.fileName} → ${b.fileName}"
                    } else {
                        (b ?: a)!!.fileName
                    },
                change =
                    when {
                        a == null -> FileChange.ADDED
                        b == null -> FileChange.REMOVED
                        a.sha256 == b.sha256 -> FileChange.SAME
                        else -> FileChange.CHANGED
                    },
                leftContentId = a?.id,
                rightContentId = b?.id,
                leftSize = a?.size,
                rightSize = b?.size,
            )
        }
    }

    private fun isText(c: ContentItem) =
        c.contentType.startsWith("text/") ||
            c.contentType in setOf("application/json", "application/xml", "application/x-yaml")

    private fun primaryText(
        actor: Actor,
        left: List<ContentItem>,
        right: List<ContentItem>,
    ): TextDifference? {
        val a = left.firstOrNull { it.role == ContentRole.PRIMARY } ?: return null
        val b = right.firstOrNull { it.role == ContentRole.PRIMARY } ?: return null
        val name = if (a.fileName == b.fileName) a.fileName else "${a.fileName} → ${b.fileName}"
        if (!isText(a) || !isText(b)) return null
        if (a.size > MAX_TEXT ||
            b.size > MAX_TEXT
        ) {
            return TextDifference(name, emptyList(), 0, 0, "Too large to compare line by line")
        }
        val lt = content.open(actor, a.id).use { it.readAllBytes().decodeToString() }.lines()
        val rt = content.open(actor, b.id).use { it.readAllBytes().decodeToString() }.lines()
        if (lt.size > MAX_LINES || rt.size > MAX_LINES) {
            return TextDifference(name, emptyList(), 0, 0, "Too many lines to compare")
        }
        val lines = LineDiff.diff(lt, rt)
        return TextDifference(
            name,
            lines,
            lines.count { it.change == LineChange.ADDED },
            lines.count {
                it.change ==
                    LineChange.REMOVED
            },
            null,
        )
    }
}

/** Line diff by longest common subsequence: fine for the few thousand lines we allow. */
internal object LineDiff {
    fun diff(
        a: List<String>,
        b: List<String>,
    ): List<DiffLine> {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> out += DiffLine(LineChange.SAME, a[i], ++i, ++j)
                lcs[i + 1][j] >= lcs[i][j + 1] -> out += DiffLine(LineChange.REMOVED, a[i], ++i, null)
                else -> out += DiffLine(LineChange.ADDED, b[j], null, ++j)
            }
        }
        while (i < n) out += DiffLine(LineChange.REMOVED, a[i], ++i, null)
        while (j < m) out += DiffLine(LineChange.ADDED, b[j], null, ++j)
        return out
    }
}
