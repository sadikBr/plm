package com.plm.platform.actor

import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId

/**
 * Who is performing an operation, and on behalf of which tenant.
 *
 * Every service operation takes an [Actor] explicitly instead of reading thread-local state.
 * That keeps services easy to test and safe to call from async consumers and workflows.
 *
 * [siteAdmin] holders bypass context-level access policies (ADR 0005). It comes from the
 * identity provider (Keycloak realm role `plm-admin`), never from request input.
 */
data class Actor(
    val tenant: TenantId,
    val principal: PrincipalId,
    val siteAdmin: Boolean = false,
)
