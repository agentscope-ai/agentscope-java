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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Regression test for #2918: segments of the default {@link ObjectStoreTranscriptStore} must be
 * keyed so that they land on the sessions route {@code RemoteFilesystemSpec} registers ({@code
 * agents/{agentId}/sessions/}). Keyed as {@code {tenant}/{agentId}/{sessionId}/events/...} they
 * match no route and stay on the default backend, which is the local disk.
 */
@HarnessQuiescence
class SessionTranscriptStoreRoutingTest {

    @TempDir Path workspace;

    /** Key of a transcript segment as {@link ObjectStoreTranscriptStore} names it. */
    private static final Pattern SEGMENT = Pattern.compile(".*/events/\\d+-\\d+-[^.]+\\.jsonl");

    private static boolean isSegment(StoreItem item) {
        return SEGMENT.matcher(item.key()).matches();
    }

    private static Msg message(String id, String text) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Msg userMsg(String text) {
        return message("user", text);
    }

    private static Model stubModel(String text) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "stub-id",
                                        List.of(TextBlock.builder().text(text).build()),
                                        null,
                                        Map.of(),
                                        "stop")));
        return model;
    }

    /** The sessions route of {@code agent-a} as {@code RemoteFilesystemSpec} registers it. */
    private AbstractFilesystem compositeWithSessionsRoute(InMemoryStore store) {
        RemoteFilesystem sessionsRoute =
                new RemoteFilesystem(
                        store, List.of("agents", "agent-a", "users", "user-1", "sessions"));
        LocalFilesystem defaultBackend =
                new LocalFilesystem(workspace, false, 10, rc -> List.of("u1"));
        return new CompositeFilesystem(
                defaultBackend, Map.of("agents/agent-a/sessions/", sessionsRoute));
    }

    private static void append(SessionTranscriptWriter writer, String agentId, String sessionId) {
        writer.appendMessages(
                RuntimeContext.builder().userId("user-1").sessionId(sessionId).build(),
                List.of(message("m1", "hello"), message("m2", "world")),
                agentId,
                sessionId);
    }

    /** The mirror upload is asynchronous; wait for it instead of racing the mirror executor. */
    private static void awaitMirror() throws InterruptedException {
        assertTrue(
                SessionTree.awaitMirrorQuiescence(10, TimeUnit.SECONDS),
                "mirror upload did not settle in time");
    }

    private boolean workspaceHas(String part) throws Exception {
        try (var files = Files.walk(workspace)) {
            return files.anyMatch(path -> workspace.relativize(path).toString().contains(part));
        }
    }

    @Test
    void segmentsHitTheSessionsRouteOfTheRoutingId() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem composite = compositeWithSessionsRoute(store);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, composite)) {
            ObjectStoreTranscriptStore transcriptStore = new ObjectStoreTranscriptStore(composite);
            // The route was built with "agent-a", the caller passes a display name: only the
            // routing id can hit it.
            SessionTranscriptWriter writer =
                    new SessionTranscriptWriter(wm, transcriptStore, "default", "agent-a");
            append(writer, "agent-qa-display", "session-1");
        }

        awaitMirror();

        assertTrue(
                store.search(List.of("agents", "agent-a"), 50, 0).stream()
                        .anyMatch(item -> isSegment(item)),
                "segments must land in the store behind the sessions route");
        assertFalse(workspaceHas("events"), "segments must not stay on the default backend");
    }

    @Test
    void segmentsHitTheSessionsRouteOfTheCallersIdWhenNoRoutingIdIsGiven() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem composite = compositeWithSessionsRoute(store);
        try (WorkspaceManager wm = new WorkspaceManager(workspace, composite)) {
            ObjectStoreTranscriptStore transcriptStore = new ObjectStoreTranscriptStore(composite);
            SessionTranscriptWriter writer =
                    new SessionTranscriptWriter(wm, transcriptStore, "default");
            append(writer, "agent-a", "session-1");
        }

        awaitMirror();

        assertTrue(
                store.search(List.of("agents", "agent-a"), 50, 0).stream()
                        .anyMatch(item -> isSegment(item)),
                "segments must land in the store behind the sessions route");
        assertFalse(workspaceHas("events"), "segments must not stay on the default backend");
    }

    @Test
    void otherTranscriptStoresKeepTheTenantLayout() throws Exception {
        RecordingStore store = new RecordingStore();
        try (WorkspaceManager wm = new WorkspaceManager(workspace, null)) {
            SessionTranscriptWriter writer =
                    new SessionTranscriptWriter(wm, store, "tenant-x", "agent-a");
            append(writer, "agent-qa-display", "session-1");
        }

        awaitMirror();

        assertEquals(List.of("tenant-x/agent-qa-display/session-1"), store.segmentPrefixes);
    }

    @Test
    void segmentsOfABuiltAgentReachTheSessionsRouteOfItsAgentId() throws Exception {
        InMemoryStore store = new InMemoryStore();
        var builder =
                HarnessAgent.builder()
                        .name("Agent QA Display")
                        .agentId("agent-qa")
                        .model(stubModel("done"))
                        .workspace(workspace)
                        .filesystem(new RemoteFilesystemSpec(store))
                        // Remote filesystems require a non-local session store.
                        .stateStore(
                                mock(
                                        AgentStateStore.class,
                                        delegatesTo(new InMemoryAgentStateStore())))
                        .disableMemoryHooks();
        try (var agent = builder.build()) {
            agent.call(
                            userMsg("hello"),
                            RuntimeContext.builder().userId("alice").sessionId("s-1").build())
                    .block();
        }

        awaitMirror();

        List<StoreItem> segments =
                store
                        .search(List.of("agents", "agent-qa", "users", "alice", "sessions"), 200, 0)
                        .stream()
                        .filter(item -> isSegment(item))
                        .toList();
        assertFalse(segments.isEmpty(), "segments must reach the store of the sessions route");
        assertTrue(
                store.search(List.of("agents", "Agent QA Display"), 200, 0).isEmpty(),
                "segments must not be keyed on the display name");
        assertFalse(workspaceHas("events"), "segments must not stay on the local disk");
    }

    /** Records the refs segments are appended under and keeps nothing. */
    private static final class RecordingStore implements TranscriptStore {
        private final List<String> segmentPrefixes = new ArrayList<>();

        @Override
        public String appendSegment(
                TranscriptRef ref, long seqStart, long seqEnd, String writerId, byte[] jsonl) {
            segmentPrefixes.add(ref.prefix());
            return ref.prefix() + "/events/" + seqStart + "-" + seqEnd + "-" + writerId;
        }

        @Override
        public List<SegmentInfo> listSegments(TranscriptRef ref) {
            return List.of();
        }

        @Override
        public InputStream readSegment(String segmentKey) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void delete(TranscriptRef ref) {}
    }
}
