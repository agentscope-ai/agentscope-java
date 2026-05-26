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
package io.agentscope.dataagent.tools.data;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wires a singleton {@link DataAgentToolkit} onto the built-in main agent's toolkit at startup,
 * so the agent can call {@code list_data_sources}, {@code describe_table}, {@code run_sql_preview}
 * and {@code render_chart}.
 *
 * <p>Mirrors {@code ContributionToolRegistrar}: runs after {@link DataAgentBootstrap} has built
 * every agent, fails soft on errors so a missing tool slot does not stop the application from
 * booting.
 */
@Component
public class DataToolkitRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitRegistrar.class);

    private final DataAgentBootstrap bootstrap;
    private final DataSourceRegistry registry;
    private final ChartRenderer chartRenderer;

    public DataToolkitRegistrar(
            DataAgentBootstrap bootstrap,
            DataSourceRegistry registry,
            ChartRenderer chartRenderer) {
        this.bootstrap = bootstrap;
        this.registry = registry;
        this.chartRenderer = chartRenderer;
    }

    @PostConstruct
    public void registerDataToolkit() {
        HarnessAgent main = bootstrap.agents().get(bootstrap.loadedConfig().getMain());
        if (main == null) {
            main =
                    bootstrap.agents().values().stream()
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "No agents available to register data toolkit"
                                                            + " onto"));
        }
        try {
            main.getDelegate()
                    .getToolkit()
                    .registerTool(new DataAgentToolkit(registry, chartRenderer));
            log.info("Registered DataAgent toolkit onto main agent '{}'", main.getName());
        } catch (RuntimeException e) {
            log.warn("Failed to register DataAgent toolkit onto main agent: {}", e.getMessage());
        }
    }
}
