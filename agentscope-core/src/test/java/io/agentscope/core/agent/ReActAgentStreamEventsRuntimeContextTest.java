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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.Middleware;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

@DisplayName("ReActAgent streamEvents RuntimeContext propagation")
class ReActAgentStreamEventsRuntimeContextTest {

    @Test
    @DisplayName("onAgent middleware sees sessionId from RuntimeContext during streamEvents")
    void onAgentMiddlewareSeesSessionId() {
        AtomicReference<RuntimeContext> capturedRc = new AtomicReference<>();

        Middleware capturingMiddleware =
                new Middleware() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        RuntimeContext rc =
                                agent instanceof AgentBase ab && ab.getRuntimeContext() != null
                                        ? ab.getRuntimeContext()
                                        : RuntimeContext.empty();
                        capturedRc.set(rc);
                        return next.apply(input);
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .sysPrompt("You are a test agent.")
                        .model(new MockModel("ok"))
                        .toolkit(new Toolkit())
                        .middlewares(List.of(capturingMiddleware))
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("my-session-42").build();

        List<AgentEvent> events =
                agent.streamEvents(List.of(), ctx).collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);

        RuntimeContext captured = capturedRc.get();
        assertNotNull(captured, "onAgent middleware should have captured a RuntimeContext");
        assertEquals(
                "my-session-42",
                captured.getSessionId(),
                "onAgent middleware should see the sessionId from the caller-supplied"
                        + " RuntimeContext");
    }

    @Test
    @DisplayName("onAgent middleware falls back to empty RC when no RuntimeContext supplied")
    void onAgentMiddlewareFallsBackWithoutContext() {
        AtomicReference<RuntimeContext> capturedRc = new AtomicReference<>();

        Middleware capturingMiddleware =
                new Middleware() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        RuntimeContext rc =
                                agent instanceof AgentBase ab && ab.getRuntimeContext() != null
                                        ? ab.getRuntimeContext()
                                        : RuntimeContext.empty();
                        capturedRc.set(rc);
                        return next.apply(input);
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .sysPrompt("You are a test agent.")
                        .model(new MockModel("ok"))
                        .toolkit(new Toolkit())
                        .middlewares(List.of(capturingMiddleware))
                        .build();

        List<AgentEvent> events =
                agent.streamEvents(List.of()).collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);

        RuntimeContext captured = capturedRc.get();
        assertNotNull(captured);
        // Without a RuntimeContext, sessionId is null; the middleware itself falls back to
        // "default"
        assertNull(captured.getSessionId());
    }
}
