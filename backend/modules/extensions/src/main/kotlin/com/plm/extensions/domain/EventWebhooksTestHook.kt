package com.plm.extensions.domain

import org.springframework.stereotype.Component

/** Lets tests send due webhook deliveries now, instead of waiting for the scheduler. */
@Component
class EventWebhooksTestHook internal constructor(
    private val webhooks: EventWebhooks,
) {
    fun deliverDue(): Int = webhooks.deliverDue()
}
