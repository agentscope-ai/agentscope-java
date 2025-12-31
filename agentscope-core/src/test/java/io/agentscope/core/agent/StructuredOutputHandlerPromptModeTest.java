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

package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for PROMPT mode in StructuredOutputHandler. */
class StructuredOutputHandlerPromptModeTest {

    private StructuredOutputHandler handler;
    private Toolkit toolkit;
    private Memory memory;

    static class TestResponse {
        public String result;
    }

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        memory = mock(Memory.class);
        when(memory.getMessages()).thenReturn(new ArrayList<>());

        handler =
                new StructuredOutputHandler(
                        TestResponse.class,
                        null,
                        toolkit,
                        memory,
                        "TestAgent",
                        StructuredOutputReminder.PROMPT);
        handler.prepare();
    }

    @Test
    void testCreateOptionsWithForcedToolDoesNotModifyInPromptMode() {
        GenerateOptions baseOptions =
                GenerateOptions.builder().temperature(0.7).maxTokens(1000).build();

        GenerateOptions options = handler.createOptionsWithForcedTool(baseOptions);

        // Should return the same options without adding toolChoice
        assertEquals(baseOptions, options);
        assertEquals(0.7, options.getTemperature());
        assertEquals(1000, options.getMaxTokens());
        assertNull(options.getToolChoice());
    }

    @Test
    void testCreateOptionsWithForcedToolWithNull() {
        GenerateOptions options = handler.createOptionsWithForcedTool(null);

        // Should return null in PROMPT mode
        assertNull(options);
    }

    @Test
    void testShouldInjectReminderReturnsFalseInitially() {
        // Initially should not inject reminder
        assertFalse(handler.shouldInjectReminder());
    }

    @Test
    void testNeedsRetryReturnsTrueWhenNoToolCalls() {
        // Mock memory with messages but no tool calls
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);

        // Should need retry when no tool calls found
        assertTrue(handler.needsRetry());
    }

    @Test
    void testShouldInjectReminderAfterNeedsRetry() {
        // Mock memory with messages but no tool calls
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);

        // Call needsRetry which should set needsReminder flag
        assertTrue(handler.needsRetry());

        // Now shouldInjectReminder should return true
        assertTrue(handler.shouldInjectReminder());

        // After calling shouldInjectReminder, it should reset and return false
        assertFalse(handler.shouldInjectReminder());
    }

    @Test
    void testCreateReminderMessage() {
        Msg reminderMsg = handler.createReminderMessage();

        assertNotNull(reminderMsg);
        assertEquals("user", reminderMsg.getName());
        assertEquals(MsgRole.USER, reminderMsg.getRole());
        assertTrue(reminderMsg.getContent().toString().contains("generate_response"));
        assertTrue(reminderMsg.getContent().toString().contains("function"));

        // Check metadata
        assertNotNull(reminderMsg.getMetadata());
        assertTrue(
                (Boolean)
                        reminderMsg
                                .getMetadata()
                                .get(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE));
        assertTrue(
                (Boolean)
                        reminderMsg
                                .getMetadata()
                                .get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER));
    }

    @Test
    void testCleanupResetsNeedsReminderFlag() {
        // Mock memory with messages but no tool calls
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);

        // Trigger needsReminder flag
        assertTrue(handler.needsRetry());
        assertTrue(handler.shouldInjectReminder());

        // Cleanup should reset the flag
        handler.cleanup();

        // After cleanup, shouldInjectReminder should return false
        assertFalse(handler.shouldInjectReminder());
    }
}
