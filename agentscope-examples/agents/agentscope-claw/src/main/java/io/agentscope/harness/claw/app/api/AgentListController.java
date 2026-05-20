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
package io.agentscope.harness.claw.app.api;

import io.agentscope.harness.claw.ClawBootstrap;
import io.agentscope.harness.claw.config.AgentConfigEntry;
import io.agentscope.harness.claw.config.AgentscopeConfig;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Returns metadata for the single product agent (DataAgent). The chat UI no longer carries an
 * agent picker — this endpoint exists for the UI to render the agent badge / about page.
 *
 * <ul>
 *   <li>{@code GET /api/me/agent-info} — id, name, description, maxIters of the bundled agent
 * </ul>
 */
@RestController
@RequestMapping("/api/me")
public class AgentListController {

    private final ClawBootstrap clawBootstrap;

    public AgentListController(ClawBootstrap clawBootstrap) {
        this.clawBootstrap = clawBootstrap;
    }

    @GetMapping("/agent-info")
    public Mono<AgentView> agentInfo() {
        return Mono.fromCallable(
                () -> {
                    String id = clawBootstrap.mainAgentId();
                    AgentscopeConfig cfg = clawBootstrap.loadedConfig();
                    Map<String, AgentConfigEntry> entries =
                            cfg != null && cfg.getAgents() != null ? cfg.getAgents() : Map.of();
                    AgentConfigEntry e = entries.get(id);
                    String name = e != null && e.getName() != null ? e.getName() : id;
                    String description = e != null ? e.getDescription() : null;
                    Integer maxIters = e != null ? e.getMaxIters() : null;
                    return new AgentView(id, name, description, maxIters);
                });
    }

    /** Public view of the single product agent. No system prompt or tool list is exposed. */
    public record AgentView(String id, String name, String description, Integer maxIters) {}
}
