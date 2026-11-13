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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentExecutionHost;
import io.agentscope.core.agent.AgentExecutionPosition;
import io.agentscope.core.agent.ExecutionIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.journal.InvocationRecorder;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SubAgentInvocationStateTest {
    @Test
    void childHasItsOwnInvocationAndRestoresOnlyItsOwnersHistory() {
        var store = new InMemoryAgentStateStore();
        var contexts = new ArrayList<RuntimeContext>();
        var requests = new ArrayList<List<Msg>>();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "offline";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        requests.add(List.copyOf(messages));
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }
                };
        MiddlewareBase observer =
                new MiddlewareBase() {
                    public Mono<String> onSystemPrompt(
                            Agent agent, RuntimeContext context, String prompt) {
                        contexts.add(context);
                        return Mono.just(prompt);
                    }
                };
        var tool =
                new SubAgentTool(
                        () ->
                                ReActAgent.builder()
                                        .name("child-agent")
                                        .model(model)
                                        .middleware(observer)
                                        .build(),
                        SubAgentConfig.builder().forwardEvents(false).stateStore(store).build());
        var parent =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("parent")
                        .threadId("review")
                        .turnId("parent-turn")
                        .runId("parent-run")
                        .put(AgentExecutionPosition.class, new AgentExecutionPosition(3, 2))
                        .put(
                                InvocationRecorder.PARENT_KEY,
                                Map.of("parentRunId", "grandparent-run"))
                        .put(
                                AgentExecutionHost.class,
                                (context, result, next) ->
                                        Mono.error(
                                                new IllegalStateException(
                                                        "Parent host must not control a local"
                                                                + " child")))
                        .build();
        parent.put(
                ExecutionIdentity.CONTEXT_KEY,
                new ExecutionIdentity(
                        "parent-agent", "parent", "review", "parent-turn", "parent-run"));
        call(tool, parent, "first");
        call(tool, parent, "second");
        call(tool, RuntimeContext.builder(parent).userId("bob").build(), "third");

        assertEquals(3, contexts.size());
        assertEquals(3, contexts.stream().map(RuntimeContext::getRunId).distinct().count());
        for (var child : contexts) {
            assertEquals("child-session", child.getSessionId());
            assertEquals("main", child.getThreadId());
            assertNotEquals(parent.getRunId(), child.getRunId());
            assertNull(child.getTurnId());
            assertNull(child.get(AgentExecutionHost.class));
            assertNull(child.get(AgentExecutionPosition.class));
            assertEquals(
                    Map.of(
                            "parentSessionId",
                            "parent",
                            "parentThreadId",
                            "review",
                            "parentRunId",
                            "parent-run",
                            "parentTurnId",
                            "parent-turn"),
                    child.get(InvocationRecorder.PARENT_KEY));
            assertEquals("child-session", child.getAgentState().getSessionId());
        }
        assertTrue(
                requests.get(1).stream()
                        .anyMatch(message -> "first".equals(message.getTextContent())));
        assertFalse(
                requests.get(2).stream()
                        .anyMatch(message -> "first".equals(message.getTextContent())));
        assertNull(parent.getAgentState());
        assertEquals("review", parent.getThreadId());
        var key = new ThreadKey("alice", "child-agent", "child-session", "main");
        assertEquals(
                "child-session",
                store.get("alice", "child-session", key.stateEntry("agent_state"), AgentState.class)
                        .orElseThrow()
                        .getSessionId());
    }

    private static void call(SubAgentTool tool, RuntimeContext parent, String message) {
        var result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(parent)
                                        .input(
                                                Map.of(
                                                        "session_id",
                                                        "child-session",
                                                        "message",
                                                        message))
                                        .build())
                        .block(Duration.ofSeconds(10));
        assertTrue(
                result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .anyMatch(text -> text.getText().contains("done")));
    }
}
