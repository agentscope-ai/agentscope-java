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
package io.agentscope.builder.hands;

import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Authenticate and bound archive-bearing requests before JSON decoding allocates their bodies. */
@Component
public final class ManagedWorkerRequestLimits implements WebFilter {
    private final ManagedWorkerService authentication;
    private final Semaphore requests = new Semaphore(2);
    private final Semaphore controls = new Semaphore(64);

    public ManagedWorkerRequestLimits(ManagedWorkerService authentication) {
        this.authentication = authentication;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String[] parts = exchange.getRequest().getPath().value().split("/");
        if (parts.length != 7
                || !parts[1].equals("v1")
                || !parts[2].equals("handss")
                || !parts[3].equals("environments")
                || !parts[5].equals("tool-tasks")) return chain.filter(exchange);
        var capacity =
                Set.of("renew", "started", "unknown").contains(parts[6]) ? controls : requests;
        if (!capacity.tryAcquire()) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        return Mono.fromRunnable(
                        () ->
                                authentication.authenticate(
                                        parts[4],
                                        exchange.getRequest()
                                                .getHeaders()
                                                .getFirst(ManagedWorkerService.ENVIRONMENT_KEY)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> chain.filter(exchange)))
                .doFinally(ignored -> capacity.release());
    }
}
