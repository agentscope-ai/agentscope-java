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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@DisplayName("ReActAgent RuntimeContext")
class ReActAgentRuntimeContextTest {

    private static final class SharedPojo {
        final String value;

        SharedPojo(String value) {
            this.value = value;
        }
    }

    private static class CtxTools {
        @Tool(description = "Read RuntimeContext in a tool call")
        public String ctx_probe(
                RuntimeContext ctx, @ToolParam(name = "q", description = "q") String q) {
            SharedPojo p = ctx.get(SharedPojo.class);
            return ctx.getUserId() + "|" + (p != null ? p.value : "null") + "|" + q;
        }
    }

    private InMemoryMemory memory;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        toolkit = new Toolkit();
        toolkit.registerTool(new CtxTools());
    }

    @Test
    @DisplayName("RuntimeContextAware + tools see the same per-call context")
    void awareHookAndToolContext() {
        AtomicReference<RuntimeContext> fromSetter = new AtomicReference<>();
        final int[] modelRound = {0};

        Hook hook = new CtxHook(fromSetter);
        MockModel model =
                new MockModel(
                        messages -> {
                            if (modelRound[0]++ == 0) {
                                return List.of(
                                        createToolResponse(
                                                "ctx_probe", "c1", Map.of("q", "tool-q")));
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("final")
                                                                    .build()))
                                            .usage(new ChatUsage(1, 1, 0))
                                            .build());
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .hooks(List.of(hook))
                        .build();

        RuntimeContext run =
                RuntimeContext.builder()
                        .userId("per-call-uid")
                        .put(SharedPojo.class, new SharedPojo("from-initial-put"))
                        .build();

        Msg user = TestUtils.createUserMessage("User", "use ctx_probe");
        Msg out =
                agent.call(List.of(user), run)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(out);
        String toolOut = lastToolText(agent);
        assertTrue(
                toolOut.contains("per-call-uid|from-pre|tool-q"),
                "unexpected tool output: " + toolOut);

        RuntimeContext r = fromSetter.get();
        assertNull(r, "unbind should clear setRuntimeContext(null)");

        assertTrue(
                memory.getMessages().stream()
                        .anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)));
    }

    private static String lastToolText(ReActAgent agent) {
        List<Msg> list = new ArrayList<>(agent.getMemory().getMessages());
        Collections.reverse(list);
        for (Msg m : list) {
            if (m.getContent() == null) {
                continue;
            }
            for (ContentBlock c : m.getContent()) {
                if (c instanceof ToolResultBlock tr) {
                    for (ContentBlock o : tr.getOutput()) {
                        if (o instanceof TextBlock tb) {
                            return tb.getText();
                        }
                    }
                }
            }
        }
        return "";
    }

    private static ChatResponse createToolResponse(
            String name, String id, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(name)
                                        .id(id)
                                        .input(input)
                                        .content(JsonUtils.getJsonCodec().toJson(input))
                                        .build()))
                .usage(new ChatUsage(1, 1, 0))
                .build();
    }

    private static final class CtxHook implements Hook, RuntimeContextAware {
        private final AtomicReference<RuntimeContext> fromSetter;
        private final AtomicInteger preCount = new AtomicInteger();

        CtxHook(AtomicReference<RuntimeContext> fromSetter) {
            this.fromSetter = fromSetter;
        }

        @Override
        public void setRuntimeContext(RuntimeContext ctx) {
            fromSetter.set(ctx);
        }

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PreReasoningEvent) {
                return Mono.defer(
                        () -> {
                            if (preCount.getAndIncrement() == 0) {
                                AgentBase a = (AgentBase) ((PreReasoningEvent) event).getAgent();
                                RuntimeContext rc = a.getRuntimeContext();
                                assertNotNull(rc);
                                assertEquals("per-call-uid", rc.getUserId());
                                assertEquals("from-initial-put", rc.get(SharedPojo.class).value);
                                rc.put(SharedPojo.class, new SharedPojo("from-pre"));
                            }
                            return Mono.just(event);
                        });
            }
            return Mono.just(event);
        }
    }
}
