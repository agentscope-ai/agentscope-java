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

package io.agentscope.durable.codec.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.state.AgentState;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.CheckpointMetadata;
import io.agentscope.durable.DurableTypes.CheckpointView;
import io.agentscope.durable.DurableTypes.CommitReceipt;
import io.agentscope.durable.DurableTypes.Restoration;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.SessionResources;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Backend-only restore coverage: this module has no adapter or Harness dependency. */
class AgentScopeCheckpointsTest {
    private final SessionBackend backend = mock(SessionBackend.class);
    private final SessionKey source = new SessionKey("owner", "source");
    private final ThreadSpec definition = definition(source);
    private final AgentScopeCheckpoints checkpoints = new AgentScopeCheckpoints(backend);

    private ThreadSpec definition(SessionKey key) {
        return new ThreadSpec(
                new ThreadKey(key, "main"),
                new ThreadBinding(
                        "agentscope-java", "v1", AgentScopeCheckpointCodec.STATE_VERSION));
    }

    private void snapshot(boolean settled, long sequence) {
        var checkpoint =
                DurableJson.value(Map.of("format", AgentScopeCheckpointCodec.STATE_VERSION));
        var metadata =
                new CheckpointMetadata(
                        "1",
                        definition.harness(),
                        "v1",
                        definition.stateVersion(),
                        "7",
                        DurableJson.hash(checkpoint));
        when(backend.checkpoint(new ThreadKey(source, "main"), 7))
                .thenReturn(new CheckpointView(definition, metadata, checkpoint, 1, settled));
        var event =
                new JournalEvent(
                        JournalEvent.SCHEMA_VERSION,
                        "checkpoint",
                        sequence,
                        1,
                        "state/checkpoint",
                        "run",
                        null,
                        true,
                        DurableJson.value(
                                        Map.of("stateJson", AgentState.builder().build().toJson()))
                                .toString());
        when(backend.checkpointJournal(new ThreadKey(source, "main"), 7, 0, 1000))
                .thenReturn(List.of(DurableJson.value(event)));
    }

    @Test
    void forkRebindsIdentityAndTransfersPrivateResourcesWithoutAgent() {
        snapshot(true, 1);
        var resources =
                List.of(
                        new SessionResources.Value(
                                List.of("workspace"),
                                "note",
                                DurableJson.value(Map.of("text", "remember")),
                                1));
        when(backend.checkpointResources(new ThreadKey(source, "main"), 7)).thenReturn(resources);
        var receipt = new CommitReceipt("fork", "digest", 1, "cursor");
        when(backend.restore(any())).thenReturn(receipt);
        var target = definition(new SessionKey("owner", "fork"));
        assertEquals(
                receipt,
                checkpoints.restore(new ThreadKey(source, "main"), 7, target, 3, "fork-request"));
        var captured = ArgumentCaptor.forClass(Restoration.class);
        verify(backend).restore(captured.capture());
        var restoration = captured.getValue();
        assertEquals(3, restoration.expectedPosition());
        assertEquals("fork-request", restoration.requestId());
        assertEquals(target, restoration.target());
        assertEquals(resources, restoration.resources());
        assertEquals(
                AgentScopeCheckpointCodec.STATE_VERSION,
                restoration.checkpoint().path("format").asText());
        var baseline = DurableJson.convert(restoration.journal().get(0), JournalEvent.class);
        assertEquals("state/initialized", baseline.type());
        var state = DurableJson.parse((String) baseline.data().get("stateJson"));
        assertEquals("fork", state.path("session_id").asText());
        assertEquals("owner", state.path("user_id").asText());
        assertEquals("main", state.path("thread_id").asText());
    }

    @Test
    void restoreSameSessionUsesRestoredBaseline() {
        snapshot(true, 1);
        checkpoints.restore(new ThreadKey(source, "main"), 7, definition, 9, "restore-request");
        var captured = ArgumentCaptor.forClass(Restoration.class);
        verify(backend).restore(captured.capture());
        assertEquals(9, captured.getValue().expectedPosition());
        assertEquals(
                "state/restored",
                DurableJson.convert(captured.getValue().journal().get(0), JournalEvent.class)
                        .type());
    }

    @Test
    void rejectsUnsettledCheckpointBeforeWriting() {
        snapshot(false, 1);
        assertEquals(
                DurableException.Code.CONFLICT,
                assertThrows(
                                DurableException.class,
                                () ->
                                        checkpoints.restore(
                                                new ThreadKey(source, "main"),
                                                7,
                                                definition,
                                                0,
                                                "restore"))
                        .code());
        verify(backend, never()).restore(any());
    }

    @Test
    void rejectsOtherNamespaceBeforeWriting() {
        snapshot(true, 1);
        var target = definition(new SessionKey("other", "fork"));
        assertEquals(
                DurableException.Code.INCOMPATIBLE,
                assertThrows(
                                DurableException.class,
                                () ->
                                        checkpoints.restore(
                                                new ThreadKey(source, "main"),
                                                7,
                                                target,
                                                0,
                                                "fork"))
                        .code());
        verify(backend, never()).restore(any());
    }

    @Test
    void rejectsDiscontinuousJournalBeforeWriting() {
        snapshot(true, 2);
        assertEquals(
                DurableException.Code.INVALID,
                assertThrows(
                                DurableException.class,
                                () ->
                                        checkpoints.restore(
                                                new ThreadKey(source, "main"),
                                                7,
                                                definition,
                                                0,
                                                "restore"))
                        .code());
        verify(backend, never()).restore(any());
    }
}
