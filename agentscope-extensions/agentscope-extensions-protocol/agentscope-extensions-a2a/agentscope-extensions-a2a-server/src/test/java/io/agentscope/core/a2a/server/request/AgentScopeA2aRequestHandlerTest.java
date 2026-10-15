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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.a2a.server.ServerCallContext;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.auth.UnauthenticatedUser;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.events.QueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.StreamingEventKind;
import io.a2a.spec.Task;
import io.a2a.spec.TaskIdParams;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.executor.AgentExecuteProperties;
import io.agentscope.core.a2a.server.executor.AgentScopeAgentExecutor;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for AgentScopeA2aRequestHandler.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Builder validation</li>
 *   <li>AgentScopeTaskStateProvider behavior</li>
 *   <li>QueueManager initialization with different TaskStore types</li>
 * </ul>
 */
@DisplayName("AgentScopeA2aRequestHandler Tests")
class AgentScopeA2aRequestHandlerTest {

    private final List<AgentScopeA2aRequestHandler> handlers = new ArrayList<>();

    /** Registers a handler so that it is closed after the test, whatever the outcome. */
    private AgentScopeA2aRequestHandler track(AgentScopeA2aRequestHandler handler) {
        handlers.add(handler);
        return handler;
    }

    @AfterEach
    void closeHandlers() {
        handlers.forEach(AgentScopeA2aRequestHandler::close);
    }

    /**
     * Helper method to create a AgentScopeA2aRequestHandler and extract its TaskStateProvider
     * through reflection.
     *
     * @param taskStore The task store to use
     * @return The TaskStateProvider instance
     * @throws Exception if reflection fails
     */
    private TaskStateProvider getTaskStateProvider(TaskStore taskStore) throws Exception {
        // Create handler with custom TaskStore
        AgentExecutor mockExecutor = mock(AgentExecutor.class);

        AgentScopeA2aRequestHandler handler =
                track(
                        AgentScopeA2aRequestHandler.builder()
                                .agentExecutor(mockExecutor)
                                .taskStore(taskStore)
                                .build());

        // Get TaskStateProvider through reflection
        Field queueManagerField = DefaultRequestHandler.class.getDeclaredField("queueManager");
        queueManagerField.setAccessible(true);
        QueueManager queueManager = (QueueManager) queueManagerField.get(handler);

        Field taskStateProviderField =
                InMemoryQueueManager.class.getDeclaredField("taskStateProvider");
        taskStateProviderField.setAccessible(true);
        return (TaskStateProvider) taskStateProviderField.get(queueManager);
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should throw exception when agentExecutor is null")
        void testBuildWithNullAgentExecutor() {
            AgentScopeA2aRequestHandler.Builder builder = AgentScopeA2aRequestHandler.builder();

            IllegalArgumentException exception =
                    assertThrows(IllegalArgumentException.class, builder::build);

            assertEquals("AgentExecutor is required.", exception.getMessage());
        }

        @Test
        @DisplayName("Should build successfully with valid agentExecutor")
        void testBuildWithValidAgentExecutor() {
            AgentExecutor mockExecutor = mock(AgentExecutor.class);
            AgentScopeA2aRequestHandler.Builder builder =
                    AgentScopeA2aRequestHandler.builder().agentExecutor(mockExecutor);

            AgentScopeA2aRequestHandler handler = track(builder.build());

            assertNotNull(handler);
        }
    }

    @Nested
    @DisplayName("AgentScopeTaskStateProvider Tests")
    class AgentScopeTaskStateProviderTests {

