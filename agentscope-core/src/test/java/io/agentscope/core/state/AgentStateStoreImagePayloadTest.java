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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ImagePayloadState;
import io.agentscope.core.message.ImagePayloadTransformer;
import io.agentscope.core.message.UserMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentStateStoreImagePayloadTest {

    private static final String PNG_DATA = "aW1hZ2UtcGF5bG9hZA==";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void savesLightweightStateAndLoadsLeanOrFullWithoutMutatingInput() throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        AgentState original = stateWithImage("session-1", PNG_DATA);

        store.saveAgentState("alice", "session-1", original);

        AgentState stored =
                store.get("alice", "session-1", AgentStateStore.AGENT_STATE_KEY, AgentState.class)
                        .orElseThrow();
        AgentState lean =
                store.getAgentState("alice", "session-1", AgentStateLoadMode.LEAN).orElseThrow();
        AgentState full = store.getAgentState("alice", "session-1").orElseThrow();

        assertFalse(json(stored).contains(PNG_DATA));
        assertEquals(json(stored), json(lean));
        assertEquals(json(original), json(full));
        assertTrue(json(original).contains(PNG_DATA), "the caller's state must remain unchanged");
    }

    @Test
    void leanLoadDoesNotReadImagePayloads() {
        CountingStore store = new CountingStore();
        store.saveAgentState(null, "session-1", stateWithImage("session-1", PNG_DATA));
        store.resetPayloadReads();

        store.getAgentState(null, "session-1", AgentStateLoadMode.LEAN).orElseThrow();
        assertEquals(0, store.payloadReads());

        store.getAgentState(null, "session-1", AgentStateLoadMode.FULL).orElseThrow();
        assertEquals(1, store.payloadReads());
    }

    @Test
    void savingAnUnchangedLeanStateDoesNotReadHistoricalPayloads() {
        CountingStore store = new CountingStore();
        store.saveAgentState(null, "session-1", stateWithImage("session-1", PNG_DATA));
        AgentState lean =
                store.getAgentState(null, "session-1", AgentStateLoadMode.LEAN).orElseThrow();
        store.resetPayloadReads();

        store.saveAgentState(null, "session-1", lean);

        assertEquals(0, store.payloadReads());
        assertEquals(
                PNG_DATA,
                ((Base64Source)
                                ((ImageBlock)
                                                store.getAgentState(null, "session-1")
                                                        .orElseThrow()
                                                        .getContext()
                                                        .get(0)
                                                        .getContent()
                                                        .get(0))
                                        .getSource())
                        .getData());
    }

    @Test
    void jsonFileStoreKeepsBase64OutOfAgentStateFile(@TempDir Path tempDir) throws Exception {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        AgentState original = stateWithImage("session-1", PNG_DATA);

        store.saveAgentState(null, "session-1", original);

        Path sessionDir = store.getSessionDir(null, "session-1");
        String stateJson =
                Files.readString(
                        sessionDir.resolve(AgentStateStore.AGENT_STATE_KEY + ".json"),
                        StandardCharsets.UTF_8);
        assertFalse(stateJson.contains(PNG_DATA));
        try (Stream<Path> files = Files.list(sessionDir)) {
            assertTrue(
                    files.anyMatch(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .startsWith(AgentStateStore.IMAGE_PAYLOAD_KEY_PREFIX)));
        }
        assertEquals(json(original), json(store.getAgentState(null, "session-1").orElseThrow()));
    }

    @Test
    void imageAwareSavePreservesAgentStateCasVersioning() throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        AgentState first = stateWithImage("session-1", PNG_DATA);
        AgentState stale = stateWithImage("session-1", "c3RhbGUtaW1hZ2U=");

        long version = store.saveAgentStateIfVersion(null, "session-1", first, 0L);
        long conflict = store.saveAgentStateIfVersion(null, "session-1", stale, 0L);

        assertEquals(1L, version);
        assertEquals(AgentStateStore.UNVERSIONED, conflict);
        VersionedState<AgentState> loaded =
                store.getAgentStateVersioned(null, "session-1", AgentStateLoadMode.FULL);
        assertEquals(1L, loaded.version());
        assertEquals(json(first), json(loaded.value()));
    }

    @Test
    void fullLoadFailsForMissingPayloadWhileLeanLoadStillWorks() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        store.saveAgentState(null, "session-1", stateWithImage("session-1", PNG_DATA));
        AgentState lean =
                store.getAgentState(null, "session-1", AgentStateLoadMode.LEAN).orElseThrow();
        String payloadId =
                ImagePayloadTransformer.referencedPayloadIds(lean.getContext()).iterator().next();

        store.delete(null, "session-1", AgentStateStore.IMAGE_PAYLOAD_KEY_PREFIX + payloadId);

        assertTrue(store.getAgentState(null, "session-1", AgentStateLoadMode.LEAN).isPresent());
        assertThrows(
                IllegalStateException.class,
                () -> store.getAgentState(null, "session-1", AgentStateLoadMode.FULL));
    }

    @Test
    void laterPayloadFailureDoesNotPublishLightweightAgentState() {
        FailingPayloadStore store = new FailingPayloadStore(2);
        AgentState state = stateWithImages("session-1", PNG_DATA, "c2Vjb25kLWltYWdl");

        assertThrows(
                IllegalStateException.class, () -> store.saveAgentState(null, "session-1", state));
        assertTrue(
                store.get(null, "session-1", AgentStateStore.AGENT_STATE_KEY, AgentState.class)
                        .isEmpty());
    }

    @Test
    void mainStateFailureLeavesNoPublishedLightweightState() {
        FailingAgentStateStore store = new FailingAgentStateStore();

        assertThrows(
                IllegalStateException.class,
                () ->
                        store.saveAgentState(
                                null, "session-1", stateWithImage("session-1", PNG_DATA)));
        assertTrue(
                store.get(null, "session-1", AgentStateStore.AGENT_STATE_KEY, AgentState.class)
                        .isEmpty());
    }

    @Test
    void rejectsConflictingPayloadAlreadyStoredUnderContentAddress() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        AgentState state = stateWithImage("session-1", PNG_DATA);
        ImagePayloadTransformer.OffloadResult offloaded =
                ImagePayloadTransformer.offload(state.getContext());
        String payloadId = offloaded.payloads().keySet().iterator().next();
        store.save(
                null,
                "session-1",
                AgentStateStore.IMAGE_PAYLOAD_KEY_PREFIX + payloadId,
                new ImagePayloadState("image/png", "dGFtcGVyZWQ="));

        assertThrows(
                IllegalStateException.class, () -> store.saveAgentState(null, "session-1", state));
        assertTrue(
                store.get(null, "session-1", AgentStateStore.AGENT_STATE_KEY, AgentState.class)
                        .isEmpty());
    }

    @Test
    void deletingSessionRemovesStateAndImagePayloads() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        store.saveAgentState(null, "session-1", stateWithImage("session-1", PNG_DATA));

        store.delete(null, "session-1");

        assertFalse(store.exists(null, "session-1"));
        assertTrue(store.getAgentState(null, "session-1").isEmpty());
    }

    @Test
    void fullLoadKeepsLegacyInlineAgentStateCompatible() throws Exception {
        CountingStore store = new CountingStore();
        AgentState legacy = stateWithImage("session-1", PNG_DATA);
        store.save(null, "session-1", AgentStateStore.AGENT_STATE_KEY, legacy);
        store.resetPayloadReads();

        AgentState loaded = store.getAgentState(null, "session-1").orElseThrow();

        assertEquals(json(legacy), json(loaded));
        assertEquals(0, store.payloadReads());
    }

    private AgentState stateWithImage(String sessionId, String data) {
        return stateWithImages(sessionId, data);
    }

    private AgentState stateWithImages(String sessionId, String... data) {
        UserMessage message =
                UserMessage.builder()
                        .id("message-1")
                        .name("alice")
                        .content(
                                java.util.Arrays.stream(data)
                                        .map(AgentStateStoreImagePayloadTest::image)
                                        .map(block -> (ContentBlock) block)
                                        .toList())
                        .timestamp("2026-08-27 16:00:00.000")
                        .build();
        return AgentState.builder()
                .sessionId(sessionId)
                .userId("alice")
                .summary("summary")
                .context(List.of(message))
                .replyId("reply-1")
                .curIter(3)
                .shutdownInterrupted(true)
                .build();
    }

    private static ImageBlock image(String data) {
        return ImageBlock.builder()
                .source(new Base64Source("image/png", data))
                .minPixels(64)
                .maxPixels(4096)
                .build();
    }

    private String json(AgentState state) throws IOException {
        return mapper.readTree(mapper.writeValueAsString(state)).toString();
    }

    private static class CountingStore extends InMemoryAgentStateStore {
        private int payloadReads;

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            if (key.startsWith(AgentStateStore.IMAGE_PAYLOAD_KEY_PREFIX)) {
                payloadReads++;
            }
            return super.get(userId, sessionId, key, type);
        }

        int payloadReads() {
            return payloadReads;
        }

        void resetPayloadReads() {
            payloadReads = 0;
        }
    }

    private static final class FailingPayloadStore extends InMemoryAgentStateStore {
        private final int failAtWrite;
        private int payloadWrites;

        private FailingPayloadStore(int failAtWrite) {
            this.failAtWrite = failAtWrite;
        }

        @Override
        public long saveIfVersion(
                String userId, String sessionId, String key, State value, long expectedVersion) {
            if (key.startsWith(AgentStateStore.IMAGE_PAYLOAD_KEY_PREFIX)
                    && ++payloadWrites == failAtWrite) {
                throw new IllegalStateException("payload store unavailable");
            }
            return super.saveIfVersion(userId, sessionId, key, value, expectedVersion);
        }
    }

    private static final class FailingAgentStateStore extends InMemoryAgentStateStore {
        @Override
        public long saveIfVersion(
                String userId, String sessionId, String key, State value, long expectedVersion) {
            if (AgentStateStore.AGENT_STATE_KEY.equals(key)) {
                throw new IllegalStateException("agent state store unavailable");
            }
            return super.saveIfVersion(userId, sessionId, key, value, expectedVersion);
        }
    }
}
