package com.plm.workflow.domain

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.WorkerFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(TemporalRuntime::class.java)

/**
 * The connection to Temporal (ADR 0006). `plm.workflow.temporal.target` is the service's
 * `host:port`, or `in-memory` for Temporal's in-process test server: tests, and trying the
 * application without the service (processes then don't survive a restart).
 */
@Component
class TemporalRuntime(
    @param:Value("\${plm.workflow.temporal.target:in-memory}") private val target: String,
    @param:Value("\${plm.workflow.temporal.namespace:default}") private val namespace: String,
    private val activities: DefaultProcessActivities,
) : SmartLifecycle {
    private var environment: TestWorkflowEnvironment? = null
    private var stubs: WorkflowServiceStubs? = null
    lateinit var client: WorkflowClient
    private lateinit var factory: WorkerFactory

    @Volatile private var running = false

    override fun start() {
        // Load the JSON reader now: the first read inside a workflow must not stall its thread.
        Definitions.read("""{"start": "end", "nodes": [{"id": "end", "kind": "END"}]}""")
        if (target == "in-memory") {
            val env =
                TestWorkflowEnvironment.newInstance(
                    TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build(),
                )
            environment = env
            client = env.workflowClient
            factory = env.workerFactory
        } else {
            val s =
                WorkflowServiceStubs.newServiceStubs(
                    WorkflowServiceStubsOptions.newBuilder().setTarget(target).build(),
                )
            stubs = s
            client = WorkflowClient.newInstance(s, WorkflowClientOptions.newBuilder().setNamespace(namespace).build())
            factory = WorkerFactory.newInstance(client)
        }
        val worker = factory.newWorker(PROCESS_QUEUE)
        worker.registerWorkflowImplementationTypes(ProcessWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        factory.start()
        running = true
        log.info("Processes run on Temporal at {} (queue {})", target, PROCESS_QUEUE)
    }

    override fun stop() {
        running = false
        runCatching { factory.shutdown() }
        environment?.close()
        stubs?.shutdown()
    }

    override fun isRunning() = running
}
