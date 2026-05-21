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
package io.agentscope.harness.claw.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.harness.claw.web.runtime.ClawRuntimeClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Read-only channel observability proxy — delegates to agentscope-claw's runtime endpoint.
 *
 * <ul>
 *   <li>{@code GET /api/admin/channels} — list all channels with current state
 * </ul>
 *
 * <p>Requires {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/channels")
public class ChannelController {

    private final ClawRuntimeClient clawClient;

    public ChannelController(ClawRuntimeClient clawClient) {
        this.clawClient = clawClient;
    }

    @GetMapping
    public Mono<JsonNode> channels(ServerWebExchange exchange) {
        String bearer = ClawRuntimeClient.extractBearer(exchange);
        return Mono.fromCallable(() -> clawClient.get("/api/admin/runtime/channels", bearer));
    }
}
