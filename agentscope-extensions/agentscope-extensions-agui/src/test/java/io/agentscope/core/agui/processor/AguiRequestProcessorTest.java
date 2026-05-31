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
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AguiRequestProcessor}. */
class AguiRequestProcessorTest {

    @Test
    void testMessagesSnapshotStream() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(new SnapshotResolver())
                        .snapshotProvider(new SnapshotProvider())
                        .build();
        RunAgentInput input = RunAgentInput.builder().threadId("thread-1").runId("run-1").build();

        List<AguiEvent> events =
                processor.messagesSnapshot(input, null, null).collectList().block();

        assertEquals(3, events.size());
        assertEquals(AguiEventType.RUN_STARTED, events.get(0).getType());
        assertEquals(AguiEventType.MESSAGES_SNAPSHOT, events.get(1).getType());
        assertEquals(AguiEventType.RUN_FINISHED, events.get(2).getType());

        AguiEvent.MessagesSnapshot snapshot =
                assertInstanceOf(AguiEvent.MessagesSnapshot.class, events.get(1));
        assertEquals("thread-1", snapshot.getThreadId());
        assertEquals("run-1", snapshot.getRunId());
        assertEquals(1, snapshot.messages().size());
        assertEquals("Hello", snapshot.messages().get(0).getContent());
    }

    private static class SnapshotResolver implements AgentResolver {

        @Override
        public Agent resolveAgent(String agentId, String threadId) {
            throw new UnsupportedOperationException("Not used by snapshot requests");
        }

        @Override
        public boolean hasMemory(String threadId) {
            return true;
        }
    }

    private static class SnapshotProvider implements AguiSnapshotProvider {

        @Override
        public List<AguiMessage> messagesSnapshot(AguiSnapshotRequest request) {
            return List.of(AguiMessage.userMessage("msg-1", "Hello"));
        }
    }
}
