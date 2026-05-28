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
package io.agentscope.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PendingToolRecoveryHook}, focusing on the callArgs extraction logic that
 * separates user input from the memory snapshot in {@code event.getInputMessages()}.
 */
class PendingToolRecoveryHookTest {

    private final PendingToolRecoveryHook hook = new PendingToolRecoveryHook();

    @Test
    @DisplayName("Patches pending tool calls when memory has historical ToolResultBlocks")
    void patchesDespiteHistoricalToolResults() {
        Memory memory = new InMemoryMemory();

        // Historical tool call + result (completed in a previous turn)
        memory.addMessage(assistantToolUse("old_call", "old_tool"));
        memory.addMessage(toolResult("old_call", "old result"));
        memory.addMessage(assistantText("Done with old tool."));

        // Pending tool call (no result)
        memory.addMessage(assistantToolUse("pending_call", "new_tool"));

        ReActAgent agent = mockReActAgent(memory);

        // event.getInputMessages() = memory snapshot (4 msgs) + user callArgs (1 msg)
        List<Msg> fullInput =
                List.of(
                        memory.getMessages().get(0),
                        memory.getMessages().get(1),
                        memory.getMessages().get(2),
                        memory.getMessages().get(3),
                        userMsg("continue please"));

        PreCallEvent event = new PreCallEvent(agent, fullInput);
        hook.onEvent(event).block();

        // Verify the hook patched: memory should now have a ToolResultBlock for "pending_call"
        boolean patched =
                memory.getMessages().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .anyMatch(tr -> "pending_call".equals(tr.getId()));
        assertTrue(patched, "Hook should patch the pending tool call despite historical results");
    }

    @Test
    @DisplayName("Skips when callArgs is empty (resume scenario)")
    void skipsWhenCallArgsEmpty() {
        Memory memory = new InMemoryMemory();
        memory.addMessage(assistantToolUse("pending_call", "some_tool"));

        ReActAgent agent = mockReActAgent(memory);

        // inputMessages = memory snapshot only, no callArgs (resume scenario)
        List<Msg> fullInput = List.of(memory.getMessages().get(0));

        PreCallEvent event = new PreCallEvent(agent, fullInput);
        hook.onEvent(event).block();

        // Should NOT patch — resume flow handled by doCall
        boolean patched =
                memory.getMessages().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .anyMatch(tr -> "pending_call".equals(tr.getId()));
        assertFalse(patched, "Hook should skip when callArgs is empty (resume scenario)");
    }

    @Test
    @DisplayName("Skips when user provides ToolResultBlock in callArgs")
    void skipsWhenUserProvidesToolResult() {
        Memory memory = new InMemoryMemory();
        memory.addMessage(assistantToolUse("pending_call", "some_tool"));

        ReActAgent agent = mockReActAgent(memory);

        // User provides a ToolResultBlock in callArgs (HITL / clarify scenario)
        Msg userResult =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("pending_call")
                                        .output(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("user answer")
                                                                .build()))
                                        .build())
                        .build();

        List<Msg> fullInput = List.of(memory.getMessages().get(0), userResult);

        PreCallEvent event = new PreCallEvent(agent, fullInput);
        hook.onEvent(event).block();

        // Should NOT patch — user is providing results themselves
        assertEquals(
                1,
                memory.getMessages().size(),
                "Hook should not add anything when user provides ToolResultBlock");
    }

    // ==================== Helpers ====================

    private static ReActAgent mockReActAgent(Memory memory) {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getMemory()).thenReturn(memory);
        when(agent.getName()).thenReturn("test-agent");
        return agent;
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Msg assistantText(String text) {
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Msg assistantToolUse(String id, String name) {
        Map<String, Object> input = Map.of();
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id(id)
                                .name(name)
                                .input(input)
                                .content(JsonUtils.getJsonCodec().toJson(input))
                                .build())
                .build();
    }

    private static Msg toolResult(String id, String text) {
        return Msg.builder()
                .name("test-agent")
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id(id)
                                .output(List.of(TextBlock.builder().text(text).build()))
                                .build())
                .build();
    }
}