        @Test
        @DisplayName("Should return false for isTaskActive when task is null")
        void testIsTaskActiveWithNullTask() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            when(mockTaskStore.get("nonexistent")).thenReturn(null);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskActive("nonexistent");

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true for isTaskActive when task status is null")
        void testIsTaskActiveWithNullTaskStatus() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            Task mockTask = mock(Task.class);
            when(mockTaskStore.get("task123")).thenReturn(mockTask);
            when(mockTask.getStatus()).thenReturn(null);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskActive("task123");

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true for isTaskActive when task state is null")
        void testIsTaskActiveWithNullTaskState() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            Task mockTask = mock(Task.class);
            TaskStatus mockStatus = mock(TaskStatus.class);
            when(mockTaskStore.get("task123")).thenReturn(mockTask);
            when(mockTask.getStatus()).thenReturn(mockStatus);
            when(mockStatus.state()).thenReturn(null);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskActive("task123");

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true for isTaskActive when task state is not final")
        void testIsTaskActiveWithNonFinalTaskState() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            Task mockTask = mock(Task.class);
            TaskStatus mockStatus = mock(TaskStatus.class);
            when(mockTaskStore.get("task123")).thenReturn(mockTask);
            when(mockTask.getStatus()).thenReturn(mockStatus);
            when(mockStatus.state()).thenReturn(TaskState.WORKING);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskActive("task123");

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false for isTaskActive when task state is final")
        void testIsTaskActiveWithFinalTaskState() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            Task mockTask = mock(Task.class);
            TaskStatus mockStatus = mock(TaskStatus.class);
            when(mockTaskStore.get("task123")).thenReturn(mockTask);
            when(mockTask.getStatus()).thenReturn(mockStatus);
            when(mockStatus.state()).thenReturn(TaskState.COMPLETED);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskActive("task123");

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false for isTaskFinalized when task is null")
        void testIsTaskFinalizedWithNullTask() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            when(mockTaskStore.get("nonexistent")).thenReturn(null);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskFinalized("nonexistent");

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false for isTaskFinalized when task status is null")
        void testIsTaskFinalizedWithNullTaskStatus() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            Task mockTask = mock(Task.class);
            when(mockTaskStore.get("task123")).thenReturn(mockTask);
            when(mockTask.getStatus()).thenReturn(null);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskFinalized("task123");

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false for isTaskFinalized when task state is null")
        void testIsTaskFinalizedWithNullTaskState() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            Task mockTask = mock(Task.class);
            TaskStatus mockStatus = mock(TaskStatus.class);
            when(mockTaskStore.get("task123")).thenReturn(mockTask);
            when(mockTask.getStatus()).thenReturn(mockStatus);
            when(mockStatus.state()).thenReturn(null);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskFinalized("task123");

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false for isTaskFinalized when task state is not final")
        void testIsTaskFinalizedWithNonFinalTaskState() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            Task mockTask = mock(Task.class);
            TaskStatus mockStatus = mock(TaskStatus.class);
            when(mockTaskStore.get("task123")).thenReturn(mockTask);
            when(mockTask.getStatus()).thenReturn(mockStatus);
            when(mockStatus.state()).thenReturn(TaskState.WORKING);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskFinalized("task123");

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true for isTaskFinalized when task state is final")
        void testIsTaskFinalizedWithFinalTaskState() throws Exception {
            TaskStore mockTaskStore = mock(TaskStore.class);
            Task mockTask = mock(Task.class);
            TaskStatus mockStatus = mock(TaskStatus.class);
            when(mockTaskStore.get("task123")).thenReturn(mockTask);
            when(mockTask.getStatus()).thenReturn(mockStatus);
            when(mockStatus.state()).thenReturn(TaskState.COMPLETED);

            TaskStateProvider taskStateProvider = getTaskStateProvider(mockTaskStore);

            // Verify it's AgentScopeTaskStateProvider
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());

            boolean result = taskStateProvider.isTaskFinalized("task123");

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("QueueManager Initialization Tests")
    class QueueManagerInitializationTests {

