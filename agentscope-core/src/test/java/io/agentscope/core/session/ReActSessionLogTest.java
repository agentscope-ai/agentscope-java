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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ReActSessionLogTest {
    private RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    private static class CaptureModel implements Model {
        List<List<Msg>> calls = new ArrayList<>();

        public String getModelName() {
            return "fake";
        }

        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(msgs));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("answer").build()))
                            .build());
        }
    }

    @Test
    void rebuiltAgentRestoresCompleteHistoryAndRecordsFinalRequest() {
        var store = new InMemorySessionLogStore();
        var first = new CaptureModel();
        var a = ReActAgent.builder().name("stable").model(first).sessionLogStore(store).build();
        String large = "input".repeat(1000);
        a.call(List.of(new UserMessage(large)), context()).block(Duration.ofSeconds(10));
        var second = new CaptureModel();
        var b = ReActAgent.builder().name("stable").model(second).sessionLogStore(store).build();
        b.call(List.of(new UserMessage("continue")), context()).block(Duration.ofSeconds(10));
        assertTrue(second.calls.get(0).stream().anyMatch(m -> large.equals(m.getTextContent())));
        var events = store.open(new SessionKey("u", "stable", "s"), context()).readAfter(0, 1000);
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e.type().equals("request/prepared")
                                                && e.payloadJson().contains(large)));
        assertEquals(2, events.stream().filter(e -> e.type().equals("run/end")).count());
        assertTrue(
                SessionProjection.read(store.open(new SessionKey("u", "stable", "s"), context()))
                        .activeRuns()
                        .isEmpty());
    }

    @Test
    void nativeAdministrationUsesFencedCheckpointsAndNeverLoadsLegacyState() {
        var store = new InMemorySessionLogStore();
        var legacy = new InMemoryAgentStateStore();
        legacy.save(
                "u",
                "s",
                "agent_state",
                AgentState.builder()
                        .sessionId("s")
                        .userId("u")
                        .context(List.of(new UserMessage("legacy-only")))
                        .build());
        var model = new CaptureModel();
        var agent =
                ReActAgent.builder()
                        .name("stable")
                        .model(model)
                        .sessionLogStore(store)
                        .stateStore(legacy)
                        .build();
        assertTrue(agent.getAgentState(context()).getContext().isEmpty());
        agent.call(List.of(new UserMessage("native")), context()).block(Duration.ofSeconds(10));
        assertTrue(
                model.calls.get(0).stream()
                        .noneMatch(message -> "legacy-only".equals(message.getTextContent())));
        agent.setPermissionMode(context(), PermissionMode.BYPASS);
        agent.clearContext(context());
        var rebuilt =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionLogStore(store)
                        .build();
        assertTrue(rebuilt.getAgentState(context()).getContext().isEmpty());
        assertEquals(PermissionMode.BYPASS, rebuilt.getPermissionMode("u", "s"));
        assertTrue(
                SessionViews.transcript(agent.sessionLog(context())).messages().stream()
                        .anyMatch(message -> "native".equals(message.getTextContent())));
        assertEquals(
                "legacy-only",
                legacy.get("u", "s", "agent_state", AgentState.class)
                        .orElseThrow()
                        .getContext()
                        .get(0)
                        .getTextContent());
    }

    @Test
    void detachedStateCannotOverwriteAnotherWriterAndInspectionDoesNotMutate() {
        var store = new InMemorySessionLogStore();
        var first =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionLogStore(store)
                        .build();
        var second =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionLogStore(store)
                        .build();
        var log = first.sessionLog(context());
        var head = log.head();
        first.inspectAgentState(context());
        assertEquals(head, log.head());
        AgentState stale = first.getAgentState(context());
        stale.setSummary("stale edit");
        second.updateAgentState(context(), "admin_update", state -> state.setSummary("current"));
        assertThrows(SessionLogException.class, () -> first.saveAgentState(context()));
        assertEquals("current", first.inspectAgentState(context()).state().getSummary());
        var writer = log.acquire("execution", Duration.ofMinutes(2));
        try {
            assertThrows(SessionLogException.class, () -> first.clearContext(context()));
            assertEquals("current", first.inspectAgentState(context()).state().getSummary());
        } finally {
            log.release(writer);
        }
        var editable = first.getAgentState(context());
        editable.setSummary("saved");
        first.saveAgentState(context());
        assertEquals("saved", second.inspectAgentState(context()).state().getSummary());
    }

    @Test
    void nativeAdministrationPreservesBackendRoutingAttributes() {
        var backing = new InMemorySessionLogStore();
        SessionLogStore routed =
                (key, context) -> {
                    assertEquals("tenant-space", context.get("namespace"));
                    return backing.open(key, context);
                };
        var ctx = context();
        ctx.put("namespace", "tenant-space");
        var agent =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionLogStore(routed)
                        .build();
        agent.updateAgentState(ctx, "setup", state -> state.setSummary("routed"));
        assertEquals("routed", agent.inspectAgentState(ctx).state().getSummary());
        agent.getAgentState(ctx).setSummary("edited");
        agent.saveAgentState(ctx);
        agent.setPermissionMode(ctx, PermissionMode.BYPASS);
        agent.clearContext(ctx);
        assertEquals(
                PermissionMode.BYPASS,
                agent.inspectAgentState(ctx).state().getPermissionContext().getMode());
    }

    @Test
    void failedDispatchCheckpointPreventsModelInvocation() {
        var backing = new InMemorySessionLogStore();
        var model = new CaptureModel();
        SessionLogStore failing =
                (key, context) ->
                        new SessionLog() {
                            final SessionLog delegate = backing.open(key, context);

                            public Head head() {
                                return delegate.head();
                            }

                            public Writer acquire(String owner, Duration lease) {
                                return delegate.acquire(owner, lease);
                            }

                            public void renew(Writer writer, Duration lease) {
                                delegate.renew(writer, lease);
                            }

                            public void release(Writer writer) {
                                delegate.release(writer);
                            }

                            public List<SessionEvent> readAfter(long seq, int limit) {
                                return delegate.readAfter(seq, limit);
                            }

                            public Head commit(
                                    Writer writer, String id, long seq, List<SessionEvent> events) {
                                if (events.stream()
                                        .anyMatch(e -> e.type().equals("model/dispatch")))
                                    throw new SessionLogException("injected");
                                return delegate.commit(writer, id, seq, events);
                            }
                        };
        var agent =
                ReActAgent.builder().name("stable").model(model).sessionLogStore(failing).build();
        assertThrows(
                RuntimeException.class,
                () ->
                        agent.call(List.of(new UserMessage("x")), context())
                                .block(Duration.ofSeconds(10)));
        assertTrue(model.calls.isEmpty());
    }
}
