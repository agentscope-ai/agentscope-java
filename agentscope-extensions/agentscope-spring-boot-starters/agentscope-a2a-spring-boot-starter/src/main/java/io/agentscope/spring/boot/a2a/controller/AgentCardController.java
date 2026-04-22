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

package io.agentscope.spring.boot.a2a.controller;

import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
public class AgentCardController {

    private final AgentScopeA2aServer agentScopeA2aServer;

    public AgentCardController(AgentScopeA2aServer agentScopeA2aServer) {
        this.agentScopeA2aServer = agentScopeA2aServer;
    }

    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentCard getAgentCard() {
        return agentScopeA2aServer.getAgentCard();
    }
}
