package com.plm.access

import com.plm.platform.actor.Actor
import com.plm.platform.id.PrincipalId
import com.plm.platform.id.TenantId
import java.util.UUID

internal object Actors {
    val admin = Actor(TenantId.DEFAULT, PrincipalId("site-admin"), siteAdmin = true)

    /** A fresh principal per call, so tests never share team memberships. */
    fun user(name: String) = Actor(TenantId.DEFAULT, PrincipalId("$name-${UUID.randomUUID()}"))
}
