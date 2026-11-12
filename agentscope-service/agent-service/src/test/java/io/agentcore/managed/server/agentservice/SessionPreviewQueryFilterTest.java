/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.config.SessionApiErrors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SessionPreviewQueryFilterTest {
    private final SessionApiErrors filter = new SessionApiErrors(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/v1/sessions/session/events/stream?beta=true&event_deltas[]=agent.message",
                "/v1/sessions/session/events/stream?event_deltas[]=agent.message&event_deltas[]=agent.message",
                "/v1/sessions/session/events/stream"
            })
    void livePreviewOptionsReachTheAuthorizedStreamHandler(String uri) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(uri));
        var forwarded = new AtomicBoolean();
        StepVerifier.create(
                        filter.filter(
                                exchange,
                                request -> {
                                    forwarded.set(true);
                                    return Mono.empty();
                                }))
                .verifyComplete();
        assertThat(forwarded).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/v1/sessions/session/events/stream?after=cursor",
                "/v1/sessions/session/events/stream?beta=false&event_deltas[]=agent.message",
                "/v1/sessions/session/events/stream?beta=true&beta=true",
                "/v1/sessions/session/events?event_deltas[]=agent.message"
            })
    void unsupportedResumeAndNonStreamPreviewOptionsRemainRejected(String uri) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(uri));
        var forwarded = new AtomicBoolean();
        StepVerifier.create(
                        filter.filter(
                                exchange,
                                request -> {
                                    forwarded.set(true);
                                    return Mono.empty();
                                }))
                .verifyComplete();
        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
