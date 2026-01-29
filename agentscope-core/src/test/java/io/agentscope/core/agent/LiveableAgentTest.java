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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.LiveEventType;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@DisplayName("LiveableAgent Interface Tests")
class LiveableAgentTest {

    @Test
    @DisplayName("Should implement live method with config")
    void shouldImplementLiveMethodWithConfig() {
        // Create a simple implementation
        LiveableAgent agent = new TestLiveableAgent();

        Flux<Msg> input =
                Flux.just(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        StepVerifier.create(agent.live(input, LiveConfig.defaults()))
                .expectNextMatches(event -> event.type() == LiveEventType.SESSION_CREATED)
                .expectNextMatches(event -> event.type() == LiveEventType.TEXT_DELTA)
                .expectNextMatches(event -> event.type() == LiveEventType.TURN_COMPLETE)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should use default config when not specified")
    void shouldUseDefaultConfigWhenNotSpecified() {
        LiveableAgent agent = new TestLiveableAgent();

        Flux<Msg> input = Flux.empty();

        // Use method with default config
        Flux<LiveEvent> result = agent.live(input);

        assertNotNull(result);
    }

    @Test
    @DisplayName("Should handle empty input stream")
    void shouldHandleEmptyInputStream() {
        LiveableAgent agent = new TestLiveableAgent();

        Flux<Msg> input = Flux.empty();

        StepVerifier.create(agent.live(input, LiveConfig.defaults()))
                .expectNextMatches(event -> event.type() == LiveEventType.SESSION_CREATED)
                .expectNextMatches(event -> event.type() == LiveEventType.TURN_COMPLETE)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle multiple messages in input stream")
    void shouldHandleMultipleMessagesInInputStream() {
        LiveableAgent agent = new TestLiveableAgent();

        Flux<Msg> input =
                Flux.just(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("World").build())
                                .build());

        StepVerifier.create(agent.live(input, LiveConfig.defaults()))
                .expectNextMatches(event -> event.type() == LiveEventType.SESSION_CREATED)
                .expectNextMatches(event -> event.type() == LiveEventType.TEXT_DELTA)
                .expectNextMatches(event -> event.type() == LiveEventType.TEXT_DELTA)
                .expectNextMatches(event -> event.type() == LiveEventType.TURN_COMPLETE)
                .verifyComplete();
    }

    /** Simple test implementation of LiveableAgent. */
    static class TestLiveableAgent implements LiveableAgent {
        @Override
        public Flux<LiveEvent> live(Flux<Msg> input, LiveConfig config) {
            return Flux.concat(
                    Flux.just(LiveEvent.sessionCreated(null)),
                    input.map(msg -> LiveEvent.textDelta(msg, false)),
                    Flux.just(LiveEvent.turnComplete(null)));
        }
    }
}
