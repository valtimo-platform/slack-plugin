/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.valtimoplugins.slack.service

import com.ritense.case.service.CaseDefinitionService
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentService
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.valtimo.service.ProcessPropertyService
import com.ritense.valtimoplugins.slack.BaseTest
import com.ritense.valtimoplugins.slack.domain.SlackMessage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Execution
import org.operaton.bpm.engine.runtime.ExecutionQuery

/**
 * Covers the reply side of the starter: which of the instances parked at a receive task a
 * given Slack message may resume.
 *
 * The broadcast this guards against is not a hypothetical. The execution query is scoped to
 * a process definition and an activity, not to a case, so without correlation every case
 * waiting for its own answer would be handed the first message that arrived for any of them.
 */
class SlackMessageProcessStarterTest : BaseTest() {
    private lateinit var runtimeService: RuntimeService
    private lateinit var executionQuery: ExecutionQuery
    private lateinit var starter: SlackMessageProcessStarter

    @BeforeEach
    fun setUp() {
        runtimeService = mock()
        executionQuery = mock()

        whenever(runtimeService.createExecutionQuery()).thenReturn(executionQuery)
        whenever(executionQuery.processDefinitionId(any())).thenReturn(executionQuery)
        whenever(executionQuery.activityId(any())).thenReturn(executionQuery)

        starter =
            SlackMessageProcessStarter(
                runtimeService,
                mock<RepositoryService>(),
                mock<ProcessPropertyService>(),
                mock<ProcessDefinitionCaseDefinitionService>(),
                mock<ProcessDocumentService>(),
                mock<CaseDefinitionService>(),
            )
    }

    @Test
    fun `should resume only the case the reply belongs to`() {
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        awaits("instance-a", "1735689600.000100")
        awaits("instance-b", "1735689600.000200")

        val resumed = starter.start(receiveTask(), reply(threadTs = "1735689600.000200"))

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-b"), any<Map<String, Any>>())
        verify(runtimeService, never()).signal(eq("execution-a"), any<Map<String, Any>>())
    }

    @Test
    fun `should resume nothing when the reply belongs to a thread nobody is waiting on`() {
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "1735689600.000100")

