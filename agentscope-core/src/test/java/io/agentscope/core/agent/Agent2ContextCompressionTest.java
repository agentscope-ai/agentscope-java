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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.config.ContextConfig;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Drives the full Agent2 react loop and verifies that the context compressor fires inside the
 * reasoning prelude — populating {@link AgentState#getSummary()} and shrinking the context.
 */
class Agent2ContextCompressionTest {

    private static final class CountingModel extends ChatModelBase {
        final AtomicInteger calls = new AtomicInteger(0);
        private final String reply;

        CountingModel(String reply) {
            this.reply = reply;
        }

        @Override
        public String getModelName() {
            return "counting";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text(reply).build()))
                            .build());
        }
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    @Test
    void agentTriggersMicroCompactWhenSystemContextExceedsReserve() {
        ContextConfig cfg = ContextConfig.builder().triggerRatio(0.005).reserveRatio(0.001).build();
        CountingModel model = new CountingModel("compressed-summary");

        AgentState state = AgentState.builder().sessionId("session-compress").build();
        // One huge SYSTEM message (~800 tokens). Snip cannot drop systems, so micro-compact runs.
        state.contextMutable().add(systemMsg(repeat("s", 3200)));

        Agent2 agent =
                new Agent2("asst", null, model, new Toolkit(), List.of(), state, null, cfg, null);

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);
        assertTrue(events.size() > 0);

        // Model was invoked at least twice: once by the compressor (micro-compact) and once by
        // the reasoning step that produced the final reply.
        assertTrue(
                model.calls.get() >= 2,
                "compressor + reasoning must call model at least twice, got " + model.calls.get());

        assertEquals(
                "compressed-summary",
                state.getSummary(),
                "state.summary must be populated by micro-compact");

        // Post-compact context contains: preserved system + rendered summary user + appended
        // user input (none here) + assistant final reply.
        assertTrue(
                state.getContext().size() <= 3,
                "context must be trimmed; got " + state.getContext().size());
        assertEquals(MsgRole.SYSTEM, state.getContext().get(0).getRole());
    }

    @Test
    void agentSkipsCompressionWhenContextIsSmall() {
        ContextConfig cfg = ContextConfig.builder().triggerRatio(0.5).reserveRatio(0.1).build();
        CountingModel model = new CountingModel("ok");

        AgentState state = AgentState.builder().sessionId("session-no-compress").build();

        Agent2 agent =
                new Agent2(
                        "asst",
                        "you are helpful",
                        model,
                        new Toolkit(),
                        List.of(),
                        state,
                        null,
                        cfg,
                        null);

        agent.streamEvents(List.of(userMsg("hi"))).collectList().block();

        assertEquals(
                1,
                model.calls.get(),
                "no compression — model called once for the single reasoning round");
        assertEquals("", state.getSummary(), "summary stays empty when no compression fires");
    }

    private static Msg systemMsg(String text) {
        return Msg.builder().role(MsgRole.SYSTEM).textContent(text).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }
}
