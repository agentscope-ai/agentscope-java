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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.events.QueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
                AgentScopeA2aRequestHandler.builder()
                        .agentExecutor(mockExecutor)
                        .taskStore(taskStore)
                        .build();

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

            AgentScopeA2aRequestHandler handler = builder.build();

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
                    AgentScopeA2aRequestHandler.builder()
                            .agentExecutor(mockExecutor)
                            .taskStore(new InMemoryTaskStore())
                            .build();

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
                    AgentScopeA2aRequestHandler.builder()
                            .agentExecutor(mockExecutor)
                            .taskStore(mockTaskStore)
                            .build();

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
}
