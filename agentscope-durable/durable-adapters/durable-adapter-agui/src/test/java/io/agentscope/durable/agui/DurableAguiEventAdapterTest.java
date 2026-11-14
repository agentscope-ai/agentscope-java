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
package io.agentscope.durable.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.SessionStreamEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DurableAguiEventAdapterTest {
    private final SessionKey session = new SessionKey("tenant", "session");
    private final DurableAguiEventAdapter adapter = new DurableAguiEventAdapter();

    @Test
    void liveTextAndCommittedMessageFormOneAguiMessageLifecycle() {
        var start = adapter.map(started("message-1"));
        var delta = adapter.map(delta("message-1", "hello"));
        var completed =
                adapter.map(
                        new SessionStreamEvent.Committed(completed("message-1", "hello world")));

        assertInstanceOf(AguiEvent.TextMessageStart.class, start.get(0));
        assertEquals("hello", ((AguiEvent.TextMessageContent) delta.get(0)).delta());
        assertEquals(2, completed.size());
        assertEquals(" world", ((AguiEvent.TextMessageContent) completed.get(0)).delta());
        assertInstanceOf(AguiEvent.TextMessageEnd.class, completed.get(1));
    }

    @Test
    void committedMessageWithoutLivePreviewStillFormsACompleteLifecycle() {
        var events = adapter.committed(completed("message-2", "complete"));
        assertInstanceOf(AguiEvent.TextMessageStart.class, events.get(0));
        assertEquals("complete", ((AguiEvent.TextMessageContent) events.get(1)).delta());
        assertInstanceOf(AguiEvent.TextMessageEnd.class, events.get(2));
    }

    private SessionStreamEvent.PreviewStarted started(String id) {
        return new SessionStreamEvent.PreviewStarted(
                id,
                EventDeltaType.MESSAGE,
                "main",
                "turn",
                "run",
                "step",
                DurableJson.value(Map.of("messageId", id)));
    }

    private SessionStreamEvent.EventDelta delta(String id, String text) {
        return new SessionStreamEvent.EventDelta(
                id,
                EventDeltaType.MESSAGE,
                "text-1",
                "main",
                "turn",
                "run",
                "step",
                DurableJson.value(Map.of("messageId", id, "text", text)));
    }

    private PublicEvent completed(String id, String text) {
        return new PublicEvent(
                "1",
                "event",
                session,
                "main",
                "turn",
                "run",
                "step",
                "1",
                "cursor",
                "message.completed",
                DurableJson.value(
                        Map.of(
                                "messageId",
                                id,
                                "attempt",
                                1,
                                "revision",
                                0,
                                "text",
                                text,
                                "message",
                                Map.of(
                                        "schemaVersion",
                                        "1",
                                        "messageId",
                                        id,
                                        "role",
                                        "assistant",
                                        "content",
                                        List.of(Map.of("type", "text", "text", text))))));
    }
}
