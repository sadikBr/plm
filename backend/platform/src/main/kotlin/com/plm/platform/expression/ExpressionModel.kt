package com.plm.platform.expression

/**
 * The variables expressions see. Every place that evaluates expressions builds them the same way,
 * so an expression written for one purpose means the same in another.
 *
 * - `object`: `number`, `name`, `type` (type name), `types` (the type and its ancestors, so
 *   `'Part' in object.types` covers subtypes), `state`, `revision`, `iteration`, `version`,
 *   `checkedOut`, `attributes` (every attribute of the type, null when unset),
 *   `context` (`name`, `kind`).
 * - `user`: `id`, `siteAdmin`, `permissions` (e.g. `'MODIFY' in user.permissions`).
 * - `event` (operation rules only): `operation` (e.g. `object.check-in`), and `from`, `to`,
 *   `comment` for set state, `comment` for check-in, `number` and `name` for rename.
 */
object ExpressionModel {
    val VARIABLES = listOf("object", "user", "event")
}
