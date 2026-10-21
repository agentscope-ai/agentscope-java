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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedHandsEventChannelsTest {
    @TempDir Path blobs;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMisroutedProducerEventCannotCommitOrAcknowledge(boolean toolResult) {
        var bus = mock(ManagedMessageBus.class);
        var journal = mock(ManagedJournalStore.class);
        var codec = new ManagedBatchCodec(new ObjectMapper(), blobs);
        var scope = new ManagedScope("tenant", "namespace", "session");
        var message =
                toolResult
                        ? codec.encode(
                                new ManagedToolResult(
                                        scope, "task", "execution", "succeeded", "{}"))
                        : codec.encode(
                                new ManagedToolCommand(
                                        scope,
                                        "command",
                                        "execute",
                                        new ManagedJournalStore.ToolTask(
                                                "task", "turn", "call", "task", "fs.read", "{}", 0,
                                                "ready", null, null, null),
                                        null));
        var wrong =
                new ManagedPushDelivery(
                        "delivery",
                        "consumer",
                        toolResult ? Channel.TURNS : Channel.TOOL_EVENTS,
                        "topic",
                        "group",
                        "broker",
                        1,
                        message);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagedBrainToolEvents(codec, journal, r -> {}).consume(wrong));
        verifyNoInteractions(bus, journal);
    }

    @Test
    void toolEventsRequireTheOperationMessageGroup() {
        var codec = new ManagedBatchCodec(new ObjectMapper(), blobs);
        var scope = new ManagedScope("tenant", "namespace", "session");
        var event =
                new ManagedToolEvent(
                        scope,
                        "task",
                        "execution",
                        1,
                        "tool.progress",
                        Map.of("status", "running"));
        var encoded = codec.encode(event);
        assertEquals(event, codec.decodeToolEvent(encoded));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        codec.decodeToolEvent(
                                new ManagedMessageBus.Message(
                                        encoded.id(), scope.messageGroup(), encoded.body())));
    }
}
