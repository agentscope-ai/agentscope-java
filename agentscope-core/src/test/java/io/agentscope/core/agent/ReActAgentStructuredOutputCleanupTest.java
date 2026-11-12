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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;

class ReActAgentStructuredOutputCleanupTest {
    private static final RuntimeContext CONTEXT =
            RuntimeContext.builder().userId("user").sessionId("cleanup-session").build();
    private static final ChatUsage USAGE = new ChatUsage(100, 50, 12, 7, 9, 3, 1.5);

    public static class Receipt {
        public String status;
    }

    public static class BusinessTools {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "Record an action")
        public String recordAction() {
            return "receipt-" + calls.incrementAndGet();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "pure,false",
        "pure,true",
        "separate,false",
        "separate,true",
        "first,false",
        "first,true",
        "last,false",
        "last,true"
    })
    void cleanupPreservesBusinessHistory(String order, boolean persisted) {
        BusinessTools business = new BusinessTools();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(business);
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        Msg reminder =
                SystemMessage.builder()
                        .textContent("old structured-output reminder")
                        .metadata(Map.of(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER, true))
                        .build();
        if (persisted) {
            store.save(
                    "user",
                    "cleanup-session",
                    "agent_state",
                    AgentState.builder().context(List.of(reminder)).build());
        }
        AtomicReference<Msg> original = new AtomicReference<>();
        FixtureModel model = new FixtureModel(order);
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("cleanup")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Set<ExtensionPoint> activePoints() {
                                        return Set.of(ExtensionPoint.ON_ACTING);
                                    }

                                    @Override
                                    public Flux<AgentEvent> onActing(
                                            Agent agent,
                                            RuntimeContext ctx,
                                            ActingInput input,
                                            Function<ActingInput, Flux<AgentEvent>> next) {
                                        ctx.getAgentState().getContext().stream()
                                                .filter(m -> "response-1".equals(m.getId()))
                                                .findFirst()
                                                .ifPresent(original::set);
                                        return next.apply(input);
                                    }
                                });
        if (persisted) builder.stateStore(store);
        ReActAgent agent = builder.build();
        Msg emptyInput = new UserMessage("");
        Msg result =
                agent.call(
                                List.of(emptyInput, new UserMessage("Record and format")),
                                Receipt.class,
                                CONTEXT)
                        .block(Duration.ofSeconds(10));
        assertNotNull(result);
        assertEquals("ok", result.getStructuredData(Receipt.class).status);
        Set<String> expected = "pure".equals(order) ? Set.of() : Set.of("business-1", "business-2");
        assertEquals(expected.size(), business.calls.get());

        List<Msg> history =
                persisted
                        ? store.get("user", "cleanup-session", "agent_state", AgentState.class)
                                .orElseThrow()
                                .getContext()
                        : agent.getAgentState(CONTEXT).getContext();
        assertEquals(expected, callIds(history));
        assertEquals(expected, resultIds(history));
        assertFalse(
                history.stream().anyMatch(m -> reminder.getId().equals(m.getId())),
                "Keep the existing cleanup of persisted internal reminders");
        assertTrue(
                history.stream().anyMatch(m -> emptyInput.getId().equals(m.getId())),
                "Unrelated empty messages must not be removed by output cleanup");
        assertFalse(
                history.stream()
                        .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                        .anyMatch(t -> "generate_response".equals(t.getName())));
        assertFalse(
                history.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .anyMatch(t -> "generate_response".equals(t.getName())));
        assertEquals(USAGE, result.getUsage());
        assertEquals(USAGE, result.getChatUsage());
        assertEquals("reasoning", result.getFirstContentBlock(ThinkingBlock.class).getThinking());
        assertEquals(
                100 * model.calls.get(),
                history.stream()
                        .map(Msg::getChatUsage)
                        .filter(Objects::nonNull)
                        .mapToInt(ChatUsage::getInputTokens)
                        .sum());

        if (!"pure".equals(order)) {
            Msg retained =
                    history.stream()
                            .filter(m -> "response-1".equals(m.getId()))
                            .findFirst()
                            .orElseThrow();
            assertEquals(original.get().getTimestamp(), retained.getTimestamp());
            assertEquals(original.get().getName(), retained.getName());
            assertEquals(original.get().getRole(), retained.getRole());
            assertEquals("provider-value", retained.getMetadata().get("provider-key"));
            assertEquals("Working", retained.getTextContent());
            assertEquals(
                    "reasoning", retained.getFirstContentBlock(ThinkingBlock.class).getThinking());
            if (!"separate".equals(order)) {
                assertNull(retained.getUsage(), "Usage is already carried by the final result");
                assertNull(retained.getChatUsage());
                assertFalse(retained.getMetadata().containsKey(MessageMetadataKeys.CHAT_USAGE));
                assertEquals(
                        USAGE, original.get().getUsage(), "Do not mutate the original message");
            }
        }

        // A rebuilt agent must receive valid history too, not just the current in-memory scope.
        ReActAgent continuing =
                persisted
                        ? ReActAgent.builder()
                                .name("cleanup")
                                .model(model)
                                .toolkit(toolkit)
                                .stateStore(store)
                                .build()
                        : agent;
        Msg next = continuing.call("Next question", CONTEXT).block(Duration.ofSeconds(10));
        assertNotNull(next);
        assertEquals("next ok", next.getTextContent());
        assertEquals(
                expected.size(), business.calls.get(), "History cleanup must not replay tools");
    }

    private static final class FixtureModel implements Model {
        private final String order;
        private final AtomicInteger calls = new AtomicInteger();

        private FixtureModel(String order) {
            this.order = order;
        }

        @Override
        public String getModelName() {
            return "cleanup-fixture";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean structured =
                    tools.stream().anyMatch(t -> "generate_response".equals(t.getName()));
            if (!structured) {
                assertEquals(
                        callIds(messages),
                        resultIds(messages),
                        "Next request must have paired history");
                return Flux.just(
                        ChatResponse.builder()
                                .id("next")
                                .content(List.of(TextBlock.builder().text("next ok").build()))
                                .build());
            }
            int n = calls.incrementAndGet();
            List<ContentBlock> content = new ArrayList<>();
            content.add(
                    ThinkingBlock.builder()
                            .thinking("reasoning")
                            .metadata(Map.of("signature", "sig"))
                            .build());
            content.add(TextBlock.builder().text("Working").build());
            ToolUseBlock format =
                    tool("format", "generate_response", Map.of("response", Map.of("status", "ok")));
            if ("pure".equals(order) || ("separate".equals(order) && n == 2)) {
                content.add(format);
            } else {
                if ("first".equals(order)) content.add(format);
                content.add(tool("business-1", "recordAction", Map.of()));
                content.add(tool("business-2", "recordAction", Map.of()));
                if ("last".equals(order)) content.add(format);
            }
            return Flux.just(
                    ChatResponse.builder()
                            .id("response-" + n)
                            .content(content)
                            .metadata(Map.of("provider-key", "provider-value"))
                            .usage(USAGE)
                            .build());
        }
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
}
