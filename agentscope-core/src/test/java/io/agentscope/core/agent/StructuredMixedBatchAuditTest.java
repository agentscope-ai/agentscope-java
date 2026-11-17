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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;

/** Checks that structured-output cleanup preserves unrelated tool-use/result pairs. */
class StructuredMixedBatchAuditTest {
    public static class Receipt {
        public String status;
    }

    public static class BusinessTools {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "Record an action")
        public String recordAction() {
            calls.incrementAndGet();
            return "receipt";
        }
    }

    @ParameterizedTest
    @CsvSource({
        "separate-rounds,false",
        "business-first,false",
        "structured-first,false",
        "separate-rounds,true",
        "business-first,true",
        "structured-first,true"
    })
    void structuredCleanupMustPreserveBusinessCall(String order, boolean persisted) {
        AtomicInteger modelCalls = new AtomicInteger();
        BusinessTools business = new BusinessTools();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(business);
        Model model =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "local-mixed-batch";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int n = modelCalls.incrementAndGet();
                        boolean structured =
                                tools.stream()
                                        .anyMatch(t -> "generate_response".equals(t.getName()));
                        if (!structured) {
                            Set<String> callIds = callIds(messages);
                            Set<String> orphanResults = new HashSet<>(resultIds(messages));
                            orphanResults.removeAll(callIds);
                            if (!orphanResults.isEmpty()) {
                                return Flux.error(
                                        new IllegalArgumentException(
                                                "fixture provider rejects tool_result without"
                                                        + " tool_use: "
                                                        + orphanResults));
                            }
                            return Flux.just(
                                    response(List.of(TextBlock.builder().text("next ok").build())));
                        }
                        ToolUseBlock action = tool("business", "recordAction", Map.of());
                        ToolUseBlock format =
                                tool(
                                        "format",
                                        "generate_response",
                                        Map.of("response", Map.of("status", "ok")));
                        if ("separate-rounds".equals(order)) {
                            return Flux.just(response(List.of(n == 1 ? action : format)));
                        }
                        return Flux.just(
                                response(
                                        "business-first".equals(order)
                                                ? List.of(action, format)
                                                : List.of(format, action)));
                    }
                };
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext rc =
                RuntimeContext.builder().userId("audit-user").sessionId("audit-session").build();
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("audit")
                        .sysPrompt("record then format")
                        .model(model)
                        .toolkit(toolkit);
        if (persisted) builder.stateStore(store);
        ReActAgent agent = builder.build();
        Msg first =
                agent.call(
                                List.of(new UserMessage("Record once and return JSON")),
                                Receipt.class,
                                rc)
                        .block(Duration.ofSeconds(10));
        assertNotNull(first);
        assertEquals("ok", first.getStructuredData(Receipt.class).status);
        assertEquals(1, business.calls.get());
        List<Msg> history =
                persisted
                        ? store.get("audit-user", "audit-session", "agent_state", AgentState.class)
                                .orElseThrow()
                                .getContext()
                        : agent.getAgentState(rc).getContext();
        Msg next = agent.call("Next question", rc).block(Duration.ofSeconds(10));
        assertEquals("next ok", next.getTextContent());
        assertEquals(Set.of("business"), callIds(history));
        assertEquals(Set.of("business"), resultIds(history));
    }

    /** A mixed message is rebuilt, not dropped: its identity and non-tool content must survive. */
    @Test
    void mixedMessageKeepsIdentityAndNonToolContent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new BusinessTools());
        Model model =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "local-mixed-identity";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        boolean structured =
                                tools.stream()
                                        .anyMatch(t -> "generate_response".equals(t.getName()));
                        if (!structured) {
                            return Flux.just(
                                    response(List.of(TextBlock.builder().text("done").build())));
                        }
                        return Flux.just(
                                response(
                                        List.of(
                                                TextBlock.builder().text("working on it").build(),
                                                tool("business", "recordAction", Map.of()),
                                                tool(
                                                        "format",
                                                        "generate_response",
                                                        Map.of(
                                                                "response",
                                                                Map.of("status", "ok"))))));
                    }
                };
        ReActAgent agent = ReActAgent.builder().name("audit").model(model).toolkit(toolkit).build();
        RuntimeContext rc =
                RuntimeContext.builder().userId("audit-user").sessionId("audit-session").build();
        Msg first =
                agent.call(
                                List.of(new UserMessage("Record once and return JSON")),
                                Receipt.class,
                                rc)
                        .block(Duration.ofSeconds(10));
        assertNotNull(first);
        assertEquals("ok", first.getStructuredData(Receipt.class).status);

        List<Msg> history = agent.getAgentState(rc).getContext();
        Msg businessCall =
                history.stream()
                        .filter(
                                m ->
                                        m.getContentBlocks(ToolUseBlock.class).stream()
                                                .anyMatch(tu -> "business".equals(tu.getId())))
                        .findFirst()
                        .orElseThrow();
        assertEquals(MsgRole.ASSISTANT, businessCall.getRole());
        assertEquals("reply", businessCall.getId());
        assertEquals("audit", businessCall.getName());
        assertTrue(
                businessCall.getContentBlocks(TextBlock.class).stream()
                        .anyMatch(tb -> "working on it".equals(tb.getText())));
        assertTrue(
                history.stream()
                        .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                        .noneMatch(tu -> "generate_response".equals(tu.getName())),
                "no synthetic generate_response tool_use should survive cleanup");
        assertEquals(Set.of("business"), resultIds(history));
    }

    private static Set<String> callIds(List<Msg> messages) {
        return messages.stream()
                .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                .map(ToolUseBlock::getId)
                .collect(Collectors.toSet());
    }

    private static Set<String> resultIds(List<Msg> messages) {
        return messages.stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .map(ToolResultBlock::getId)
                .collect(Collectors.toSet());
    }

    private static ToolUseBlock tool(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
    }

    private static ChatResponse response(List<ContentBlock> blocks) {
        return ChatResponse.builder().id("reply").content(blocks).build();
    }
}
