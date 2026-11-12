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
package io.agentcore.managed.server.agentservice.api;

import io.agentcore.managed.server.agentservice.session.AgentServiceWebhooks;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping({"/v1/sessions/{session}/webhooks", "/api/v1/agent-sessions/{session}/webhooks"})
public class AgentServiceWebhooksController {
    private final AgentServiceWebhooks webhooks;

    public AgentServiceWebhooksController(AgentServiceWebhooks webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> register(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AgentServiceWebhooks.Registration request) {
        return Mono.fromCallable(() -> webhooks.register(session, headers, key, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @PathVariable String session,
            @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit) {
        return Mono.fromCallable(() -> webhooks.list(session, headers, after, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> webhooks.update(session, id, headers, true))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/retry")
    public Mono<Map<String, Object>> retry(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers) {
        return Mono.fromCallable(() -> webhooks.update(session, id, headers, false))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/deliveries")
    public Mono<Map<String, Object>> deliveries(
            @PathVariable String session,
            @PathVariable String id,
            @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit) {
        return Mono.fromCallable(() -> webhooks.receipts(session, id, headers, after, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
