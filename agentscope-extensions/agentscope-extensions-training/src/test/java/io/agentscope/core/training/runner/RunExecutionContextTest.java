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

package io.agentscope.core.training.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.training.util.TrainingTestConstants;
import io.agentscope.core.training.util.TrainingTestUtils;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunExecutionContext Unit Tests")
class RunExecutionContextTest {

    @Test
    @DisplayName("Should create context with valid taskId and runId")
    void shouldCreateContextWithValidIds() {
        // Act
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // Assert
        assertNotNull(context);
        assertEquals(TrainingTestConstants.TEST_TASK_ID, context.getTaskId());
        assertEquals(TrainingTestConstants.TEST_RUN_ID, context.getRunId());
        assertTrue(context.getStartTime() > 0);
    }

    @Test
    @DisplayName("Should throw exception when taskId is null")
    void shouldThrowExceptionWhenTaskIdIsNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RunExecutionContext.create(null, TrainingTestConstants.TEST_RUN_ID));
    }

    @Test
    @DisplayName("Should throw exception when taskId is empty")
    void shouldThrowExceptionWhenTaskIdIsEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RunExecutionContext.create("", TrainingTestConstants.TEST_RUN_ID));
    }

    @Test
    @DisplayName("Should throw exception when runId is null")
    void shouldThrowExceptionWhenRunIdIsNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, null));
    }

    @Test
    @DisplayName("Should throw exception when runId is empty")
    void shouldThrowExceptionWhenRunIdIsEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, ""));
    }

    @Test
    @DisplayName("Should add msgId successfully")
    void shouldAddMsgIdSuccessfully() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // Act
        context.addMsgId(TrainingTestConstants.TEST_MSG_ID_1);
        context.addMsgId(TrainingTestConstants.TEST_MSG_ID_2);

        // Assert
        assertEquals(2, context.getMsgIdCount());
        assertTrue(context.getMsgIds().contains(TrainingTestConstants.TEST_MSG_ID_1));
        assertTrue(context.getMsgIds().contains(TrainingTestConstants.TEST_MSG_ID_2));
    }

    @Test
    @DisplayName("Should ignore null msgId")
    void shouldIgnoreNullMsgId() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // Act
        context.addMsgId(null);
        context.addMsgId("");

        // Assert
        assertEquals(0, context.getMsgIdCount());
        assertFalse(context.hasMsgIds());
    }

    @Test
    @DisplayName("Should return copy of msgIds list")
    void shouldReturnCopyOfMsgIdsList() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        context.addMsgId(TrainingTestConstants.TEST_MSG_ID_1);

        // Act
        List<String> msgIds = context.getMsgIds();
        msgIds.add("should-not-affect-original");

        // Assert
        assertEquals(1, context.getMsgIdCount());
    }

    @Test
    @DisplayName("Should add msgId thread-safely")
    void shouldAddMsgIdThreadSafely() throws InterruptedException {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        int threadCount = 10;
        int messagesPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Act
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(
                    () -> {
                        for (int j = 0; j < messagesPerThread; j++) {
                            context.addMsgId("msg-" + threadId + "-" + j);
                        }
                        latch.countDown();
                    });
        }
        latch.await();
        executor.shutdown();

        // Assert
        assertEquals(threadCount * messagesPerThread, context.getMsgIdCount());
    }

    @Test
    @DisplayName("Should set and get messages")
    void shouldSetAndGetMessages() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        List<Msg> messages = TrainingTestUtils.createTestMessages();

        // Act
        context.setMessages(messages);

        // Assert
        assertEquals(2, context.getMessageCount());
        assertTrue(context.hasMessages());
    }

    @Test
    @DisplayName("Should add single message")
    void shouldAddSingleMessage() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        Msg msg = TrainingTestUtils.createTestMessage("user", MsgRole.USER, "Hello");

        // Act
        context.addMsg(msg);

        // Assert
        assertEquals(1, context.getMessageCount());
        assertTrue(context.hasMessages());
    }

    @Test
    @DisplayName("Should ignore null message")
    void shouldIgnoreNullMessage() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // Act
        context.addMsg(null);

        // Assert
        assertEquals(0, context.getMessageCount());
        assertFalse(context.hasMessages());
    }

    @Test
    @DisplayName("Should return copy of messages list")
    void shouldReturnCopyOfMessagesList() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        context.setMessages(TrainingTestUtils.createTestMessages());

        // Act
        List<Msg> messages = context.getMessages();
        messages.clear();

        // Assert
        assertEquals(2, context.getMessageCount());
    }

    @Test
    @DisplayName("Should clear messages when setMessages with null")
    void shouldClearMessagesWhenSetMessagesWithNull() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        context.setMessages(TrainingTestUtils.createTestMessages());

        // Act
        context.setMessages(null);

        // Assert
        assertEquals(0, context.getMessageCount());
        assertFalse(context.hasMessages());
    }

    @Test
    @DisplayName("Should calculate duration correctly")
    void shouldCalculateDurationCorrectly() throws InterruptedException {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // Act
        Thread.sleep(100);
        long duration = context.getDuration();

        // Assert
        assertTrue(duration >= 100, "Duration should be at least 100ms");
    }

    @Test
    @DisplayName("Should check hasMsgIds correctly")
    void shouldCheckHasMsgIdsCorrectly() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // Assert - initially false
        assertFalse(context.hasMsgIds());

        // Act
        context.addMsgId(TrainingTestConstants.TEST_MSG_ID_1);

        // Assert - now true
        assertTrue(context.hasMsgIds());
    }

    @Test
    @DisplayName("Should implement equals based on taskId and runId")
    void shouldImplementEqualsBasedOnTaskIdAndRunId() {
        // Arrange
        RunExecutionContext context1 =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        RunExecutionContext context2 =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        RunExecutionContext context3 = RunExecutionContext.create("different-task", "1");

        // Assert
        assertEquals(context1, context2);
        assertNotEquals(context1, context3);
    }

    @Test
    @DisplayName("Should implement hashCode based on taskId and runId")
    void shouldImplementHashCodeBasedOnTaskIdAndRunId() {
        // Arrange
        RunExecutionContext context1 =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        RunExecutionContext context2 =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // Assert
        assertEquals(context1.hashCode(), context2.hashCode());
    }

    @Test
    @DisplayName("Should produce readable toString output")
    void shouldProduceReadableToStringOutput() {
        // Arrange
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        context.addMsgId(TrainingTestConstants.TEST_MSG_ID_1);

        // Act
        String str = context.toString();

        // Assert
        assertTrue(str.contains(TrainingTestConstants.TEST_TASK_ID));
        assertTrue(str.contains(TrainingTestConstants.TEST_RUN_ID));
        assertTrue(str.contains("msgIds=1"));
    }
}
