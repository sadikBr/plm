package com.plm.archfixture.beta

import com.plm.archfixture.alpha.domain.AlphaInternal

/** Deliberate boundary violation used by ArchitectureTest. */
class BetaService {
    fun leak() = AlphaInternal().value()
}
