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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Wrapper for Default {@link RequestHandler} implementation.
 */
public class AgentScopeA2aRequestHandler extends DefaultRequestHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeA2aRequestHandler.class);

    private AgentScopeA2aRequestHandler(
            AgentExecutor agentExecutor,
            TaskStore taskStore,
            QueueManager queueManager,
            PushNotificationConfigStore pushConfigStore,
            MainEventBusProcessor mainEventBusProcessor,
            Executor executor,
            Executor eventConsumerExecutor) {
        super(
                agentExecutor,
                taskStore,
                queueManager,
                pushConfigStore,
                mainEventBusProcessor,
                executor,
                eventConsumerExecutor);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private AgentExecutor agentExecutor;

        private TaskStore taskStore;

        private QueueManager queueManager;

        private PushNotificationConfigStore pushConfigStore;

        private PushNotificationSender pushSender;

        private MainEventBus mainEventBus;

        private MainEventBusProcessor mainEventBusProcessor;

        public Builder agentExecutor(AgentExecutor agentExecutor) {
            this.agentExecutor = agentExecutor;
            return this;
        }

        public Builder taskStore(TaskStore taskStore) {
            this.taskStore = taskStore;
            return this;
        }

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
            if (null == pushConfigStore) {
                pushConfigStore = new InMemoryPushNotificationConfigStore();
            }
            if (null == pushSender) {
                pushSender = new BasePushNotificationSender(pushConfigStore);
            }
            if (null == mainEventBus) {
                mainEventBus = new MainEventBus();
            }
            if (null == queueManager) {
                TaskStateProvider taskStateProvider = new AgentScopeTaskStateProvider(taskStore);
                queueManager = new InMemoryQueueManager(taskStateProvider, mainEventBus);
            }
            if (null == mainEventBusProcessor) {
                mainEventBusProcessor =
                        new MainEventBusProcessor(
                                mainEventBus, taskStore, pushSender, queueManager);
                startMainEventBusProcessor(mainEventBusProcessor);
            }
            Executor executor = Executors.newCachedThreadPool();
            Executor eventConsumerExecutor = Executors.newCachedThreadPool();
            AgentScopeA2aRequestHandler result =
                    new AgentScopeA2aRequestHandler(
                            agentExecutor,
                            taskStore,
                            queueManager,
                            pushConfigStore,
                            mainEventBusProcessor,
                            executor,
                            eventConsumerExecutor);
            setTimeoutProperties(result);
            return result;
        }

        private static void startMainEventBusProcessor(
                MainEventBusProcessor mainEventBusProcessor) {
            try {
                var method = MainEventBusProcessor.class.getDeclaredMethod("start");
                method.setAccessible(true);
                method.invoke(mainEventBusProcessor);
            } catch (Exception e) {
                log.error("Failed to start A2A main event bus processor.", e);
                throw new IllegalStateException("Failed to start A2A main event bus processor.", e);
            }
        }

        /**
         * A2A Server Request Handler don't provider configurable way to set timeout. So temp use reflection to do.
         *
         * <p>
         * This Spring/manual construction path does not run the SDK's MicroProfile Config
         * initialization. Without non-zero timeout values, blocking A2A requests return an
         * innerError immediately.
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
            } catch (Exception e) {
                log.error("Failed to configure A2A blocking request timeouts.", e);
                throw new IllegalStateException(
                        "Failed to configure A2A blocking request timeouts.", e);
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
