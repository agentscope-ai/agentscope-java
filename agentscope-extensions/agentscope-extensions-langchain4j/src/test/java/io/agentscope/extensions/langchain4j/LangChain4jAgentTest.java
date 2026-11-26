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
package io.agentscope.extensions.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class LangChain4jAgentTest {
    private final Scheduler workers = Schedulers.newBoundedElastic(2, 8, "interop-agent");

    @AfterEach
    void close() {
        workers.dispose();
    }

    public static class SessionTool {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool("Returns the current session")
        public String session(@ToolMemoryId String sessionId) {
            calls.incrementAndGet();
            return sessionId;
        }
    }

    @Test
    void permissionDenialDoesNotInvokeTheForeignExecutor() {
        SessionTool tool = new SessionTool();
        Toolkit toolkit = annotated(tool);
        ScriptedModel model = new ScriptedModel();
        run(toolkit, model, PermissionBehavior.DENY);
        assertEquals(0, tool.calls.get());
        assertEquals(1, model.results.size());
        assertEquals(ToolResultState.DENIED, model.results.get(0).getState());
    }

    @Test
    void allowedAnnotatedToolRoundTripsContextAndCallIdThroughTheAgent() {
        SessionTool tool = new SessionTool();
        ScriptedModel model = new ScriptedModel();
        List<AgentEvent> events = run(annotated(tool), model, PermissionBehavior.ALLOW);
        assertEquals(1, tool.calls.get());
        assertEquals(1, model.results.size());
        ToolResultBlock result = model.results.get(0);
        assertEquals("call-1", result.getId());
        assertEquals("session", result.getName());
        assertEquals("session-42", ((TextBlock) result.getOutput().get(0)).getText());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEndState(events, ToolResultState.SUCCESS);
    }

    @Test
    void structuredForeignFailureReachesTheModelAndTheStreamingEvent() {
        ToolExecutor executor =
                new ToolExecutor() {
                    @Override
                    public String execute(
                            dev.langchain4j.agent.tool.ToolExecutionRequest request, Object id) {
                        throw new AssertionError("executeWithContext must be used");
                    }

                    @Override
                    public ToolExecutionResult executeWithContext(
                            dev.langchain4j.agent.tool.ToolExecutionRequest request,
                            InvocationContext context) {
                        return ToolExecutionResult.builder()
                                .resultText("unavailable")
                                .isError(true)
                                .build();
                    }
                };
        Toolkit toolkit = new Toolkit();
        LangChain4jTools.register(
                toolkit,
                List.of(
                        AiServiceTool.builder()
                                .toolSpecification(
                                        ToolSpecification.builder().name("session").build())
                                .toolExecutor(executor)
                                .build()),
                workers,
                param -> InvocationContext.builder().build());
        ScriptedModel model = new ScriptedModel();
        List<AgentEvent> events = run(toolkit, model, PermissionBehavior.ALLOW);
        assertEquals(1, model.results.size());
        assertEquals(ToolResultState.ERROR, model.results.get(0).getState());
        assertEquals(
                "unavailable", ((TextBlock) model.results.get(0).getOutput().get(0)).getText());
        assertEndState(events, ToolResultState.ERROR);
    }

    private Toolkit annotated(SessionTool tool) {
        Toolkit toolkit = new Toolkit();
        LangChain4jTools.registerAnnotated(
                toolkit,
                tool,
                workers,
                param ->
                        InvocationContext.builder()
                                .chatMemoryId(param.getRuntimeContext().getSessionId())
                                .build());
        return toolkit;
    }

    private List<AgentEvent> run(
            Toolkit toolkit, ScriptedModel model, PermissionBehavior behavior) {
        PermissionRule rule = new PermissionRule("session", null, behavior, "interop-test");
        PermissionContextState.Builder permissions = PermissionContextState.builder();
        if (behavior == PermissionBehavior.DENY) {
            permissions.addDenyRule("session", rule);
        } else {
            permissions.addAllowRule("session", rule);
        }
        ReActAgent agent =
                ReActAgent.builder()
                        .name("interop-test")
                        .model(model)
                        .toolkit(toolkit)
                        .permissionContext(permissions.build())
                        .stateStore(new InMemoryAgentStateStore())
                        .build();
        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("Use the tool")
                                                .build()),
                                RuntimeContext.builder()
                                        .userId("user-7")
                                        .sessionId("session-42")
                                        .build())
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(events);
        return events;
    }

    private static void assertEndState(List<AgentEvent> events, ToolResultState state) {
        List<ToolResultEndEvent> ends =
                events.stream()
                        .filter(ToolResultEndEvent.class::isInstance)
                        .map(ToolResultEndEvent.class::cast)
                        .toList();
        assertEquals(1, ends.size());
        assertEquals("call-1", ends.get(0).getToolCallId());
        assertEquals(state, ends.get(0).getState());
    }

    private static final class ScriptedModel extends ChatModelBase {
        private int turns;
        private List<ToolResultBlock> results = List.of();

        @Override
        public String getModelName() {
            return "interop-script";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (turns++ == 0) {
                assertTrue(tools.stream().anyMatch(tool -> "session".equals(tool.getName())));
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("call-1")
                                                        .name("session")
                                                        .input(Map.of())
                                                        .content("{}")
                                                        .build()))
                                .build());
            }
            results =
                    messages.stream()
                            .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }
}
