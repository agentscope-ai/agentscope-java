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
package io.agentscope.dataagent.web.marketplace;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wires a singleton {@link ContributeWorkspaceTool} onto the built-in {@code data-agent} main
 * agent's toolkit at startup, so the agent can call {@code contribute_to_workspace} to nominate
 * skills / subagents / memory snippets for promotion to the shared workspace.
 *
 * <p>The tool is intentionally registered on the main agent only — user-custom agents do not get
 * it by default. Adding it to every agent is a one-line change here if that becomes the policy.
 *
 * <p>This component runs after {@link DataAgentBootstrap} has built every agent, mirroring the
 * shape of {@code ToolNotificationHook} (configured via {@code configureAllAgents}) but applied
 * post-bootstrap because the tool depends on a Spring-managed {@link MarketContributionService}
 * (JPA repository wiring) that is not safely accessible from the bootstrap builder lambda.
 */
@Component
public class ContributionToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ContributionToolRegistrar.class);

    private final DataAgentBootstrap bootstrap;
    private final MarketContributionService service;

    public ContributionToolRegistrar(
            DataAgentBootstrap bootstrap, MarketContributionService service) {
        this.bootstrap = bootstrap;
        this.service = service;
    }

    @PostConstruct
    public void registerContributionTool() {
        HarnessAgent main = bootstrap.agents().get(bootstrap.loadedConfig().getMain());
        if (main == null) {
            // Resolve through agents().values().iterator().next() as a fallback when no explicit
            // main is configured — DataAgentBootstrap guarantees at least one agent at build time.
            main =
                    bootstrap.agents().values().stream()
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "No agents available to register"
                                                            + " contribute_to_workspace onto"));
        }
        try {
            main.getDelegate().getToolkit().registerTool(new ContributeWorkspaceTool(service));
            log.info(
                    "Registered contribute_to_workspace tool onto main agent '{}'", main.getName());
        } catch (RuntimeException e) {
            // Fail soft: a missing tool slot must not stop the application from booting; the
            // user-facing REST endpoints (/api/me/contributions) remain fully functional even if
            // the agent-side convenience tool fails to register.
            log.warn(
                    "Failed to register contribute_to_workspace tool onto main agent: {}",
                    e.getMessage());
        }
    }
}
