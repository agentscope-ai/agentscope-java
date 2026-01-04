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

import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.UpdateEvent;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.Msg;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@link TaskUpdateEvent}.
 */
public class TaskUpdateEventHandler implements ClientEventHandler<TaskUpdateEvent> {

    private static final Logger log = LoggerFactory.getLogger(TaskUpdateEventHandler.class);

    private final Map<Class<? extends UpdateEvent>, UpdateEventHandler<? extends UpdateEvent>>
            updateEventHandlers;

    public TaskUpdateEventHandler() {
        this.updateEventHandlers = new HashMap<>(2);
        updateEventHandlers.put(TaskStatusUpdateEvent.class, new TaskStatusUpdateEventHandler());
        updateEventHandlers.put(
                TaskArtifactUpdateEvent.class, new TaskArtifactUpdateEventHandler());
    }

    @Override
    public Class<TaskUpdateEvent> getHandleEventType() {
        return TaskUpdateEvent.class;
    }

    @Override
    public void handle(TaskUpdateEvent event, ClientEventContext context) {
        handleTaskUpdateEvent(event, context);
    }

    private void handleTaskUpdateEvent(TaskUpdateEvent event, ClientEventContext context) {
        context.setTask(event.getTask());
        handleUpdateEvent(event.getUpdateEvent(), context);
    }

    @SuppressWarnings("unchecked")
    private void handleUpdateEvent(UpdateEvent event, ClientEventContext context) {
        UpdateEventHandler<UpdateEvent> handler =
                (UpdateEventHandler<UpdateEvent>) updateEventHandlers.get(event.getClass());

        if (handler != null) {
            handleSafely(event, handler, context);
        }
    }

    private <T extends UpdateEvent> void handleSafely(
            T event, UpdateEventHandler<T> handler, ClientEventContext context) {
        handler.handle(event, context);
    }

    private interface UpdateEventHandler<T extends UpdateEvent> {

        void handle(T event, ClientEventContext context);
    }

    private static class TaskStatusUpdateEventHandler
            implements UpdateEventHandler<TaskStatusUpdateEvent> {

        @Override
        public void handle(TaskStatusUpdateEvent event, ClientEventContext context) {
            String currentRequestId = context.getCurrentRequestId();
            if (event.isFinal()) {
                Msg msg = MessageConvertUtil.convertFromArtifact(context.getTask().getArtifacts());
                context.getSink().success(msg);
                LoggerUtil.info(log, "[{}] A2aAgent complete call.", currentRequestId);
                LoggerUtil.debug(
                        log, "[{}] A2aAgent complete with artifact messages: ", currentRequestId);
                LoggerUtil.logTextMsgDetail(log, List.of(msg));
            } else {
                TaskStatus taskStatus = event.getStatus();
                LoggerUtil.debug(
                        log,
                        "[{}] A2aAgent task status updated to: {}.",
                        currentRequestId,
                        taskStatus.state());
                if (null == taskStatus.message()) {
                    return;
                }
                Msg msg = MessageConvertUtil.convertFromMessage(taskStatus.message());
                LoggerUtil.debug(
                        log, "[{}] A2aAgent task status updated with messages: ", currentRequestId);
                LoggerUtil.logTextMsgDetail(log, List.of(msg));
                ReasoningChunkEvent chunkEvent =
                        new ReasoningChunkEvent(context.getAgent(), "A2A", null, msg, msg);
                context.getHooks().forEach(hook -> hook.onEvent(chunkEvent).block());
            }
        }
    }

    private static class TaskArtifactUpdateEventHandler
            implements UpdateEventHandler<TaskArtifactUpdateEvent> {

        @Override
        public void handle(TaskArtifactUpdateEvent event, ClientEventContext context) {
            String currentRequestTaskId = context.getCurrentRequestId();
            if (null == event.getArtifact()) {
                return;
            }
            Msg msg = MessageConvertUtil.convertFromArtifact(event.getArtifact());
            LoggerUtil.debug(
                    log, "[{}] A2aAgent artifact append with messages: ", currentRequestTaskId);
            LoggerUtil.logTextMsgDetail(log, List.of(msg));
            ReasoningChunkEvent chunkEvent =
                    new ReasoningChunkEvent(context.getAgent(), "A2A", null, msg, msg);
            context.getHooks().forEach(hook -> hook.onEvent(chunkEvent).block());
        }
    }
}
