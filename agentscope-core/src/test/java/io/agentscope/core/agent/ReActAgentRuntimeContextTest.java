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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    @DisplayName("Middleware + tools see the same per-call context")
    void middlewareAndToolContext() {
        AtomicReference<RuntimeContext> fromMiddleware = new AtomicReference<>();
        final int[] modelRound = {0};

        MiddlewareBase middleware = new CtxMiddleware(fromMiddleware);
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
                        .middlewares(List.of(middleware))
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
        String toolOut = lastToolText(agent, "per-call-uid", null);
        assertTrue(
                toolOut.contains("per-call-uid|from-pre|tool-q"),
                "unexpected tool output: " + toolOut);

        RuntimeContext r = fromMiddleware.get();
        assertSame(run, r, "middleware receives the explicit call context");

        assertTrue(
                agent
                        .getAgentState("per-call-uid", agent.getDefaultSessionId())
                        .getContext()
                        .stream()
                        .anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)));
    }

    @Test
    void buildMergedRuntimeContextCopiesTypedData() throws Exception {
        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(new MockModel("ok"))
                        .toolkit(new Toolkit())
                        .toolExecutionContext(
                                ToolExecutionContext.builder()
                                        .register(new SharedPojo("toolkit"))
                                        .build())
                        .build();

        RuntimeContext callContext =
                RuntimeContext.builder()
                        .userId("user-1")
                        .put(SharedPojo.class, new SharedPojo("from-call"))
                        .build();

        Method method =
                ReActAgent.class.getDeclaredMethod(
                        "buildMergedRuntimeContext", RuntimeContext.class);
        method.setAccessible(true);

        RuntimeContext merged = (RuntimeContext) method.invoke(agent, callContext);
        RuntimeContext mergedFromNull = (RuntimeContext) method.invoke(agent, new Object[] {null});

        assertNotNull(merged);
        assertEquals("user-1", merged.getUserId());
        assertSame(callContext.get(SharedPojo.class), merged.get(SharedPojo.class));
        assertEquals("from-call", merged.asToolExecutionContext().get(SharedPojo.class).value);
        assertNotNull(mergedFromNull);
        assertSame(agent.getToolExecutionContext(), mergedFromNull.getToolExecutionContext());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"complete", "error", "cancel"})
    void concurrentCallKeepsItsContextAfterAnotherCallTerminates(String termination) {
        RuntimeContext alice = RuntimeContext.builder().userId("alice").sessionId("s").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").sessionId("s").build();
        var aliceRelease = reactor.core.publisher.Sinks.<String>one();
        var bobRelease = reactor.core.publisher.Sinks.<String>one();
        Map<String, RuntimeContext> entered = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, RuntimeContext> resumed = new java.util.concurrent.ConcurrentHashMap<>();
        MiddlewareBase middleware =
                new MiddlewareBase() {
                    @Override
                    public Mono<String> onSystemPrompt(
                            Agent agent, RuntimeContext ctx, String prompt) {
                        entered.put(ctx.getUserId(), ctx);
                        var release = ctx.getUserId().equals("alice") ? aliceRelease : bobRelease;
                        return release.asMono()
                                .map(
                                        value -> {
                                            resumed.put(ctx.getUserId(), ctx);
                                            assertEquals(
                                                    ctx.getUserId(),
                                                    ctx.getAgentState().getUserId());
                                            return value;
                                        });
                    }
                };
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("context-isolation")
                        .model(new MockModel("done"))
                        .middlewares(List.of(middleware))
                        .build()) {
            AtomicReference<Throwable> aliceError = new AtomicReference<>();
            AtomicReference<Msg> aliceResult = new AtomicReference<>();
            var callA = agent.call("a", alice).subscribe(aliceResult::set, aliceError::set);
            var callB = agent.call("b", bob).toFuture();
            try {
                assertSame(alice, entered.get("alice"));
                assertSame(bob, entered.get("bob"));
                if (termination.equals("cancel")) {
                    callA.dispose();
                } else if (termination.equals("error")) {
                    aliceRelease.tryEmitError(new IllegalStateException("expected failure"));
                    assertNotNull(aliceError.get());
                } else {
                    aliceRelease.tryEmitValue("system");
                    assertNotNull(aliceResult.get());
                }
                bobRelease.tryEmitValue("system");
                assertNotNull(callB.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertSame(bob, resumed.get("bob"));
                assertEquals("bob", bob.getAgentState().getUserId());
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                callA.dispose();
                callB.cancel(true);
            }
        }
    }

    private static String lastToolText(ReActAgent agent, String userId, String sessionId) {
        String sid = sessionId != null ? sessionId : agent.getDefaultSessionId();
        List<Msg> list = new ArrayList<>(agent.getAgentState(userId, sid).getContext());
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

    private static final class CtxMiddleware implements MiddlewareBase {
        private final AtomicReference<RuntimeContext> observed;

        CtxMiddleware(AtomicReference<RuntimeContext> observed) {
            this.observed = observed;
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
            observed.set(ctx);
            assertEquals("per-call-uid", ctx.getUserId());
            assertEquals("from-initial-put", ctx.get(SharedPojo.class).value);
            ctx.put(SharedPojo.class, new SharedPojo("from-pre"));
            return Mono.just(prompt);
        }
    }
}
