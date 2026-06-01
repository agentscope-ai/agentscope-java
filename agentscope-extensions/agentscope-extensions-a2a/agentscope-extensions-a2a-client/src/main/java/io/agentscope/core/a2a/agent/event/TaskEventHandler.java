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

import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.message.Msg;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.spec.Task;
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
                task.id(),
                task.status());
        boolean isFinal =
                task.status() != null
                        && task.status().state() != null
                        && task.status().state().isFinal();
        if (!isFinal) {
            LoggerUtil.debug(
                    log,
                    "[{}] TaskEventHandler: task state {} is not terminal, waiting for more"
                            + " events.",
                    context.getCurrentRequestId(),
                    task.status() != null ? task.status().state() : "null");
            return;
        }
        Msg msg;
        if (task.status() != null && task.status().message() != null) {
            msg =
                    MessageConvertUtil.convertFromMessage(
                            task.status().message(), context.getAgent().getName());
        } else {
            msg =
                    MessageConvertUtil.convertFromArtifact(
                            task.artifacts(), context.getAgent().getName());
        }
        log.info(
                "[{}] TaskEventHandler: calling sink.success(), thread={}",
                context.getCurrentRequestId(),
                Thread.currentThread().getName());
        context.getSink().success(msg);
    }
}
