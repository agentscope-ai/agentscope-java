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

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

class GatewayUpstreamExceptionHandlerTest {
    private final GatewayUpstreamExceptionHandler handler = new GatewayUpstreamExceptionHandler();

    @Test
    void timeoutIsReportedAs504AndIsNotAutomaticallyRetried() {
        var exchange = exchange();
        var error = new IllegalStateException(new TimeoutException("response deadline"));
        StepVerifier.create(handler.handle(exchange, error)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("upstream_timeout");
        assertThat(GatewayUpstreamExceptionHandler.disconnected(error)).isFalse();
    }

    @Test
    void failedUpstreamResponseHeadersDoNotCorruptTheJsonErrorBody() {
        var exchange = exchange();
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_ENCODING, "gzip");
        exchange.getResponse().getHeaders().setContentLength(999);
        exchange.getAttributes()
                .put(
                        ServerWebExchangeUtils.CLIENT_RESPONSE_HEADER_NAMES,
                        Set.of(HttpHeaders.CONTENT_ENCODING, HttpHeaders.CONTENT_LENGTH));
        StepVerifier.create(handler.handle(exchange, new IOException("closed before body")))
                .verifyComplete();
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING))
                .isNull();
        assertThat(exchange.getResponse().getHeaders().getContentLength()).isNotEqualTo(999);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("upstream_unavailable");
    }

    @Test
    void committedSseResponseIsNotReplacedWithJson() {
        var exchange = exchange();
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().setComplete().block();
        var error = new IOException("stream closed");
        StepVerifier.create(handler.handle(exchange, error))
                .expectErrorMatches(actual -> actual == error)
                .verify();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void localApplicationErrorsAndUnroutedRequestsKeepTheirOriginalHandling() {
        var error = new IllegalStateException("local error");
        StepVerifier.create(handler.handle(exchange(), error))
                .expectErrorMatches(actual -> actual == error)
                .verify();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
        var connectionError = new IOException("local I/O");
        StepVerifier.create(handler.handle(exchange, connectionError))
                .expectErrorMatches(actual -> actual == connectionError)
                .verify();
    }

    static MockServerWebExchange exchange() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/sessions"));
        exchange.getAttributes()
                .put(
                        ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                        Route.async()
                                .id("agent-session-lifecycle")
                                .uri(URI.create("http://localhost:8084"))
                                .predicate(ignored -> true)
                                .build());
        return exchange;
    }
}
