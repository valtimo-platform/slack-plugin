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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.ritense.case.service.CaseDefinitionService
import com.ritense.document.domain.impl.request.NewDocumentRequest
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.processdocument.domain.ProcessDefinitionId
import com.ritense.processdocument.domain.impl.request.NewDocumentAndStartProcessRequest
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentService
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.valtimo.contract.annotation.SkipComponentScan
import com.ritense.valtimo.service.ProcessPropertyService
import com.ritense.valtimoplugins.slack.domain.SlackMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Execution
import org.operaton.bpm.model.bpmn.instance.CatchEvent
import org.operaton.bpm.model.bpmn.instance.MessageEventDefinition
import org.springframework.stereotype.Service

/**
 * Starts, or resumes, the process behind a `receive-message` process link.
 *
 * Which of the two happens is decided by the BPMN element the link is attached to, not by
 * configuration:
 * - a message start event starts something new — a case (document process) for a normal
 *   process definition, or a bare process instance for a system process;
 * - a receive task or intermediate catch event resumes an instance that is already waiting,
 *   which is how a reply in a Slack thread lands back in the case that opened it — and only
 *   in that case, see [signalWaitingExecutions].
 *
 * Split out of the poller so that "which BPMN construct does this link mean" stays testable
 * without a Slack workspace in the picture.
 */
