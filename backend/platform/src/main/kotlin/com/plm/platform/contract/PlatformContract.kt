package com.plm.platform.contract

/**
 * The version of the contract extensions are written against (the manifest model, the expression
 * variables, the REST API they may call). Extensions declare the range they support
 * (`platform: ">=1.0 <2.0"`); an installed extension whose range no longer includes this version
 * is switched off, not broken. Raise the minor version for additions, the major one for changes
 * that can break extensions.
 */
object PlatformContract {
    const val VERSION = "1.0"
}
