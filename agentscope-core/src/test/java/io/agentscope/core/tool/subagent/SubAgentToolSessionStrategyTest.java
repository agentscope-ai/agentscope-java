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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests the opt-in {@link SubAgentSessionStrategy#PER_PARENT_SESSION} child-session strategy. */
class SubAgentToolSessionStrategyTest {

    private static final String TOOL_NAME = "ask_keeper";

    @Test
    void defaultsToModelControlledSessionsAndExposesSessionIdParameter() {
        SubAgentConfig config = SubAgentConfig.builder().build();
        SubAgentTool tool = new SubAgentTool(childProvider(new ArrayList<>()), config);

        assertEquals(SubAgentSessionStrategy.MODEL_CONTROLLED, config.getSessionStrategy());
        assertTrue(properties(tool).containsKey("session_id"));
    }

    @Test
    void perParentSessionHidesSessionIdParameterFromTheModel() {
        SubAgentTool tool =
                new SubAgentTool(childProvider(new ArrayList<>()), perParentConfig(null));

        Map<String, Object> properties = properties(tool);
        assertFalse(properties.containsKey("session_id"));
        assertTrue(properties.containsKey("message"));
    }

    @Test
    void perParentSessionContinuesChildHistoryAcrossDelegationsInSameParentSession() {
        List<MockModel> models = new ArrayList<>();
        InMemoryAgentStateStore stateStore = spy(new InMemoryAgentStateStore());
        SubAgentTool tool = new SubAgentTool(childProvider(models), perParentConfig(stateStore));

        tool.callAsync(param("Remember this", null, parent("merchant", "parent-one")))
                .block(Duration.ofSeconds(10));
        tool.callAsync(param("Continue", null, parent("merchant", "parent-one")))
                .block(Duration.ofSeconds(10));

        // One sample agent from the constructor, then one per call.
        assertEquals(3, models.size());
        assertTrue(
                models.get(2).getLastMessages().stream()
                        .anyMatch(message -> "Remember this".equals(message.getTextContent())),
                "second delegation in the same parent session must see the first one's history");

        ArgumentCaptor<String> sessionIds = ArgumentCaptor.forClass(String.class);
        verify(stateStore, atLeastOnce())
                .save(
                        eq("merchant"),
                        sessionIds.capture(),
                        eq("agent_state"),
                        any(AgentState.class));
        for (String childSessionId : sessionIds.getAllValues()) {
            assertEquals("parent-one:" + TOOL_NAME, childSessionId);
            assertNotEquals(
                    "parent-one",
                    childSessionId,
                    "the child must not share the parent's own session key");
        }
    }

    @Test
    void perParentSessionIgnoresModelSuppliedSessionId() {
        List<MockModel> models = new ArrayList<>();
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        SubAgentTool tool = new SubAgentTool(childProvider(models), perParentConfig(stateStore));

        tool.callAsync(param("Remember this", null, parent("merchant", "parent-one")))
                .block(Duration.ofSeconds(10));
        ToolResultBlock result =
                tool.callAsync(
                                param(
                                        "Continue",
                                        "made-up-session",
                                        parent("merchant", "parent-one")))
                        .block(Duration.ofSeconds(10));

        assertTrue(text(result).contains("session_id: parent-one:" + TOOL_NAME));
        assertTrue(
                models.get(2).getLastMessages().stream()
                        .anyMatch(message -> "Remember this".equals(message.getTextContent())));
        assertTrue(
                stateStore
                        .get("merchant", "made-up-session", "agent_state", AgentState.class)
                        .isEmpty());
    }

    @Test
    void perParentSessionIsolatesDifferentParentSessionsAndUsers() {
        List<MockModel> models = new ArrayList<>();
        SubAgentTool tool =
                new SubAgentTool(
                        childProvider(models), perParentConfig(new InMemoryAgentStateStore()));

        tool.callAsync(param("Remember this", null, parent("merchant", "parent-one")))
                .block(Duration.ofSeconds(10));
        tool.callAsync(param("Other session", null, parent("merchant", "parent-two")))
                .block(Duration.ofSeconds(10));
        tool.callAsync(param("Other user", null, parent("attacker", "parent-one")))
                .block(Duration.ofSeconds(10));

        assertTrue(
                models.get(2).getLastMessages().stream()
                        .noneMatch(message -> "Remember this".equals(message.getTextContent())));
        assertTrue(
                models.get(3).getLastMessages().stream()
                        .noneMatch(message -> "Remember this".equals(message.getTextContent())));
    }

    @Test
    void perParentSessionFallsBackToANewSessionWhenTheCallerHasNoSession() {
        List<MockModel> models = new ArrayList<>();
        SubAgentTool tool =
                new SubAgentTool(
                        childProvider(models), perParentConfig(new InMemoryAgentStateStore()));

        ToolResultBlock result =
                tool.callAsync(param("hello", null, RuntimeContext.builder().userId("u").build()))
                        .block(Duration.ofSeconds(10));

        String output = text(result);
        assertTrue(output.contains("OK"), output);
        assertTrue(output.startsWith("session_id: "), output);
        assertFalse(output.startsWith("session_id: null"), output);
    }

    private static SubAgentConfig perParentConfig(InMemoryAgentStateStore stateStore) {
        SubAgentConfig.Builder builder =
                SubAgentConfig.builder()
                        .toolName(TOOL_NAME)
                        .forwardEvents(false)
                        .sessionStrategy(SubAgentSessionStrategy.PER_PARENT_SESSION);
        if (stateStore != null) {
            builder.stateStore(stateStore);
        }
        return builder.build();
    }

    private static SubAgentProvider<ReActAgent> childProvider(List<MockModel> models) {
        return () -> {
            MockModel model = new MockModel("OK");
            models.add(model);
            return ReActAgent.builder()
                    .name("keeper")
                    .sysPrompt("You are helpful")
                    .model(model)
                    .toolkit(new Toolkit())
                    .build();
        };
    }

    private static RuntimeContext parent(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(SubAgentTool tool) {
        return (Map<String, Object>) tool.getParameters().get("properties");
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
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