        @Test
        @DisplayName(
                "Should use InMemoryTaskStore directly when queueManager is null and taskStore is"
                        + " InMemoryTaskStore")
        void testQueueManagerWithInMemoryTaskStore() throws Exception {
            AgentExecutor mockExecutor = mock(AgentExecutor.class);

            AgentScopeA2aRequestHandler handler =
                    track(
                            AgentScopeA2aRequestHandler.builder()
                                    .agentExecutor(mockExecutor)
                                    .taskStore(new InMemoryTaskStore())
                                    .build());

            // Access the queueManager field from the parent class
            Field queueManagerField = DefaultRequestHandler.class.getDeclaredField("queueManager");
            queueManagerField.setAccessible(true);
            QueueManager queueManager = (QueueManager) queueManagerField.get(handler);

            assertNotNull(queueManager);
            assertInstanceOf(InMemoryQueueManager.class, queueManager);

            // Get the taskStateProvider field from InMemoryQueueManager
            Field taskStateProviderField =
                    InMemoryQueueManager.class.getDeclaredField("taskStateProvider");
            taskStateProviderField.setAccessible(true);
            TaskStateProvider taskStateProvider =
                    (TaskStateProvider) taskStateProviderField.get(queueManager);

            // Should be the InMemoryTaskStore instance directly
            assertInstanceOf(InMemoryTaskStore.class, taskStateProvider);
        }

        @Test
        @DisplayName(
                "Should use AgentScopeTaskStateProvider when queueManager is null and taskStore is"
                        + " custom")
        void testQueueManagerWithCustomTaskStore() throws Exception {
            AgentExecutor mockExecutor = mock(AgentExecutor.class);
            TaskStore mockTaskStore = mock(TaskStore.class);

            AgentScopeA2aRequestHandler handler =
                    track(
                            AgentScopeA2aRequestHandler.builder()
                                    .agentExecutor(mockExecutor)
                                    .taskStore(mockTaskStore)
                                    .build());

            // Access the queueManager field from the parent class
            Field queueManagerField = DefaultRequestHandler.class.getDeclaredField("queueManager");
            queueManagerField.setAccessible(true);
            QueueManager queueManager = (QueueManager) queueManagerField.get(handler);

            assertNotNull(queueManager);
            assertInstanceOf(InMemoryQueueManager.class, queueManager);

            // Get the taskStateProvider field from InMemoryQueueManager
            Field taskStateProviderField =
                    InMemoryQueueManager.class.getDeclaredField("taskStateProvider");
            taskStateProviderField.setAccessible(true);
            TaskStateProvider taskStateProvider =
                    (TaskStateProvider) taskStateProviderField.get(queueManager);

            // Should be the AgentScopeTaskStateProvider instance
            assertEquals(
                    "io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler$AgentScopeTaskStateProvider",
                    taskStateProvider.getClass().getName());
        }
    }

    @Nested
    @DisplayName("Timeout And Close Tests")
    class TimeoutAndCloseTests {

        @Test
        @DisplayName("Should wait 60s for the agent and 10s for consumption by default")
        void testDefaultTimeouts() throws Exception {
            AgentScopeA2aRequestHandler handler =
                    track(
                            AgentScopeA2aRequestHandler.builder()
                                    .agentExecutor(mock(AgentExecutor.class))
                                    .build());

            assertEquals(60, readField(handler, "agentCompletionTimeoutSeconds"));
            assertEquals(10, readField(handler, "consumptionCompletionTimeoutSeconds"));
        }

        @Test
        @DisplayName("Should apply configured timeouts")
        void testConfiguredTimeouts() throws Exception {
            AgentScopeA2aRequestHandler handler =
                    track(
                            AgentScopeA2aRequestHandler.builder()
                                    .agentExecutor(mock(AgentExecutor.class))
                                    .agentCompletionTimeoutSeconds(120)
                                    .consumptionCompletionTimeoutSeconds(30)
                                    .build());

            assertEquals(120, readField(handler, "agentCompletionTimeoutSeconds"));
            assertEquals(30, readField(handler, "consumptionCompletionTimeoutSeconds"));
        }

        @Test
        @DisplayName("Should shut down the execution thread pool on close")
        void testCloseShutsDownExecutor() throws Exception {
            AgentScopeA2aRequestHandler handler =
                    track(
                            AgentScopeA2aRequestHandler.builder()
                                    .agentExecutor(mock(AgentExecutor.class))
                                    .build());
            ExecutorService executor = (ExecutorService) readField(handler, "executor");
            assertFalse(executor.isShutdown());

            handler.close();

            assertTrue(executor.isShutdown());
        }

