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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Tests for {@link EventStreamingAgent}. */
class EventStreamingAgentTest {

    @Test
    void convenienceOverloadsDelegateToListAndContext() {
        RecordingAgent agent = new RecordingAgent();
        Msg message = new UserMessage("hello");
        RuntimeContext context = RuntimeContext.builder().sessionId("thread-1").build();

        Flux<AgentEvent> singleWithContext = agent.streamEvents(message, context);
        assertSame(agent.events, singleWithContext);
        assertEquals(List.of(message), agent.lastMessages);
        assertSame(context, agent.lastContext);

        Flux<AgentEvent> listWithoutContext = agent.streamEvents(List.of(message));
        assertSame(agent.events, listWithoutContext);
        assertEquals(List.of(message), agent.lastMessages);
        assertNull(agent.lastContext);

        Flux<AgentEvent> singleWithoutContext = agent.streamEvents(message);
        assertSame(agent.events, singleWithoutContext);
        assertEquals(List.of(message), agent.lastMessages);
        assertNull(agent.lastContext);
    }

    @Test
    void reActAgentImplementsEventStreamingAgent() {
        assertTrue(EventStreamingAgent.class.isAssignableFrom(ReActAgent.class));
    }

    private static final class RecordingAgent implements EventStreamingAgent {

        private final Flux<AgentEvent> events = Flux.empty();
        private List<Msg> lastMessages;
        private RuntimeContext lastContext;

        @Override
        public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context) {
            lastMessages = msgs;
            lastContext = context;
            return events;
        }
    }
}
