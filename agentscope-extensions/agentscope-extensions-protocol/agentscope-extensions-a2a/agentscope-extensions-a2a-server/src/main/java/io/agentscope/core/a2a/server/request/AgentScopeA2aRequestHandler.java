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

package io.agentscope.core.a2a.server.request;

import java.lang.reflect.Field;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.DefaultPushNotificationUrlValidator;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.DeleteTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.GetTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;

/**
 * The {@link RequestHandler} of an AgentScope A2A server, backed by the SDK's {@link
 * DefaultRequestHandler} and the components it needs.
 */
public class AgentScopeA2aRequestHandler implements RequestHandler {

    private final DefaultRequestHandler delegate;

    private AgentScopeA2aRequestHandler(DefaultRequestHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public Task onGetTask(TaskQueryParams params, ServerCallContext context) throws A2AError {
        return delegate.onGetTask(params, context);
    }

    @Override
    public ListTasksResult onListTasks(ListTasksParams params, ServerCallContext context)
            throws A2AError {
        return delegate.onListTasks(params, context);
    }

    @Override
    public Task onCancelTask(CancelTaskParams params, ServerCallContext context) throws A2AError {
        return delegate.onCancelTask(params, context);
    }

    @Override
    public EventKind onMessageSend(MessageSendParams params, ServerCallContext context)
            throws A2AError {
        return delegate.onMessageSend(params, context);
    }

    @Override
    public Flow.Publisher<StreamingEventKind> onMessageSendStream(
            MessageSendParams params, ServerCallContext context) throws A2AError {
        return delegate.onMessageSendStream(params, context);
    }

    @Override
    public TaskPushNotificationConfig onCreateTaskPushNotificationConfig(
            TaskPushNotificationConfig params, ServerCallContext context) throws A2AError {
        return delegate.onCreateTaskPushNotificationConfig(params, context);
    }

    @Override
    public TaskPushNotificationConfig onGetTaskPushNotificationConfig(
            GetTaskPushNotificationConfigParams params, ServerCallContext context) throws A2AError {
        return delegate.onGetTaskPushNotificationConfig(params, context);
    }

    @Override
    public Flow.Publisher<StreamingEventKind> onSubscribeToTask(
            TaskIdParams params, ServerCallContext context) throws A2AError {
        return delegate.onSubscribeToTask(params, context);
    }

    @Override
    public ListTaskPushNotificationConfigsResult onListTaskPushNotificationConfigs(
            ListTaskPushNotificationConfigsParams params, ServerCallContext context)
            throws A2AError {
        return delegate.onListTaskPushNotificationConfigs(params, context);
    }

    @Override
    public void onDeleteTaskPushNotificationConfig(
            DeleteTaskPushNotificationConfigParams params, ServerCallContext context)
            throws A2AError {
        delegate.onDeleteTaskPushNotificationConfig(params, context);
    }

    @Override
    public void authorizeTaskAccess(
            String requestedTaskId, ServerCallContext context, TaskOperation operation)
            throws A2AError {
        delegate.authorizeTaskAccess(requestedTaskId, context, operation);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private AgentExecutor agentExecutor;

        private TaskStore taskStore;

        private MainEventBus mainEventBus;

        private QueueManager queueManager;

        private PushNotificationConfigStore pushConfigStore;

        private PushNotificationSender pushSender;

        public Builder agentExecutor(AgentExecutor agentExecutor) {
            this.agentExecutor = agentExecutor;
            return this;
        }

        public Builder taskStore(TaskStore taskStore) {
            this.taskStore = taskStore;
            return this;
        }

        /**
         * Set the bus that carries events from the queues to the processor that persists and
         * dispatches them. A custom {@link #queueManager(QueueManager) queue manager} must be
         * created with this bus. By default, a new bus is created.
         *
         * @param mainEventBus the event bus
         * @return this builder
         */
        public Builder mainEventBus(MainEventBus mainEventBus) {
            this.mainEventBus = mainEventBus;
            return this;
        }

        /**
         * Set the queue manager. It must be created with the {@link #mainEventBus(MainEventBus)
         * event bus} of this handler.
         *
         * @param queueManager the queue manager
         * @return this builder
         */
        public Builder queueManager(QueueManager queueManager) {
            this.queueManager = queueManager;
            return this;
        }

        public Builder pushConfigStore(PushNotificationConfigStore pushConfigStore) {
            this.pushConfigStore = pushConfigStore;
            return this;
        }

        public Builder pushSender(PushNotificationSender pushSender) {
            this.pushSender = pushSender;
            return this;
        }

        public AgentScopeA2aRequestHandler build() {
            if (null == agentExecutor) {
                throw new IllegalArgumentException("AgentExecutor is required.");
            }
            if (null == taskStore) {
                taskStore = new InMemoryTaskStore();
            }
            if (null == mainEventBus) {
                mainEventBus = new MainEventBus();
            }
            if (null == queueManager) {
                TaskStateProvider stateProvider =
                        taskStore instanceof TaskStateProvider provider
                                ? provider
                                : new AgentScopeTaskStateProvider(taskStore);
                queueManager = new InMemoryQueueManager(stateProvider, mainEventBus);
            }
            if (null == pushConfigStore) {
                pushConfigStore = new InMemoryPushNotificationConfigStore();
            }
            if (null == pushSender) {
                pushSender =
                        new BasePushNotificationSender(
                                pushConfigStore, new DefaultPushNotificationUrlValidator());
            }
            MainEventBusProcessor mainEventBusProcessor =
                    new MainEventBusProcessor(mainEventBus, taskStore, pushSender, queueManager);
            DefaultRequestHandler delegate =
                    DefaultRequestHandler.builder()
                            .agentExecutor(agentExecutor)
                            .taskStore(taskStore)
                            .queueManager(queueManager)
                            .pushConfigStore(pushConfigStore)
                            .mainEventBusProcessor(mainEventBusProcessor)
                            // TODO support custom executor.
                            .executor(Executors.newCachedThreadPool())
                            .eventConsumerExecutor(Executors.newCachedThreadPool())
                            // AgentScope does not authenticate callers yet; with authorization
                            // required and no provider, every task operation is rejected.
                            .authorizationRequired(false)
                            .build();
            setTimeoutProperties(delegate);
            return new AgentScopeA2aRequestHandler(delegate);
        }

        /**
         * A2A Server Request Handler don't provider configurable way to set timeout. So temp use reflection to do.
         *
         * <p>
         * If no timeout property setting, the blocking A2A request will return innerError immediately.
         * </p>
         */
        private static void setTimeoutProperties(DefaultRequestHandler requestHandler) {
            // TODO support config timeout properties by user input properties.
            try {
                Field field =
                        DefaultRequestHandler.class.getDeclaredField(
                                "agentCompletionTimeoutSeconds");
                field.setAccessible(true);
                field.set(requestHandler, 60);
                field =
                        DefaultRequestHandler.class.getDeclaredField(
                                "consumptionCompletionTimeoutSeconds");
                field.setAccessible(true);
                field.set(requestHandler, 10);
            } catch (Exception ignored) {
            }
        }
    }

    private record AgentScopeTaskStateProvider(TaskStore taskStore) implements TaskStateProvider {

        @Override
        public boolean isTaskActive(String taskId) {
            Task task = taskStore.get(taskId);
            if (task == null) {
                return false;
            }
            // Task is active if not in final state
            return task.status() == null
                    || task.status().state() == null
                    || !task.status().state().isFinal();
        }

        @Override
        public boolean isTaskFinalized(String taskId) {
            Task task = taskStore.get(taskId);
            if (task == null) {
                return false;
            }
            // Task is finalized if in final state (ignores grace period)
            return task.status() != null
                    && task.status().state() != null
                    && task.status().state().isFinal();
        }
    }
}
