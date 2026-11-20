package com.plm.platform.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {
    /** Inject this instead of calling `Instant.now()`, so tests can control time. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
