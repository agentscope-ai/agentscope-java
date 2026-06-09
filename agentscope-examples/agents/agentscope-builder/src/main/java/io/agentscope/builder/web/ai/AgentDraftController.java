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
package io.agentscope.builder.web.ai;

import io.agentscope.builder.web.catalog.AgentCatalogService.AgentDraft;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * AI-assisted agent drafting endpoint.
 *
 * <ul>
 *   <li>{@code POST /api/agents/draft} — body {@code {description}}; returns a populated
 *       {@link AgentDraft} the UI can use to seed a creation form. No persistent side effect.
 * </ul>
 *
 * <p>Returns 503 when no {@link io.agentscope.core.model.Model} bean is configured.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentDraftController {

    private final AgentDraftService service;

    public AgentDraftController(AgentDraftService service) {
        this.service = service;
    }

    @PostMapping("/draft")
    public Mono<AgentDraft> draft(@RequestBody DraftRequest req, Authentication auth) {
        if (req == null || req.description() == null || req.description().isBlank()) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "description is required"));
        }
        return service.draft(req.description());
    }

    public record DraftRequest(String description) {}
}
