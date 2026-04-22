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

package io.agentscope.extensions.higress;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Toolkit extension for Higress AI Gateway integration.
 *
 * <p>This toolkit extends {@link Toolkit} to provide Higress-specific functionality
 * while inheriting all standard toolkit features.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create Higress MCP client
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress")
 *     .streamableHttpEndpoint("http://gateway/mcp-servers/union-tools-search")
 *     .build();
 *
 * // Register with toolkit
 * HigressToolkit toolkit = new HigressToolkit();
 * toolkit.registerMcpClient(client).block();
 *
 * // Or use fluent API for more options
 * toolkit.registration()
 *     .mcpClient(client)
 *     .enableTools(List.of("tool1", "tool2"))
 *     .group("myGroup")
 *     .apply();
 * }</pre>
 *
 * @see Toolkit
 * @see HigressMcpClientWrapper
 * @see HigressMcpClientBuilder
 */
public class HigressToolkit extends Toolkit {

    private static final Logger logger = LoggerFactory.getLogger(HigressToolkit.class);

    /**
     * Reference to the registered Higress MCP client.
     */
    private HigressMcpClientWrapper higressMcpClient;

    /**
     * Creates a new HigressToolkit with default configuration.
     */
    public HigressToolkit() {
        super();
    }

    /**
     * Creates a new HigressToolkit with custom configuration.
     *
     * @param config the toolkit configuration
     */
    public HigressToolkit(ToolkitConfig config) {
        super(config);
    }

    /**
     * Registers an MCP client with the toolkit.
     *
     * <p>If the client is a {@link HigressMcpClientWrapper}, it will be cached
     * for later access via {@link #getHigressMcpClient()}.
     *
     * <p>For advanced registration options (filtering, groups), use the fluent API:
     * <pre>{@code
     * toolkit.registration()
     *     .mcpClient(client)
     *     .enableTools(List.of("tool1"))
     *     .group("myGroup")
     *     .apply();
     * }</pre>
     *
     * @param mcpClientWrapper the MCP client wrapper to register
     * @return Mono that completes when registration is finished
     */
    @Override
    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return super.registerMcpClient(mcpClientWrapper)
                .doOnSuccess(unused -> cacheHigressClient(mcpClientWrapper));
    }

    private void cacheHigressClient(McpClientWrapper mcpClientWrapper) {
        if (mcpClientWrapper instanceof HigressMcpClientWrapper higressClient) {
            this.higressMcpClient = higressClient;
            logger.info(
                    "Higress MCP client '{}' registered successfully", mcpClientWrapper.getName());
        }
    }

    /**
     * Gets the registered Higress MCP client.
     *
     * @return the Higress MCP client, or null if not registered
     */
    public HigressMcpClientWrapper getHigressMcpClient() {
        return higressMcpClient;
    }
}