@SkipComponentScan
@Service
class SlackMessageProcessStarter(
    private val runtimeService: RuntimeService,
    private val repositoryService: RepositoryService,
    private val processPropertyService: ProcessPropertyService,
    private val processDefinitionCaseDefinitionService: ProcessDefinitionCaseDefinitionService,
    private val processDocumentService: ProcessDocumentService,
    private val caseDefinitionService: CaseDefinitionService,
) {
    /**
     * Returns `true` when the link led to a started or resumed instance.
     *
     * A `false` means the link matched but had nothing to act on — typically a catch event
     * with no instance waiting at it, or a reply that belongs to no open case. That is a
     * normal state, not an error, so the caller counts it as skipped rather than failed.
     */
    fun start(
        processLink: PluginProcessLink,
        message: SlackMessage,
    ): Boolean {
        val variables = message.toProcessVariables()
        return when (processLink.activityType) {
            ActivityTypeWithEventName.MESSAGE_START_EVENT_START -> startProcessByMessage(processLink, variables)
            else -> signalWaitingExecutions(processLink, message, variables)
        }
    }

    /**
     * The threads instances parked at this link are waiting in, each with the timestamp that
     * instance has already seen.
     *
     * The poller needs this because Slack does not surface a thread reply in a channel's
     * history: the only way to see one is to ask for that specific thread. Rather than track
     * every thread the workspace ever opened, the question is turned around — the process
     * engine already knows which conversations are unfinished, so those are the threads worth
     * a call.
     *
     * The timestamp comes from the instance rather than from the channel's read position, and
     * that distinction is load-bearing. A case does not necessarily park at its receive task
     * in the same poll that started it — an asynchronous continuation can delay it — and in
     * the meantime the channel cursor moves on past any newer message. Reading the thread
     * from the channel's position would then skip the answer this case was waiting for, and
     * skipping an answer means a case that never continues. Asking from where *the case* got
     * to cannot miss it.
     *
     * A `null` value means the instance has no recorded position, so the thread is read from
     * its beginning; the claim table removes what was already handled.
     */
    fun threadsAwaitingReply(processLink: PluginProcessLink): Map<String, String?> {
        if (processLink.activityType == ActivityTypeWithEventName.MESSAGE_START_EVENT_START) {
            return emptyMap()
        }

        val awaited = mutableMapOf<String, String?>()
        waitingExecutions(processLink).forEach { execution ->
            val thread = variableOf(execution, SlackMessage.THREAD_TS_VARIABLE) ?: return@forEach
            val seen = variableOf(execution, SlackMessage.MESSAGE_TS_VARIABLE)
            awaited[thread] =
                if (awaited.containsKey(thread)) SlackMessage.earliestPosition(awaited[thread], seen) else seen
        }
        return awaited
    }

    private fun startProcessByMessage(
        processLink: PluginProcessLink,
        variables: Map<String, Any>,
    ): Boolean =
        if (processPropertyService.isSystemProcessById(processLink.processDefinitionId)) {
            startSystemProcessByMessage(processLink, variables)
        } else {
            startDocumentProcessByMessage(processLink, variables)
        }

    private fun startSystemProcessByMessage(
        processLink: PluginProcessLink,
        variables: Map<String, Any>,
    ): Boolean {
        val messageName = messageNameOf(processLink)
        logger.info {
            "Starting system process by message '$messageName' for process definition '${processLink.processDefinitionId}'"
        }
        runtimeService
            .createMessageCorrelation(messageName)
            .processDefinitionId(processLink.processDefinitionId)
            .setVariables(variables)
            .correlateStartMessage()
        return true
    }

    private fun startDocumentProcessByMessage(
        processLink: PluginProcessLink,
        variables: Map<String, Any>,
    ): Boolean {
        val processDefinitionCaseDefinition =
            try {
                processDefinitionCaseDefinitionService
                    .findByProcessDefinitionId(ProcessDefinitionId(processLink.processDefinitionId))
                    ?: return false
            } catch (e: Exception) {
                logger.warn(e) {
                    "No case definition linked to process definition '${processLink.processDefinitionId}'"
                }
                return false
            }

        // Only the deployed, active version of a case definition may be started. Without
        // this an old process link would keep creating cases against a superseded version.
        val activeCaseDefinition =
            caseDefinitionService.getActiveCaseDefinition(processDefinitionCaseDefinition.id.caseDefinitionId.key)
        if (activeCaseDefinition?.id != processDefinitionCaseDefinition.id.caseDefinitionId) {
            logger.debug {
                "Skipping process link for '${processLink.processDefinitionId}': it points at a case definition " +
                    "version that is no longer active"
            }
            return false
        }

        require(processDefinitionCaseDefinition.canInitializeDocument) {
            "Cannot start a case for process definition '${processLink.processDefinitionId}' because " +
                "canInitializeDocument is false on the linked case definition."
        }

        val processDefinitionKey =
            processDefinitionCaseDefinition.processDefinitionKey
                ?: error("No process definition key found for '${processLink.processDefinitionId}'")

        val request =
            NewDocumentAndStartProcessRequest(
                processDefinitionKey,
                NewDocumentRequest(
                    activeCaseDefinition.id.key,
                    activeCaseDefinition.id.key,
                    activeCaseDefinition.id.versionTag.toString(),
                    JsonNodeFactory.instance.objectNode(),
                ),
            ).withProcessVars(variables)

        logger.info {
            "Creating a case for case definition '${activeCaseDefinition.id.key}' " +
                "(${activeCaseDefinition.id.versionTag}) with process '$processDefinitionKey'"
        }
        val result = processDocumentService.newDocumentAndStartProcess(request)
        if (result.errors().isNotEmpty()) {
            error("Failed to create a case for the incoming Slack message: ${result.errors()}")
        }
        return true
    }

    /**
     * Resumes the instances this message is an answer to — and only those.
     *
     * The query finds every instance parked at the activity, across all cases, so it cannot
     * be the answer on its own: signalling all of them would file one citizen's reply into
     * every other case waiting at the same step. The thread is what narrows it down, so a
     * message belonging to no thread a case is waiting on is left alone rather than delivered
     * to everybody.
     */
    private fun signalWaitingExecutions(
        processLink: PluginProcessLink,
        message: SlackMessage,
        variables: Map<String, Any>,
    ): Boolean {
        val waiting = waitingExecutions(processLink)

        if (waiting.isEmpty()) {
            logger.debug {
                "No execution waiting at activity '${processLink.activityId}' of process definition " +
                    "'${processLink.processDefinitionId}'"
            }
            return false
        }

        val executions = waiting.filter { answers(it, message) }
        if (executions.isEmpty()) {
            logger.debug {
                "Message '${message.identity}' matched the filter on activity '${processLink.activityId}' but " +
                    "belongs to no thread any of the ${waiting.size} execution(s) waiting there is expecting; " +
                    "leaving them untouched"
            }
            return false
        }

        executions.forEach { execution ->
            logger.info {
                "Resuming execution '${execution.id}' of process instance '${execution.processInstanceId}' " +
                    "at activity '${processLink.activityId}'"
            }
            when (processLink.activityType) {
                ActivityTypeWithEventName.RECEIVE_TASK_END ->
                    runtimeService.signal(execution.id, variables)

                ActivityTypeWithEventName.INTERMEDIATE_CATCH_EVENT_END ->
                    runtimeService.messageEventReceived(messageNameOf(processLink), execution.id, variables)

                else ->
                    error("Unsupported activity type '${processLink.activityType}' for a receive-message process link")
            }
        }
        return true
    }

    private fun waitingExecutions(processLink: PluginProcessLink): List<Execution> =
        runtimeService
            .createExecutionQuery()
            .processDefinitionId(processLink.processDefinitionId)
            .activityId(processLink.activityId)
            .list()

    /**
     * Decides whether this message continues the conversation that instance is waiting on.
     *
     * The instance remembers the thread it is part of in [SlackMessage.THREAD_TS_VARIABLE] —
     * written either by the `post-message` action that opened the thread, or by the message
     * that started the case. Every later message in that thread reports the same value, so
     * matching the two routes an answer back to the one case that asked the question.
     */
    private fun answers(
        execution: Execution,
        message: SlackMessage,
    ): Boolean {
        val awaited = variableOf(execution, SlackMessage.THREAD_TS_VARIABLE) ?: return false
        return SlackMessage.compareTimestamps(awaited, message.conversationTs) == 0
    }

    private fun variableOf(
        execution: Execution,
        name: String,
    ): String? =
        runCatching {
            runtimeService.getVariable(execution.processInstanceId, name)
        }.getOrElse { e ->
            logger.warn(e) {
                "Could not read '$name' from process instance '${execution.processInstanceId}'"
            }
            null
        }?.toString()
            ?.takeIf { it.isNotBlank() }

    private fun messageNameOf(processLink: PluginProcessLink): String {
        val model = repositoryService.getBpmnModelInstance(processLink.processDefinitionId)
        val element =
            model.getModelElementById<CatchEvent>(processLink.activityId)
                ?: error(
                    "No catch event '${processLink.activityId}' in process definition '${processLink.processDefinitionId}'",
                )
        return element.eventDefinitions
            .filterIsInstance<MessageEventDefinition>()
            .firstOrNull()
            ?.message
            ?.name
            ?: error(
                "No message event definition on element '${processLink.activityId}' in process definition " +
                    "'${processLink.processDefinitionId}'",
            )
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
