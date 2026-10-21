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
package io.agentscope.builder.web.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.builder.runtime.managed.ManagedMcpCatalog;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolFilter;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedMcpProxyConfigurationTest {
    private final ManagedMcpCatalog.Description description =
            new ManagedMcpCatalog.Description(
                    List.of(
                            new ManagedMcpCatalog.Tool(
                                    "catalog",
                                    "catalog__lookup",
                                    "lookup",
                                    "Lookup",
                                    Map.of(),
                                    null,
                                    true)),
                    List.of());

    @Test
    void remoteDescriptorsSurviveRestrictedBuiltinsAndDenyStillWins() {
        var config = new ToolsConfig();
        config.setDefaultToolsEnabled(false);
        config.setAllow(List.of("read_file"));
        config.setMcpServers(Map.of("catalog", new McpServerConfig()));
        var proxy = HarnessAgentBuildService.managedProxyConfiguration(config, description);
        assertTrue(proxy.getMcpServers().isEmpty());
        assertFalse(config.getMcpServers().isEmpty());
        assertTrue(ToolFilter.isAllowed("catalog__lookup", proxy));
        assertTrue(ToolFilter.isAllowed("read_file", proxy));
        assertFalse(ToolFilter.isAllowed("shell", proxy));
        config.setDeny(List.of("catalog__lookup"));
        assertFalse(
                ToolFilter.isAllowed(
                        "catalog__lookup",
                        HarnessAgentBuildService.managedProxyConfiguration(config, description)));
    }

    @Test
    void defaultBuiltinSurfaceIsPreservedAndStrictChildAllowlistCannotExpand() {
        var config = new ToolsConfig();
        var proxy = HarnessAgentBuildService.managedProxyConfiguration(config, description);
        assertTrue(ToolFilter.isAllowed("shell", proxy));
        assertTrue(ToolFilter.isAllowed("catalog__lookup", proxy));
        config.setStrictAllow(true);
        config.setAllow(List.of("read_file"));
        proxy = HarnessAgentBuildService.managedProxyConfiguration(config, description);
        assertTrue(ToolFilter.isAllowed("read_file", proxy));
        assertFalse(ToolFilter.isAllowed("catalog__lookup", proxy));
    }
}
