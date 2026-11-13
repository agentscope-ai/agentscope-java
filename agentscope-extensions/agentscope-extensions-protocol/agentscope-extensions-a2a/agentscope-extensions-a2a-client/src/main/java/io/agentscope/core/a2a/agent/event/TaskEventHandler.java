/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.agent.event;

import io.a2a.client.TaskEvent;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@link TaskEvent}.
 */
public class TaskEventHandler implements ClientEventHandler<TaskEvent> {

    private static final Logger log = LoggerFactory.getLogger(TaskEventHandler.class);

    @Override
    public Class<TaskEvent> getHandleEventType() {
        return TaskEvent.class;
    }

    @Override
    public void handle(TaskEvent event, ClientEventContext context) {
        Task task = event.getTask();
        context.setTask(task);
        LoggerUtil.info(
                log,
                "[{}] A2A Task {} with status {}",
                context.getCurrentRequestId(),
                task.getId(),
                task.getStatus());

        context.publishPreReasoning();

        boolean isFinal =
                task.getStatus() != null
                        && task.getStatus().state() != null
                        && task.getStatus().state().isFinal();
        if (!isFinal) {
            if (task.getStatus() != null
                    && (task.getStatus().state() == TaskState.INPUT_REQUIRED
                            || task.getStatus().state() == TaskState.AUTH_REQUIRED)) {
                completeWaiting(task.getId(), task.getStatus(), context);
                return;
            }
            LoggerUtil.debug(
                    log,
                    "[{}] TaskEventHandler: task state {} is not terminal, waiting for more"
                            + " events.",
                    context.getCurrentRequestId(),
                    task.getStatus() != null ? task.getStatus().state() : "null");
            return;
        }

        Msg msg;
        boolean hasArtifacts = task.getArtifacts() != null && !task.getArtifacts().isEmpty();
        if (TaskState.COMPLETED == task.getStatus().state() && hasArtifacts) {
            msg =
                    MessageConvertUtil.convertFromArtifact(
                            task.getArtifacts(), context.getAgent().getName());
        } else if (task.getStatus() != null && task.getStatus().message() != null) {
            msg =
                    MessageConvertUtil.convertFromMessage(
                            task.getStatus().message(), context.getAgent().getName());
        } else {
            msg =
                    MessageConvertUtil.convertFromArtifact(
                            task.getArtifacts(), context.getAgent().getName());
        }
        msg = context.publishPostReasoning(msg);
        if (!context.complete(msg)) {
            LoggerUtil.debug(
                    log,
                    "[{}] TaskEventHandler: duplicate terminal event ignored.",
                    context.getCurrentRequestId());
        }
    }

    /** Ends the call while leaving the remote task waiting for the caller. */
    static void completeWaiting(String taskId, TaskStatus status, ClientEventContext context) {
        String agentName = context.getAgent().getName();
        Msg question =
                status.message() != null
                        ? MessageConvertUtil.convertFromMessage(status.message(), agentName)
                        : Msg.builder()
                                .name(agentName)
                                .role(MsgRole.ASSISTANT)
                                .textContent(
                                        "A2A task "
                                                + taskId
                                                + " is waiting in state "
                                                + status.state()
                                                + ".")
                                .build();
        Map<String, Object> metadata = new HashMap<>();
        if (question.getMetadata() != null) {
            metadata.putAll(question.getMetadata());
        }
        metadata.put(MessageConstants.TASK_ID_METADATA_KEY, taskId);
        metadata.put(MessageConstants.TASK_STATE_METADATA_KEY, status.state().name());
        Msg msg = context.publishPostReasoning(question.withMetadata(metadata));
        if (!context.complete(msg)) {
            LoggerUtil.debug(
                    log,
                    "[{}] TaskEventHandler: duplicate terminal event ignored.",
                    context.getCurrentRequestId());
        }
    }
}
