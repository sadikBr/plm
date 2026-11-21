package com.plm.search.infra.web

import com.plm.platform.actor.Actor
import com.plm.search.api.SaveTableView
import com.plm.search.api.SheetExport
import com.plm.search.api.TableViewService
import com.plm.search.domain.Xlsx
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
internal class TableViewController(
    private val views: TableViewService,
) {
    @GetMapping("/api/v1/table-views")
    fun list(
        actor: Actor,
        @RequestParam table: String,
    ) = views.list(actor, table)

    @PostMapping("/api/v1/table-views")
    @ResponseStatus(HttpStatus.CREATED)
    fun save(
        actor: Actor,
        @RequestBody request: SaveTableView,
    ) = views.save(actor, request)

    @PutMapping("/api/v1/table-views/{id}")
    fun update(
        actor: Actor,
        @PathVariable id: UUID,
        @RequestBody request: SaveTableView,
    ) = views.update(actor, id, request)

    @DeleteMapping("/api/v1/table-views/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: Actor,
        @PathVariable id: UUID,
    ) = views.delete(actor, id)

    /** The table as shown, as an Excel workbook. */
    @PostMapping(
        "/api/v1/export/xlsx",
        produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"],
    )
    fun xlsx(
        @Suppress("UNUSED_PARAMETER") actor: Actor,
        @RequestBody sheet: SheetExport,
    ): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename("${sheet.sheetName.take(60)}.xlsx")
                    .build()
                    .toString(),
            ).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(Xlsx.write(sheet))
}
