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
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
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
    }
}
