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
package io.agentscope.core.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class ReActThreadJournalTest {
    @TempDir Path stateDirectory;

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

    @ParameterizedTest
    @CsvSource({
        "log-id, display-name, log-id",
        "log-id, , log-id",
        ", display-name, display-name",
        ", , framework-id",
        ", '', framework-id",
        ", '   ', framework-id"
    })
    void journalIdentityUsesExplicitIdThenNonBlankNameThenAgentId(
            String journalAgentId, String name, String expected) {
        var builder =
                ReActAgent.builder().agentId("framework-id").name(name).model(new CaptureModel());
        if (journalAgentId != null) builder.journalAgentId(journalAgentId);
        var agent = builder.build();

        assertEquals(new ThreadKey("u", expected, "s", "main"), agent.threadKey(context()));
    }

    @Test
    void unnamedAgentWithoutExplicitIdUsesGeneratedIdForSessionIdentity() {
        var agent = ReActAgent.builder().model(new CaptureModel()).build();

        assertEquals(
                new ThreadKey("u", agent.getAgentId(), "s", "main"), agent.threadKey(context()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unnamedAgentSupportsCallAndStreamWithOrWithoutJournal(boolean journalEnabled) {
        var model = new CaptureModel();
        var builder = ReActAgent.builder().agentId("framework-id").model(model);
        if (journalEnabled) builder.threadJournalStore(new InMemoryThreadJournalStore());
        var agent = builder.build();

        var reply =
                agent.call(List.of(new UserMessage("hello")), context())
                        .block(Duration.ofSeconds(10));
        assertEquals("answer", reply.getTextContent());
        var events =
                agent.streamEvents(List.of(new UserMessage("continue")), context())
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertFalse(events.isEmpty());
        assertTrue(
                events.stream().allMatch(e -> "framework-id".equals(e.getExecution().agentId())));
        assertEquals(2, model.calls.size());
        if (journalEnabled) {
            var facts = agent.threadJournal(context()).readAfter(0, 1000);
            assertEquals(2, facts.stream().filter(e -> e.type().equals("run/end")).count());
        }
    }

    @Test
    void rebuiltAgentRestoresCompleteHistoryAndRecordsFinalRequest() {
        var store = new InMemoryThreadJournalStore();
        var first = new CaptureModel();
        var a = ReActAgent.builder().name("stable").model(first).threadJournalStore(store).build();
        String large = "input".repeat(1000);
        a.call(List.of(new UserMessage(large)), context()).block(Duration.ofSeconds(10));
        var second = new CaptureModel();
        var b = ReActAgent.builder().name("stable").model(second).threadJournalStore(store).build();
        b.call(List.of(new UserMessage("continue")), context()).block(Duration.ofSeconds(10));
        assertTrue(second.calls.get(0).stream().anyMatch(m -> large.equals(m.getTextContent())));
        var events =
                store.open(new ThreadKey("u", "stable", "s", "main"), context()).readAfter(0, 1000);
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e.type().equals("request/prepared")
                                                && e.payloadJson().contains(large)));
        assertEquals(2, events.stream().filter(e -> e.type().equals("run/end")).count());
        assertTrue(
                JournalProjection.read(
                                store.open(new ThreadKey("u", "stable", "s", "main"), context()))
                        .activeRuns()
                        .isEmpty());
    }

    @Test
    void emptyMainThreadImportsAgentStateWrittenByVersion2() throws Exception {
        var store = new InMemoryThreadJournalStore();
        AgentState version2State =
                AgentState.builder()
                        .sessionId("s")
                        .userId("u")
                        .context(List.of(new UserMessage("from-2.0")))
                        .build();
        var version2Json =
                JsonUtils.getJsonCodec()
                        .fromJson(
                                version2State.toJson(),
                                com.fasterxml.jackson.databind.node.ObjectNode.class);
        version2Json.remove("thread_id");
        Path stateFile = stateDirectory.resolve("u/s/agent_state.json");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, version2Json.toPrettyString());
        var legacy = new JsonFileAgentStateStore(stateDirectory);
        var model = new CaptureModel();
        var agent =
                ReActAgent.builder()
                        .name("stable")
                        .model(model)
                        .threadJournalStore(store)
                        .stateStore(legacy)
                        .build();
        assertEquals(
                "from-2.0", agent.getAgentState(context()).getContext().get(0).getTextContent());
        agent.call(List.of(new UserMessage("native")), context()).block(Duration.ofSeconds(10));
        assertTrue(
                model.calls.get(0).stream()
                        .anyMatch(message -> "from-2.0".equals(message.getTextContent())));
        assertEquals(
                1,
                agent.threadJournal(context()).readAfter(0, 1000).stream()
                        .filter(event -> event.type().equals("migration/baseline"))
                        .count());
        assertTrue(
                legacy
                        .get("u", "s", "agent_state", AgentState.class)
                        .orElseThrow()
                        .getContext()
                        .stream()
                        .anyMatch(message -> "native".equals(message.getTextContent())));
        agent.setPermissionMode(context(), PermissionMode.BYPASS);
        agent.clearContext(context());
        var rebuilt =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .threadJournalStore(store)
                        .build();
        assertTrue(rebuilt.getAgentState(context()).getContext().isEmpty());
        assertEquals(PermissionMode.BYPASS, rebuilt.getPermissionMode("u", "s"));
        assertTrue(
                ThreadHistory.transcript(agent.threadJournal(context())).messages().stream()
                        .anyMatch(message -> "native".equals(message.getTextContent())));
        AgentState mirrored = legacy.get("u", "s", "agent_state", AgentState.class).orElseThrow();
        assertTrue(mirrored.getContext().isEmpty());
        assertEquals(PermissionMode.BYPASS, mirrored.getPermissionContext().getMode());
    }

    @Test
    void namedThreadDoesNotImportVersion2MainState() {
        var journal = new InMemoryThreadJournalStore();
        var legacy = new JsonFileAgentStateStore(stateDirectory);
        legacy.save(
                "u",
                "s",
                "agent_state",
                AgentState.builder()
                        .sessionId("s")
                        .userId("u")
                        .context(List.of(new UserMessage("main-only")))
                        .build());
        var model = new CaptureModel();
        var agent =
                ReActAgent.builder()
                        .name("stable")
                        .model(model)
                        .threadJournalStore(journal)
                        .stateStore(legacy)
                        .build();
        RuntimeContext child =
                RuntimeContext.builder().userId("u").sessionId("s").threadId("child").build();
        agent.call(List.of(new UserMessage("child")), child).block(Duration.ofSeconds(10));
        assertTrue(
                model.calls.get(0).stream()
                        .noneMatch(message -> "main-only".equals(message.getTextContent())));
        assertTrue(
                agent.threadJournal(child).readAfter(0, 1000).stream()
                        .noneMatch(event -> event.type().equals("migration/baseline")));
    }

    @Test
    void detachedStateCannotOverwriteAnotherWriterAndInspectionDoesNotMutate() {
        var store = new InMemoryThreadJournalStore();
        var first =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .threadJournalStore(store)
                        .build();
        var second =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .threadJournalStore(store)
                        .build();
        var log = first.threadJournal(context());
        var head = log.head();
        first.inspectThreadState(context());
        assertEquals(head, log.head());
        AgentState stale = first.getAgentState(context());
        stale.setSummary("stale edit");
        second.updateAgentState(context(), "admin_update", state -> state.setSummary("current"));
        assertThrows(JournalException.class, () -> first.saveAgentState(context()));
        assertEquals("current", first.inspectThreadState(context()).state().getSummary());
        var writer = log.acquire("execution", Duration.ofMinutes(2));
        try {
            assertThrows(JournalException.class, () -> first.clearContext(context()));
            assertEquals("current", first.inspectThreadState(context()).state().getSummary());
        } finally {
            log.release(writer);
        }
        var editable = first.getAgentState(context());
        editable.setSummary("saved");
        first.saveAgentState(context());
        assertEquals("saved", second.inspectThreadState(context()).state().getSummary());
    }

    @Test
    void nativeAdministrationPreservesBackendRoutingAttributes() {
        var backing = new InMemoryThreadJournalStore();
        ThreadJournalStore routed =
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
                        .threadJournalStore(routed)
                        .build();
        agent.updateAgentState(ctx, "setup", state -> state.setSummary("routed"));
        assertEquals("routed", agent.inspectThreadState(ctx).state().getSummary());
        agent.getAgentState(ctx).setSummary("edited");
        agent.saveAgentState(ctx);
        agent.setPermissionMode(ctx, PermissionMode.BYPASS);
        agent.clearContext(ctx);
        assertEquals(
                PermissionMode.BYPASS,
                agent.inspectThreadState(ctx).state().getPermissionContext().getMode());
    }

    @Test
    void failedDispatchCheckpointPreventsModelInvocation() {
        var backing = new InMemoryThreadJournalStore();
        var model = new CaptureModel();
        ThreadJournalStore failing =
                (key, context) ->
                        new ThreadJournal() {
                            final ThreadJournal delegate = backing.open(key, context);

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

                            public List<JournalEvent> readAfter(long seq, int limit) {
                                return delegate.readAfter(seq, limit);
                            }

                            public Head commit(
                                    Writer writer, String id, long seq, List<JournalEvent> events) {
                                if (events.stream()
                                        .anyMatch(e -> e.type().equals("model/dispatch")))
                                    throw new JournalException("injected");
                                return delegate.commit(writer, id, seq, events);
                            }
                        };
        var agent =
                ReActAgent.builder()
                        .name("stable")
                        .model(model)
                        .threadJournalStore(failing)
                        .build();
        assertThrows(
                RuntimeException.class,
                () ->
                        agent.call(List.of(new UserMessage("x")), context())
                                .block(Duration.ofSeconds(10)));
        assertTrue(model.calls.isEmpty());
    }
}
