package com.plm.platform.actor

import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId

/**
 * Who is performing an operation, and on behalf of which tenant.
 *
 * Every service operation takes an [Actor] explicitly instead of reading thread-local state.
 * That keeps services easy to test and safe to call from async consumers and workflows.
 */
data class Actor(
    val tenant: TenantId,
    val principal: PrincipalId,
)
