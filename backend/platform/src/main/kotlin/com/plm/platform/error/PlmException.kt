package com.plm.platform.error

/**
 * Base class for expected, client-visible failures.
 *
 * [code] is a stable machine-readable identifier (e.g. `ALREADY_CHECKED_OUT`). Clients and the UI
 * switch on it, so treat codes as part of the public API: never rename one.
 */
sealed class PlmException(
    val code: String,
    message: String,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

class NotFoundException(
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
) : PlmException(code, message, details)

class ConflictException(
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
) : PlmException(code, message, details)

class ValidationException(
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
) : PlmException(code, message, details)

class ForbiddenException(
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
) : PlmException(code, message, details)
