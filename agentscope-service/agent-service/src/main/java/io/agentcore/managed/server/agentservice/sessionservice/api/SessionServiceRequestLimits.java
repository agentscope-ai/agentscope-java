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
package io.agentcore.managed.server.agentservice.sessionservice.api;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Enforce limits while reading, including chunked bodies, before the JSON decoder buffers them. */
@Component
@Order(-50)
public final class SessionServiceRequestLimits implements WebFilter {
    private final long publicLimit;

    public SessionServiceRequestLimits(
            @Value("${MANAGED_AGENTS_AGENT_SERVICE_MAX_REQUEST_SIZE:2MB}") DataSize limit) {
        publicLimit = limit.toBytes();
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean internal = path.startsWith("/internal/session-service/v0/");
        if (!internal
                && !MediaType.APPLICATION_JSON.isCompatibleWith(
                        exchange.getRequest().getHeaders().getContentType()))
            return chain.filter(exchange);
        long limit =
                internal
                        ? (path.endsWith("/objects/write")
                                        || path.endsWith("/prepared/objects")
                                        || path.endsWith("/prepared/commits")
                                ? 90L * 1024 * 1024
                                // resolve-input forwards an accepted public payload plus its query
                                // envelope.
                                : path.endsWith("/prepared/query")
                                        ? Math.max(publicLimit, 1024L * 1024) + 64 * 1024
                                        : 1024 * 1024)
                        : publicLimit;
        if (exchange.getRequest().getHeaders().getContentLength() > limit)
            return Mono.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE));
        var request =
                new ServerHttpRequestDecorator(exchange.getRequest()) {
                    public Flux<DataBuffer> getBody() {
                        var size = new AtomicLong();
                        return super.getBody()
                                .<DataBuffer>handle(
                                        (buffer, sink) -> {
                                            if (size.addAndGet(buffer.readableByteCount())
                                                    > limit) {
                                                DataBufferUtils.release(buffer);
                                                sink.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.PAYLOAD_TOO_LARGE));
                                            } else sink.next(buffer);
                                        })
                                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
                    }
                };
        return chain.filter(exchange.mutate().request(request).build());
    }
}
