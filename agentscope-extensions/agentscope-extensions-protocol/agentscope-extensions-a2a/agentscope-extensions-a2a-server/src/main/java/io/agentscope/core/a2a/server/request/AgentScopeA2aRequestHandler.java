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

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.events.QueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.server.tasks.BasePushNotificationSender;
import io.a2a.server.tasks.InMemoryPushNotificationConfigStore;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.server.tasks.PushNotificationConfigStore;
import io.a2a.server.tasks.PushNotificationSender;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Task;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Wrapper for Default {@link RequestHandler} implementation.
 */
public class AgentScopeA2aRequestHandler extends DefaultRequestHandler
        implements RequestHandler, AutoCloseable {

    private final ExecutorService executor;

    private AgentScopeA2aRequestHandler(
            AgentExecutor agentExecutor,
            TaskStore taskStore,
            QueueManager queueManager,
            PushNotificationConfigStore pushConfigStore,
            PushNotificationSender pushSender,
            ExecutorService executor) {
        super(agentExecutor, taskStore, queueManager, pushConfigStore, pushSender, executor);
        this.executor = executor;
    }

    /**
     * Shuts down the thread pool that runs agent executions with {@link
     * ExecutorService#shutdownNow()}, which requests interruption of executions still running and
     * does not wait for them to finish.
     */
    @Override
    public void close() {
        executor.shutdownNow();
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

        private int agentCompletionTimeoutSeconds = 60;

        private int consumptionCompletionTimeoutSeconds = 10;

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

        /**
         * Set how long a blocking request waits for the agent to finish, in seconds. Default 60.
         *
         * @param agentCompletionTimeoutSeconds timeout in seconds
         * @return this builder
         */
        public Builder agentCompletionTimeoutSeconds(int agentCompletionTimeoutSeconds) {
            this.agentCompletionTimeoutSeconds = agentCompletionTimeoutSeconds;
            return this;
        }

        /**
         * Set how long a blocking request waits for the produced events to be consumed after the
         * agent finished, in seconds. Default 10.
         *
         * @param consumptionCompletionTimeoutSeconds timeout in seconds
         * @return this builder
         */
        public Builder consumptionCompletionTimeoutSeconds(
                int consumptionCompletionTimeoutSeconds) {
            this.consumptionCompletionTimeoutSeconds = consumptionCompletionTimeoutSeconds;
            return this;
        }

        public AgentScopeA2aRequestHandler build() {
            if (null == agentExecutor) {
                throw new IllegalArgumentException("AgentExecutor is required.");
            }
            if (null == taskStore) {
                taskStore = new InMemoryTaskStore();
            }
            if (null == queueManager) {
                if (taskStore instanceof InMemoryTaskStore inMemoryTaskStore) {
                    queueManager = new InMemoryQueueManager(inMemoryTaskStore);
                } else {
                    queueManager =
                            new InMemoryQueueManager(new AgentScopeTaskStateProvider(taskStore));
                }
            }
            if (null == pushConfigStore) {
                pushConfigStore = new InMemoryPushNotificationConfigStore();
            }
            if (null == pushSender) {
                pushSender = new BasePushNotificationSender(pushConfigStore);
            }
            AgentScopeA2aRequestHandler result =
                    new AgentScopeA2aRequestHandler(
                            agentExecutor,
                            taskStore,
                            queueManager,
                            pushConfigStore,
                            pushSender,
                            // TODO support custom executor.
                            Executors.newCachedThreadPool());
            setTimeoutProperties(result);
            return result;
        }

        /**
         * A2A Server Request Handler don't provider configurable way to set timeout. So temp use reflection to do.
         *
         * <p>
         * If no timeout property setting, the blocking A2A request will return innerError immediately.
         * </p>
         */
        private void setTimeoutProperties(DefaultRequestHandler requestHandler) {
            try {
                Field field =
                        DefaultRequestHandler.class.getDeclaredField(
                                "agentCompletionTimeoutSeconds");
                field.setAccessible(true);
                field.set(requestHandler, agentCompletionTimeoutSeconds);
                field =
                        DefaultRequestHandler.class.getDeclaredField(
                                "consumptionCompletionTimeoutSeconds");
                field.setAccessible(true);
                field.set(requestHandler, consumptionCompletionTimeoutSeconds);
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
            return task.getStatus() == null
                    || task.getStatus().state() == null
                    || !task.getStatus().state().isFinal();
        }

        @Override
        public boolean isTaskFinalized(String taskId) {
            Task task = taskStore.get(taskId);
            if (task == null) {
                return false;
            }
            // Task is finalized if in final state (ignores grace period)
            return task.getStatus() != null
                    && task.getStatus().state() != null
                    && task.getStatus().state().isFinal();
        }
    }
}
