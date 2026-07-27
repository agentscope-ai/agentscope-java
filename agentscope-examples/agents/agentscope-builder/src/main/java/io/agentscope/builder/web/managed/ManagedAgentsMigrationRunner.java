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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.persistence.jpa.AgentEntity;
import io.agentscope.builder.web.persistence.jpa.AgentEntityRepository;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot migration for Managed Agents alignment: ensures every existing agent has a version-1
 * snapshot and each owner has a default local environment.
 */
@Component
@Order(100)
public class ManagedAgentsMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ManagedAgentsMigrationRunner.class);

    private final AgentEntityRepository agentRepository;
    private final AgentVersionEntityRepository versionRepository;
    private final AgentVersionService versionService;
    private final EnvironmentService environmentService;
    private final AgentCatalogService catalogService;

    public ManagedAgentsMigrationRunner(
            AgentEntityRepository agentRepository,
            AgentVersionEntityRepository versionRepository,
            AgentVersionService versionService,
            EnvironmentService environmentService,
            AgentCatalogService catalogService) {
        this.agentRepository = agentRepository;
        this.versionRepository = versionRepository;
        this.versionService = versionService;
        this.environmentService = environmentService;
        this.catalogService = catalogService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        catalogService.ensureGlobalVersions();
        int agentsMigrated = 0;
        for (AgentEntity agent : agentRepository.findAll()) {
            if (agent.getHeadVersion() <= 0) {
                agent.setHeadVersion(1);
                agentRepository.save(agent);
            }
            boolean hasVersion =
                    versionRepository
                            .findByOwnerIdAndAgentIdAndVersion(
                                    agent.getOwnerId(), agent.getAgentId(), 1)
                            .isPresent();
            if (!hasVersion) {
                versionService.createInitialVersion(
                        agent.getOwnerId(),
                        agent.getAgentId(),
                        versionService.snapshotFromEntity(agent));
                agentsMigrated++;
            }
            environmentService.ensureDefaultEnvironment(agent.getOwnerId(), "local");
        }
        if (agentsMigrated > 0) {
            log.info(
                    "Managed Agents migration: created initial version snapshots for {} agents",
                    agentsMigrated);
        }
    }
}