        private Object readField(DefaultRequestHandler handler, String name) throws Exception {
            Field field = DefaultRequestHandler.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(handler);
        }
    }

    @Nested
    @DisplayName("Cancel Tests")
    class CancelTests {

        @Test
        @DisplayName("A cancel rejected after completion should release its event queue")
        void testRejectedCancelReleasesItsQueue() throws Exception {
            // Keeps the completed task out of the store, so the cancel passes the SDK's
            // final-state check after the execution completed.
            CountDownLatch storeCompletion = new CountDownLatch(1);
            InMemoryTaskStore taskStore =
                    new InMemoryTaskStore() {
                        @Override
                        public void save(Task task) {
                            if (task.getStatus().state().isFinal()) {
                                awaitQuietly(storeCompletion);
                            }
                            super.save(task);
                        }
                    };
            InMemoryQueueManager queueManager = new InMemoryQueueManager(taskStore);
            CountDownLatch completed = new CountDownLatch(1);
            CountDownLatch returnFromExecute = new CountDownLatch(1);
            AgentRunner agentRunner = mock(AgentRunner.class);
            when(agentRunner.streamEvents(anyList(), any(AgentRequestOptions.class)))
                    .thenReturn(
                            Flux.<AgentEvent>just(
                                            new TextBlockDeltaEvent("reply-id", "block-id", "done"))
                                    // Runs after the completion is published, before execute()
                                    // returns.
                                    .doFinally(
                                            signal -> {
                                                completed.countDown();
                                                awaitQuietly(returnFromExecute);
                                            }));
            AgentScopeA2aRequestHandler handler =
                    track(
                            AgentScopeA2aRequestHandler.builder()
                                    .agentExecutor(
                                            new AgentScopeAgentExecutor(
                                                    agentRunner,
                                                    AgentExecuteProperties.builder().build()))
                                    .taskStore(taskStore)
                                    .queueManager(queueManager)
                                    .build());
            StreamRecorder stream = new StreamRecorder();
            try {
                // The SDK stores streamed events on the subscribing thread.
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                handler.onMessageSendStream(userMessage(), streamingContext())
                                        .subscribe(stream);
                            } catch (Exception e) {
                                stream.onError(e);
                            }
                        });
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                assertTrue(stream.firstEvent.await(5, TimeUnit.SECONDS));
                String taskId = ((Task) stream.events.get(0)).getId();
                int requestQueues = queueManager.getActiveChildQueueCount(taskId);

                assertThrows(
                        TaskNotCancelableError.class,
                        () ->
                                handler.onCancelTask(
                                        new TaskIdParams(taskId, Map.of()), streamingContext()));
                assertEquals(
                        requestQueues,
                        queueManager.getActiveChildQueueCount(taskId),
                        "the rejected cancel must close the queue tapped for it");

                storeCompletion.countDown();
                assertTrue(stream.done.await(5, TimeUnit.SECONDS));
                returnFromExecute.countDown();
                handler.waitForBackgroundTasks().get(5, TimeUnit.SECONDS);
                assertNull(
                        queueManager.get(taskId),
                        "the task's queue must be released once the request ended");
            } finally {
                storeCompletion.countDown();
                returnFromExecute.countDown();
            }
        }

        private MessageSendParams userMessage() {
            Message message =
                    new Message.Builder()
                            .role(Message.Role.USER)
                            .parts(new TextPart("hello"))
                            .messageId(UUID.randomUUID().toString())
                            .build();
            return new MessageSendParams(message, null, null);
        }

        private ServerCallContext streamingContext() {
            Map<String, Object> state = new HashMap<>();
            state.put(A2aServerConstants.ContextKeys.IS_STREAM_KEY, true);
            return new ServerCallContext(UnauthenticatedUser.INSTANCE, state, Set.of());
        }
    }

    private static final class StreamRecorder implements Flow.Subscriber<StreamingEventKind> {

        private final List<Object> events = new CopyOnWriteArrayList<>();

        private final CountDownLatch firstEvent = new CountDownLatch(1);

        private final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(StreamingEventKind item) {
            events.add(item);
            firstEvent.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            events.add(throwable);
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
