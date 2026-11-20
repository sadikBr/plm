package com.plm.app.web

import com.plm.platform.error.ConflictException
import com.plm.platform.error.ForbiddenException
import com.plm.platform.error.NotFoundException
import com.plm.platform.error.PlmException
import com.plm.platform.error.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/**
 * Maps [PlmException]s to RFC 9457 problem details:
 *
 * ```json
 * {"type":"urn:plm:error:ALREADY_CHECKED_OUT","title":"Conflict","status":409,
 *  "detail":"0000000001 is already checked out by bob","code":"ALREADY_CHECKED_OUT",
 *  "details":{"checkedOutBy":"bob"}}
 * ```
 */
@RestControllerAdvice
internal class ProblemDetailsAdvice {
    @ExceptionHandler(PlmException::class)
    fun handle(e: PlmException): ResponseEntity<ProblemDetail> {
        val status =
            when (e) {
                is NotFoundException -> HttpStatus.NOT_FOUND
                is ConflictException -> HttpStatus.CONFLICT
                is ValidationException -> HttpStatus.UNPROCESSABLE_CONTENT
                is ForbiddenException -> HttpStatus.FORBIDDEN
            }
        val problem =
            ProblemDetail.forStatusAndDetail(status, e.message ?: e.code).apply {
                type = URI.create("urn:plm:error:${e.code}")
                setProperty("code", e.code)
                if (e.details.isNotEmpty()) setProperty("details", e.details)
            }
        return ResponseEntity.status(status).body(problem)
    }
}
