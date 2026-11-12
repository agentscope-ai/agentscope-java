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

import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Replay only bounded Session creation requests already protected by backend idempotency. */
@Component
public final class SessionCreateRetryFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(SessionCreateRetryFilter.class);
    private static final int MAX_REPLAY_BYTES = 2 * 1024 * 1024;
    private static final Set<String> CREATE_PATHS =
            Set.of("/v1/sessions", "/api/v1/agent-sessions");

    @Override
    public int getOrder() {
        // Inside NettyWriteResponseFilter: retry only before any downstream response is written.
        return 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!eligible(exchange)) return chain.filter(exchange);
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_REPLAY_BYTES)
                .map(
                        buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> forward(exchange, chain, bytes));
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, byte[] bytes) {
        var request =
                new ServerHttpRequestDecorator(exchange.getRequest()) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        return Flux.defer(
                                () ->
                                        Flux.just(
                                                exchange.getResponse()
                                                        .bufferFactory()
                                                        .wrap(bytes)));
                    }
                };
        var replay = exchange.mutate().request(request).build();
        return Mono.defer(() -> chain.filter(replay))
                .onErrorResume(error -> retryOnce(replay, chain, error));
    }

    private Mono<Void> retryOnce(
            ServerWebExchange exchange, GatewayFilterChain chain, Throwable error) {
        if (exchange.getResponse().isCommitted()
                || !GatewayUpstreamExceptionHandler.disconnected(error)) return Mono.error(error);
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        log.warn(
                "gateway.upstream.retry route={} request_id={} attempt=2 cause={}",
                route.getId(),
                exchange.getRequest().getId(),
                error.getClass().getSimpleName());
        ServerWebExchangeUtils.reset(exchange);
        return Mono.delay(Duration.ofMillis(100)).then(Mono.defer(() -> chain.filter(exchange)));
    }

    private static boolean eligible(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null
                || !"agent-session-lifecycle".equals(route.getId())
                || request.getMethod() != HttpMethod.POST
                || !CREATE_PATHS.contains(request.getPath().pathWithinApplication().value()))
            return false;
        var keys = request.getHeaders().get("Idempotency-Key");
        long length = request.getHeaders().getContentLength();
        // Unknown/chunked or large bodies keep their streaming behavior and are never cached.
        return keys != null
                && keys.size() == 1
                && !keys.get(0).isBlank()
                && keys.get(0).length() <= 256
                && length >= 0
                && length <= MAX_REPLAY_BYTES;
    }
}
