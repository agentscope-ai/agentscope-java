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
package io.agentscope.builder.web.share;

import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Convenience gate for controllers: resolve an agent by id (across globals, own, shared-in) and
 * verify the caller holds at least the required tier. Keeps the {@code findVisible + can} pattern
 * out of every controller while staying explicit at the call site (no AOP magic).
 */
@Service
public class AgentAccessGuard {

    private final AgentCatalogService catalog;
    private final AgentAclService acl;

    public AgentAccessGuard(AgentCatalogService catalog, AgentAclService acl) {
        this.catalog = catalog;
        this.acl = acl;
    }

    /**
     * Resolves the agent and returns it iff {@code userId} holds at least {@code required}.
     *
     * @throws ResponseStatusException 404 if the agent is invisible to the user, 403 if visible
     *     but below the required tier.
     */
    public AgentDefinition require(String userId, String agentId, Tier required) {
        AgentDefinition def =
                catalog.findVisible(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        if (!acl.can(userId, def, required)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Required tier " + required.name() + " on agent " + agentId);
        }
        return def;
    }

    /** Get the agent without enforcing a tier (404 if invisible). Use for read-only descriptors. */
    public AgentDefinition load(String userId, String agentId) {
        return catalog.findVisible(userId, agentId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
    }
}
