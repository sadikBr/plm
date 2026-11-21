package com.plm.identity.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The site's built-in administrator: one account, made with the installation, that holds the
 * site administrator role. More site administrators are possible (grant the role in the identity
 * provider) but not recommended; [onlyBuiltIn] refuses the rights to anyone else.
 */
@Component
class SiteAdminSettings(
    @Value("\${plm.identity.built-in-admin:plmadmin}") val builtInAdmin: String,
    @Value("\${plm.identity.only-built-in:false}") val onlyBuiltIn: Boolean,
)
