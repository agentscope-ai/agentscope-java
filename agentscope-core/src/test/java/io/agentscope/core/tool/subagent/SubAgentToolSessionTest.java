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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SubAgentToolSessionTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void continuesChildSessionWithoutUsingParentSession(boolean forwardEvents) {
        List<MockModel> models = new ArrayList<>();
        SubAgentConfig config = SubAgentConfig.builder().forwardEvents(forwardEvents).build();
        SubAgentTool tool =
                new SubAgentTool(
                        () -> {
                            MockModel model = new MockModel("OK");
                            models.add(model);
                            return ReActAgent.builder()
                                    .name("child")
                                    .sysPrompt("You are helpful")
                                    .model(model)
                                    .toolkit(new Toolkit())
                                    .build();
                        },
                        config);

        RuntimeContext firstParent =
                RuntimeContext.builder().userId("merchant").sessionId("parent-one").build();
        AgentState firstParentState = mock(AgentState.class);
        firstParent.setAgentState(firstParentState);
        ToolResultBlock first =
                tool.callAsync(param("Remember this", null, firstParent))
                        .block(Duration.ofSeconds(10));
        String firstOutput = ((TextBlock) first.getOutput().get(0)).getText();
        String childSessionId =
                firstOutput.substring("session_id: ".length(), firstOutput.indexOf('\n'));
        AgentState saved =
                config.getStateStore()
                        .get(null, childSessionId, "agent_state", AgentState.class)
                        .orElseThrow();

        assertTrue(
                saved.getContext().stream()
                        .anyMatch(message -> "Remember this".equals(message.getTextContent())));
        assertSame(firstParentState, firstParent.getAgentState());

        RuntimeContext secondParent =
                RuntimeContext.builder().userId("merchant").sessionId("parent-two").build();
        AgentState secondParentState = mock(AgentState.class);
        secondParent.setAgentState(secondParentState);
        ToolResultBlock second =
                tool.callAsync(param("Continue", childSessionId, secondParent))
                        .block(Duration.ofSeconds(10));

        assertTrue(((TextBlock) second.getOutput().get(0)).getText().contains(childSessionId));
        assertEquals(3, models.size());
        assertTrue(
                models.get(2).getLastMessages().stream()
                        .anyMatch(message -> "Remember this".equals(message.getTextContent())));
        assertSame(secondParentState, secondParent.getAgentState());
    }

    private static ToolCallParam param(
            String message, String childSessionId, RuntimeContext parent) {
        Map<String, Object> input =
                childSessionId == null
                        ? Map.of("message", message)
                        : Map.of("message", message, "session_id", childSessionId);
        return ToolCallParam.builder()
                .input(input)
                .runtimeContext(parent)
                .emitter(mock(ToolEmitter.class))
                .build();
    }
}
