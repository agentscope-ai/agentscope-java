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

import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Tests for {@link StreamableAgent}. */
class StreamableAgentTest {

    @Test
    void testRuntimeContextStreamDelegatesToLegacyStreamByDefault() {
        TestStreamableAgent agent = new TestStreamableAgent();
        List<Msg> messages = List.of();
        StreamOptions options = StreamOptions.defaults();
        RuntimeContext context = RuntimeContext.builder().sessionId("thread-1").build();

        Flux<Event> events = agent.stream(messages, options, context);

        assertSame(agent.events, events);
        assertSame(messages, agent.lastMessages);
        assertSame(options, agent.lastOptions);
    }

    private static final class TestStreamableAgent implements StreamableAgent {

        private final Flux<Event> events = Flux.empty();
        private List<Msg> lastMessages;
        private StreamOptions lastOptions;

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
            lastMessages = msgs;
            lastOptions = options;
            return events;
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
            throw new UnsupportedOperationException();
        }
    }
}
