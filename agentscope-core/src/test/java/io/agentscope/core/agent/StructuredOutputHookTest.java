/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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

import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ToolChoice;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StructuredOutputHook}.
 *
 * <p>Tests the behavior of the hook in different reminder modes, particularly focusing on when
 * tool_choice is forced in TOOL_CHOICE mode.
 */
class StructuredOutputHookTest {

    private Memory memory;
    private Agent mockAgent;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        mockAgent = mock(Agent.class);
    }

    @Test
    void testToolChoiceMode_HandlesEmptyInputMessages() {
        // Given: TOOL_CHOICE mode with an empty message list
        StructuredOutputHook hook =
                new StructuredOutputHook(StructuredOutputReminder.TOOL_CHOICE, null, memory);

        List<Msg> inputMessages = new ArrayList<>();

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        // When: handle the pre-reasoning event with empty list
        hook.onEvent(event).block();

        // Then: should not throw and tool_choice should not be set
        assertNull(
                event.getEffectiveGenerateOptions(),
                "Should handle empty message list gracefully without throwing");
    }

    @Test
    void testToolChoiceMode_NotForcedOnInitialRequest() {
        // Given: TOOL_CHOICE mode with a regular user message (not a reminder)
        StructuredOutputHook hook =
                new StructuredOutputHook(StructuredOutputReminder.TOOL_CHOICE, null, memory);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Calculate 1+1").build())
                        .build();

        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(userMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        // When: handle the pre-reasoning event
        hook.onEvent(event).block();

        // Then: tool_choice should NOT be forced (effective options should be null)
        assertNull(
                event.getEffectiveGenerateOptions(),
                "tool_choice should not be forced on initial request");
    }

    @Test
    void testToolChoiceMode_ForcedOnReminderMessage() {
        // Given: TOOL_CHOICE mode with a TOOL_CHOICE reminder message as the last message
        StructuredOutputHook hook =
                new StructuredOutputHook(StructuredOutputReminder.TOOL_CHOICE, null, memory);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Calculate 1+1").build())
                        .build();

        Msg reminderMsg = createToolChoiceReminderMessage();

        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(userMsg);
        inputMessages.add(reminderMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        // When: handle the pre-reasoning event
        hook.onEvent(event).block();

        // Then: tool_choice should be forced to generate_response
        GenerateOptions effectiveOptions = event.getEffectiveGenerateOptions();
        assertNotNull(effectiveOptions, "Should have generate options set");
        assertNotNull(effectiveOptions.getToolChoice(), "Should have tool_choice set");
        assertTrue(
                effectiveOptions.getToolChoice() instanceof ToolChoice.Specific,
                "tool_choice should be Specific");
        assertEquals(
                StructuredOutputHook.TOOL_NAME,
                ((ToolChoice.Specific) effectiveOptions.getToolChoice()).toolName(),
                "tool_choice should be generate_response");
    }

    @Test
    void testToolChoiceMode_NotForcedOnPromptReminderMessage() {
        // Given: TOOL_CHOICE mode with a PROMPT reminder message (wrong type)
        StructuredOutputHook hook =
                new StructuredOutputHook(StructuredOutputReminder.TOOL_CHOICE, null, memory);

        Msg promptReminderMsg = createPromptReminderMessage();

        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(promptReminderMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        // When: handle the pre-reasoning event
        hook.onEvent(event).block();

        // Then: tool_choice should NOT be forced (reminder type doesn't match)
        assertNull(
                event.getEffectiveGenerateOptions(),
                "tool_choice should not be forced for PROMPT reminder in TOOL_CHOICE mode");
    }

    @Test
    void testPromptMode_NeverForcesToolChoice() {
        // Given: PROMPT mode with any message
        StructuredOutputHook hook =
                new StructuredOutputHook(StructuredOutputReminder.PROMPT, null, memory);

        Msg reminderMsg = createPromptReminderMessage();

        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(reminderMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        // When: handle the pre-reasoning event
        hook.onEvent(event).block();

        // Then: tool_choice should NOT be forced in PROMPT mode
        assertNull(
                event.getEffectiveGenerateOptions(),
                "tool_choice should never be forced in PROMPT mode");
    }

    @Test
    void testPostReasoning_CreatesCorrectReminderMetadata_ToolChoiceMode() {
        // Given: TOOL_CHOICE mode, model returns text without tool call
        StructuredOutputHook hook =
                new StructuredOutputHook(StructuredOutputReminder.TOOL_CHOICE, null, memory);

        Msg reasoningMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("I'll help you calculate").build())
                        .build();

        PostReasoningEvent event =
                new PostReasoningEvent(mockAgent, "test-model", null, reasoningMsg);

        // When: handle the post-reasoning event (should trigger retry with reminder)
        hook.onEvent(event).block();

        // Then: should goto reasoning with correct reminder message
        assertTrue(event.isGotoReasoningRequested(), "Should trigger retry");
        assertNotNull(event.getGotoReasoningMsgs(), "Should have additional messages");
        assertEquals(1, event.getGotoReasoningMsgs().size(), "Should have one reminder message");

        Msg createdReminder = event.getGotoReasoningMsgs().get(0);
        assertNotNull(createdReminder.getMetadata(), "Reminder should have metadata");
        assertTrue(
                (Boolean)
                        createdReminder
                                .getMetadata()
                                .get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER),
                "Should be marked as structured output reminder");
        assertEquals(
                StructuredOutputReminder.TOOL_CHOICE.toString(),
                createdReminder
                        .getMetadata()
                        .get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER_TYPE),
                "Should have TOOL_CHOICE reminder type");
    }

    @Test
    void testPostReasoning_CreatesCorrectReminderMetadata_PromptMode() {
        // Given: PROMPT mode, model returns text without tool call
        StructuredOutputHook hook =
                new StructuredOutputHook(StructuredOutputReminder.PROMPT, null, memory);

        Msg reasoningMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("I'll help you calculate").build())
                        .build();

        PostReasoningEvent event =
                new PostReasoningEvent(mockAgent, "test-model", null, reasoningMsg);

        // When: handle the post-reasoning event
        hook.onEvent(event).block();

        // Then: should have PROMPT reminder type
        assertTrue(event.isGotoReasoningRequested(), "Should trigger retry");
        Msg createdReminder = event.getGotoReasoningMsgs().get(0);
        assertEquals(
                StructuredOutputReminder.PROMPT.toString(),
                createdReminder
                        .getMetadata()
                        .get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER_TYPE),
                "Should have PROMPT reminder type");
    }

    @Test
    void testToolChoiceMode_MergesWithBaseOptions() {
        // Given: TOOL_CHOICE mode with base options
        GenerateOptions baseOptions =
                GenerateOptions.builder().temperature(0.7).maxTokens(1000).build();

        StructuredOutputHook hook =
                new StructuredOutputHook(StructuredOutputReminder.TOOL_CHOICE, baseOptions, memory);

        Msg reminderMsg = createToolChoiceReminderMessage();

        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(reminderMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        // When: handle the pre-reasoning event
        hook.onEvent(event).block();

        // Then: options should be merged with base options
        GenerateOptions effectiveOptions = event.getEffectiveGenerateOptions();
        assertNotNull(effectiveOptions, "Should have generate options set");
        assertNotNull(effectiveOptions.getToolChoice(), "Should have tool_choice set");
        // Base options should be preserved through merge
        assertEquals(
                0.7, effectiveOptions.getTemperature(), 0.001, "Temperature should be preserved");
        assertEquals(1000, effectiveOptions.getMaxTokens(), "MaxTokens should be preserved");
    }

    @Test
    void testReminderMessage_HasCorrectContent() {
        // Given: Any mode, model returns text without tool call
        StructuredOutputHook hook =
                new StructuredOutputHook(StructuredOutputReminder.TOOL_CHOICE, null, memory);

        Msg reasoningMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("I'll help you").build())
                        .build();

        PostReasoningEvent event =
                new PostReasoningEvent(mockAgent, "test-model", null, reasoningMsg);

        // When
        hook.onEvent(event).block();

        // Then: reminder message should have correct structure
        Msg createdReminder = event.getGotoReasoningMsgs().get(0);
        assertEquals(MsgRole.USER, createdReminder.getRole(), "Should be USER role");
        assertEquals("system", createdReminder.getName(), "Should have 'system' name");

        List<TextBlock> textBlocks = createdReminder.getContentBlocks(TextBlock.class);
        assertFalse(textBlocks.isEmpty(), "Should have text content");
        assertTrue(
                textBlocks.get(0).getText().contains("generate_response"),
                "Should mention generate_response tool");
    }

    /**
     * Creates a reminder message with TOOL_CHOICE type metadata.
     */
    private Msg createToolChoiceReminderMessage() {
        return Msg.builder()
                .name("system")
                .role(MsgRole.USER)
                .content(
                        TextBlock.builder()
                                .text("Please call the 'generate_response' function.")
                                .build())
                .metadata(
                        Map.of(
                                MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER,
                                true,
                                MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER_TYPE,
                                StructuredOutputReminder.TOOL_CHOICE.toString()))
                .build();
    }

    /**
     * Creates a reminder message with PROMPT type metadata.
     */
    private Msg createPromptReminderMessage() {
        return Msg.builder()
                .name("system")
                .role(MsgRole.USER)
                .content(
                        TextBlock.builder()
                                .text("Please call the 'generate_response' function.")
                                .build())
                .metadata(
                        Map.of(
                                MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER,
                                true,
                                MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER_TYPE,
                                StructuredOutputReminder.PROMPT.toString()))
                .build();
    }
}
