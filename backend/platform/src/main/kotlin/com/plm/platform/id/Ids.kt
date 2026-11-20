package com.plm.platform.id

import java.util.UUID

/** Tenant that owns a row. Every table carries one (ADR 0009). */
@JvmInline
value class TenantId(
    val value: UUID,
) {
    override fun toString() = value.toString()

    companion object {
        /** Seeded by migration `platform/V202611201000__tenant.sql`. Single-tenant deployments use only this one. */
        val DEFAULT = TenantId(UUID.fromString("00000000-0000-7000-8000-000000000001"))
    }
}

/** A user or service account, identified by its OIDC subject. */
@JvmInline
value class PrincipalId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "principal must not be blank" }
    }

    override fun toString() = value
}
