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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolEmitter;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests for SubAgentTool. */
@DisplayName("SubAgentTool Tests")
class SubAgentToolTest {

    @Test
    @DisplayName("Should keep forwarded subagent IDs unique")
    void forwardedSubagentIdRemainsUnique() throws Exception {
        ReActAgent first = ReActAgent.builder().agentId("shared-subagent").name("first").build();
        ReActAgent second = ReActAgent.builder().agentId("shared-subagent").name("second").build();
        SubAgentTool tool = new SubAgentTool(() -> first, SubAgentConfig.builder().build());
        Method forwardEvent =
                SubAgentTool.class.getDeclaredMethod(
                        "forwardEvent", Event.class, ToolEmitter.class, Agent.class, String.class);
        forwardEvent.setAccessible(true);
        ToolEmitter firstEmitter = mock(ToolEmitter.class);
        ToolEmitter secondEmitter = mock(ToolEmitter.class);

        forwardEvent.invoke(tool, null, firstEmitter, first, "session-1");
        forwardEvent.invoke(tool, null, secondEmitter, second, "session-2");

        ArgumentCaptor<ToolResultBlock> firstResult =
                ArgumentCaptor.forClass(ToolResultBlock.class);
        ArgumentCaptor<ToolResultBlock> secondResult =
                ArgumentCaptor.forClass(ToolResultBlock.class);
        verify(firstEmitter).emit(firstResult.capture());
        verify(secondEmitter).emit(secondResult.capture());

        assertEquals(first.getId(), firstResult.getValue().getMetadata().get("subagent_id"));
        assertEquals(second.getId(), secondResult.getValue().getMetadata().get("subagent_id"));
        assertNotEquals(
                firstResult.getValue().getMetadata().get("subagent_id"),
                secondResult.getValue().getMetadata().get("subagent_id"));
    }
}