        val resumed = starter.start(receiveTask(), reply(threadTs = "1735689600.000999"))

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
    }

    @Test
    fun `should resume nothing for a message that is part of no thread at all`() {
        // A fresh message in the channel is not an answer to anything. Signalling on it is
        // exactly the broadcast this correlation exists to prevent.
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        awaits("instance-a", "1735689600.000100")
        awaits("instance-b", "1735689600.000200")

        val resumed = starter.start(receiveTask(), reply(threadTs = null))

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
    }

    @Test
    fun `should treat the thread parent itself as belonging to its own thread`() {
        // Slack reports the parent of a thread with thread_ts equal to its own ts, and a case
        // started by that message remembers exactly that value.
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "1735689600.000100")

        val parent =
            SlackMessage(
                channel = CHANNEL,
                ts = "1735689600.000100",
                threadTs = "1735689600.000100",
                text = "Original question",
                userId = "U012AB3CD",
            )

        assertThat(starter.start(receiveTask(), parent)).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
    }

    @Test
    fun `should match timestamps that differ only in trailing precision`() {
        // Slack trims trailing zeroes on some payloads, so the value a case stored and the
        // value a later message reports can be the same instant spelled two ways.
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "1735689600.0001")

        val resumed = starter.start(receiveTask(), reply(threadTs = "1735689600.000100"))

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
    }

    @Test
    fun `should resume every case in the thread when one instance waits at the task twice`() {
        waiting("execution-a" to "instance-a", "execution-b" to "instance-a")
        awaits("instance-a", "1735689600.000100")

        val resumed = starter.start(receiveTask(), reply(threadTs = "1735689600.000100"))

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
        verify(runtimeService).signal(eq("execution-b"), any<Map<String, Any>>())
    }

    @Test
    fun `should hand the message on as process variables when it does resume`() {
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "1735689600.000100")

        val message = reply(threadTs = "1735689600.000100")
        starter.start(receiveTask(), message)

        verify(runtimeService).signal(eq("execution-a"), eq(message.toProcessVariables()))
    }

    @Test
    fun `should report nothing resumed when no execution is waiting`() {
        waiting()

        val resumed = starter.start(receiveTask(), reply(threadTs = "1735689600.000100"))

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
    }

    @Test
    fun `should report the threads waiting cases expect an answer in`() {
        // This is what keeps the poller from having to track every thread in the workspace:
        // it asks Slack about the open conversations only.
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        awaits("instance-a", "1735689600.000100")
        awaits("instance-b", "1735689600.000200")

        assertThat(starter.threadsAwaitingReply(receiveTask()))
            .containsOnlyKeys("1735689600.000100", "1735689600.000200")
    }

    @Test
    fun `should report each thread with the position of the case waiting in it`() {
        // The position comes from the case, not from how far the channel has been read: a
        // case that parks late would otherwise have its answer skipped, because the channel
        // has moved on past it in the meantime.
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "1735689600.000100")
        hasSeen("instance-a", "1735689650.000150")

        assertThat(starter.threadsAwaitingReply(receiveTask()))
            .containsExactly(entry("1735689600.000100", "1735689650.000150"))
    }

    @Test
    fun `should report no position for a case that has not recorded one`() {
        // Read the thread from its start then, and let the claim table drop what was handled.
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "1735689600.000100")

        assertThat(starter.threadsAwaitingReply(receiveTask()))
            .containsExactly(entry("1735689600.000100", null))
    }

    @Test
    fun `should report the earliest position when two cases wait in one thread`() {
        // The thread is read once. Reading it from the later position would cost the case
        // that is further behind its answer.
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        awaits("instance-a", "1735689600.000100")
        awaits("instance-b", "1735689600.000100")
        hasSeen("instance-a", "1735689900.000500")
        hasSeen("instance-b", "1735689650.000150")

        assertThat(starter.threadsAwaitingReply(receiveTask()))
            .containsExactly(entry("1735689600.000100", "1735689650.000150"))
    }

    @Test
    fun `should report no threads for a message start event`() {
        // A start event has nothing waiting at it by definition, and querying executions for
        // one would cost a call per poll to learn that.
        val startEvent =
            mock<PluginProcessLink>().also {
                whenever(it.activityType).thenReturn(ActivityTypeWithEventName.MESSAGE_START_EVENT_START)
            }

        assertThat(starter.threadsAwaitingReply(startEvent)).isEmpty()
        verify(runtimeService, never()).createExecutionQuery()
    }

    private fun waiting(vararg executions: Pair<String, String>) {
        // Built before the stubbing rather than inside it: stubbing one mock while another
        // stubbing call is still open is what Mockito calls unfinished stubbing.
        val parked =
            executions.map { (executionId, processInstanceId) ->
                mock<Execution>().also {
                    whenever(it.id).thenReturn(executionId)
                    whenever(it.processInstanceId).thenReturn(processInstanceId)
                }
            }
        whenever(executionQuery.list()).thenReturn(parked)
    }

    /** Records which thread the case behind [processInstanceId] expects an answer in. */
    private fun awaits(
        processInstanceId: String,
        threadTs: String,
    ) {
        whenever(runtimeService.getVariable(processInstanceId, SlackMessage.THREAD_TS_VARIABLE))
            .thenReturn(threadTs)
    }

    /** Records the last message the case behind [processInstanceId] has already seen. */
    private fun hasSeen(
        processInstanceId: String,
        messageTs: String,
    ) {
        whenever(runtimeService.getVariable(processInstanceId, SlackMessage.MESSAGE_TS_VARIABLE))
            .thenReturn(messageTs)
    }

    private fun receiveTask(): PluginProcessLink =
        mock<PluginProcessLink>().also {
            whenever(it.activityType).thenReturn(ActivityTypeWithEventName.RECEIVE_TASK_END)
            whenever(it.activityId).thenReturn("await-reply")
            whenever(it.processDefinitionId).thenReturn("slack-intake-process:1:abc")
        }

    private fun reply(threadTs: String?) =
        SlackMessage(
            channel = CHANNEL,
            ts = "1735689900.000500",
            threadTs = threadTs,
            text = "Yes, go ahead",
            userId = "U012AB3CD",
            userName = "jan",
        )

    private companion object {
        private const val CHANNEL = "C012AB3CD"
    }
}
