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

package io.agentscope.core.a2a.server.registry;

import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent registry service.
 *
 * <p>Do Register operation when A2A server is ready.
 */
public class AgentRegistryService {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistryService.class);

    private final List<AgentRegistry> agentRegistries;

    public AgentRegistryService(List<AgentRegistry> agentRegistries) {
        this.agentRegistries = agentRegistries;
    }

    /**
     * Traversal all agent registries and do register operation.
     *
     * @param agentCard             agent card to be registered
     * @param transportProperties   properties for transport A2A Server supported and exported
     */
    public void register(AgentCard agentCard, Set<TransportProperties> transportProperties) {
        if (null == agentCard || null == transportProperties || transportProperties.isEmpty()) {
            return;
        }
        log.info("Start to auto register agent {} into Registries.", agentCard.name());
        List<TransportProperties> transportPropertiesList = transportProperties.stream().toList();
        agentRegistries.forEach(
                agentRegistry -> register(agentCard, transportPropertiesList, agentRegistry));
    }

    private void register(
            AgentCard agentCard,
            List<TransportProperties> transportProperties,
            AgentRegistry agentRegistry) {
        log.info(
                "Auto register agent {} into Registry {}.",
                agentCard.name(),
                agentRegistry.registryName());
        try {
            agentRegistry.register(agentCard, transportProperties);
            log.info(
                    "Auto register agent {} into Registry {} successfully.",
                    agentCard.name(),
                    agentRegistry.registryName());
        } catch (Exception e) {
            log.error(
                    "Auto register agent {} into Registry {} failed.",
                    agentCard.name(),
                    agentRegistry.registryName(),
                    e);
        }
    }
}
