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

import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AgentTest {

    @Test
    void getRuntimeContext_defaultsToNull() {
        Agent agent = new BareAgent();

        assertNull(agent.getRuntimeContext());
    }

    private static final class BareAgent implements Agent {

        @Override
        public String getAgentId() {
            return "bare-agent";
        }

        @Override
        public String getName() {
            return "bare-agent";
        }

        @Override
        public void interrupt() {}

        @Override
        public void interrupt(Msg msg) {}

        @Override
        public Mono<Msg> call(List<Msg> msgs) {
            return Mono.empty();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
            return Mono.empty();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
            return Mono.empty();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
            return Flux.empty();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
            return Flux.empty();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
            return Flux.empty();
        }

        @Override
        public Mono<Void> observe(Msg msg) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> observe(List<Msg> msgs) {
            return Mono.empty();
        }
    }
}
