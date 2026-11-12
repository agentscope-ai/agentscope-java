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
package io.agentcore.managed.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.PooledByteBufAllocator;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SessionCreateRetryFilterTest {
    private final SessionCreateRetryFilter filter = new SessionCreateRetryFilter();

    @Test
    void cancellationDuringBackoffDoesNotReplayAndReleasesTheInboundBuffer() {
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        var buffer = factory.allocateBuffer().write(new byte[] {'{', '}'});
        var exchange =
                routed(
                        MockServerWebExchange.from(
                                MockServerHttpRequest.post("/v1/sessions")
                                        .contentLength(2)
                                        .header("Idempotency-Key", "stable-key")
                                        .body(Flux.just(buffer))));
        var calls = new AtomicInteger();
        StepVerifier.withVirtualTime(
                        () ->
                                filter.filter(
                                        exchange,
                                        ignored -> {
                                            calls.incrementAndGet();
                                            return Mono.error(new IOException("connection reset"));
                                        }))
                .thenAwait(Duration.ofMillis(50))
                .thenCancel()
                .verify();
        assertThat(calls).hasValue(1);
        assertThat(buffer.getNativeBuffer().refCnt()).isZero();
    }

    @Test
    void ambiguousOrInvalidKeysAreNeverReplayed() {
        for (List<String> keys :
                List.of(List.of(" "), List.of("x".repeat(257)), List.of("one", "two"))) {
            var exchange =
                    routed(
                            MockServerWebExchange.from(
                                    MockServerHttpRequest.post("/v1/sessions")
                                            .contentLength(2)
                                            .header("Idempotency-Key", keys.toArray(String[]::new))
                                            .body("{}")));
            var calls = new AtomicInteger();
            var error = new IOException("connection reset");
            StepVerifier.create(
                            filter.filter(
                                    exchange,
                                    ignored -> {
                                        calls.incrementAndGet();
                                        return Mono.error(error);
                                    }))
                    .expectErrorMatches(actual -> actual == error)
                    .verify();
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void slowRequestsTimingOutAreNotReplayed() {
        var exchange =
                routed(
                        MockServerWebExchange.from(
                                MockServerHttpRequest.post("/v1/sessions")
                                        .contentLength(2)
                                        .header("Idempotency-Key", "stable-key")
                                        .body("{}")));
        var calls = new AtomicInteger();
        var error = new TimeoutException("response deadline");
        StepVerifier.create(
                        filter.filter(
                                exchange,
                                ignored -> {
                                    calls.incrementAndGet();
                                    return Mono.error(error);
                                }))
                .expectErrorMatches(actual -> actual == error)
                .verify();
        assertThat(calls).hasValue(1);
    }

    private static MockServerWebExchange routed(MockServerWebExchange exchange) {
        exchange.getAttributes()
                .put(
                        ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                        GatewayUpstreamExceptionHandlerTest.exchange()
                                .getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR));
        return exchange;
    }
}
