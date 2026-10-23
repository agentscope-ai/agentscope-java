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
package io.agentscope.claw2.web.catalog;

import io.agentscope.claw2.web.catalog.AgentCatalogService.AgentCreateRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST controller for the local agent catalog.
 *
 * <ul>
 *   <li>{@code GET /api/agents} — list every agent (built-in + custom)
 *   <li>{@code GET /api/agents/{id}} — get a single definition
 *   <li>{@code POST /api/agents} — create a custom agent
 *   <li>{@code PUT /api/agents/{id}} — update a custom agent
 *   <li>{@code DELETE /api/agents/{id}} — delete a custom agent
 * </ul>
 */
@RestController
@RequestMapping("/api/agents")
public class AgentCatalogController {

    private final AgentCatalogService catalogService;

    public AgentCatalogController(AgentCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public Mono<List<AgentDefinition>> listAgents() {
        return Mono.fromCallable(catalogService::list);
    }

    @GetMapping("/{id}")
    public Mono<AgentDefinition> getAgent(@PathVariable String id) {
        return Mono.fromCallable(
                () ->
                        catalogService
                                .find(id)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Agent not found: " + id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AgentDefinition> createAgent(@RequestBody AgentCreateRequest req) {
        return Mono.fromCallable(() -> catalogService.createAgent(req));
    }

    @PutMapping("/{id}")
    public Mono<AgentDefinition> updateAgent(
            @PathVariable String id, @RequestBody AgentCreateRequest req) {
        return Mono.fromCallable(() -> catalogService.updateAgent(id, req));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteAgent(@PathVariable String id) {
        return Mono.fromRunnable(() -> catalogService.deleteAgent(id));
    }
}
