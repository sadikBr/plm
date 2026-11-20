package com.plm.identity.domain

import com.plm.jobs.api.JobContext
import com.plm.jobs.api.JobHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** Removes short-lived tokens (access, delegated) a day after they expired. Scheduled hourly. */
@Component
internal class PurgeTokensJob(
    private val accounts: DefaultServiceAccountService,
    private val tx: TransactionTemplate,
) : JobHandler {
    override val type = "identity.purge-tokens"
    override val label = "Purge expired tokens"
    override val description = "Removes access and delegated tokens a day after they expired"

    override fun run(context: JobContext): Map<String, Any?> {
        val removed = tx.execute { accounts.purge() }
        context.progress(removed, removed, "$removed tokens removed")
        return mapOf("removed" to removed)
    }
}
