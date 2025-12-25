/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.examples.bobatea.business.config;

import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.FEEDBACK_CREATE_FEEDBACK;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.FEEDBACK_GET_FEEDBACK_BY_ORDER;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.FEEDBACK_GET_FEEDBACK_BY_USER;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.FEEDBACK_UPDATE_SOLUTION;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_CHECK_STOCK;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_CREATE_ORDER_WITH_USER;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_DELETE_ORDER;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_GET_ORDER;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_GET_ORDERS;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_GET_ORDERS_BY_USER;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_GET_ORDER_BY_USER;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_QUERY_ORDERS;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_UPDATE_REMARK;
import static io.agentscope.examples.bobatea.business.config.McpToolDefinitions.ORDER_VALIDATE_PRODUCT;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;

/**
 * MCP Server Configuration.
 * Configures the MCP server using the native MCP SDK with Spring WebFlux transport.
 */
@Configuration
public class McpServerConfig {

    @Autowired private McpToolHandlers handlers;

    @Bean
    public WebFluxSseServerTransportProvider webFluxSseServerTransportProvider(
            ObjectMapper objectMapper) {
        return new WebFluxSseServerTransportProvider(objectMapper, "/mcp/message");
    }

    @Bean
    public RouterFunction<?> mcpRouterFunction(
            WebFluxSseServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }

    @Bean
    public McpSyncServer mcpSyncServer(WebFluxSseServerTransportProvider transportProvider) {
        McpServer.SyncSpecification spec =
                McpServer.sync(transportProvider)
                        .serverInfo("business-mcp-server", "0.0.1")
                        .capabilities(ServerCapabilities.builder().tools(true).build());

        registerOrderTools(spec);
        registerFeedbackTools(spec);

        return spec.build();
    }

    private void registerOrderTools(McpServer.SyncSpecification spec) {
        spec.tool(
                tool(ORDER_CREATE_ORDER_WITH_USER),
                (ex, args) -> handlers.createOrderWithUser(args));
        spec.tool(tool(ORDER_GET_ORDER), (ex, args) -> handlers.getOrder(args));
        spec.tool(tool(ORDER_GET_ORDER_BY_USER), (ex, args) -> handlers.getOrderByUser(args));
        spec.tool(tool(ORDER_CHECK_STOCK), (ex, args) -> handlers.checkStock(args));
        spec.tool(tool(ORDER_GET_ORDERS), (ex, args) -> handlers.getOrders(args));
        spec.tool(tool(ORDER_GET_ORDERS_BY_USER), (ex, args) -> handlers.getOrdersByUser(args));
        spec.tool(tool(ORDER_QUERY_ORDERS), (ex, args) -> handlers.queryOrders(args));
        spec.tool(tool(ORDER_DELETE_ORDER), (ex, args) -> handlers.deleteOrder(args));
        spec.tool(tool(ORDER_UPDATE_REMARK), (ex, args) -> handlers.updateRemark(args));
        spec.tool(tool(ORDER_VALIDATE_PRODUCT), (ex, args) -> handlers.validateProduct(args));
    }

    private void registerFeedbackTools(McpServer.SyncSpecification spec) {
        spec.tool(tool(FEEDBACK_CREATE_FEEDBACK), (ex, args) -> handlers.createFeedback(args));
        spec.tool(
                tool(FEEDBACK_GET_FEEDBACK_BY_USER),
                (ex, args) -> handlers.getFeedbackByUser(args));
        spec.tool(
                tool(FEEDBACK_GET_FEEDBACK_BY_ORDER),
                (ex, args) -> handlers.getFeedbackByOrder(args));
        spec.tool(tool(FEEDBACK_UPDATE_SOLUTION), (ex, args) -> handlers.updateSolution(args));
    }

    private Tool tool(McpToolDefinitions.ToolDefinition def) {
        return new Tool(def.name(), def.description(), def.schemaAsString());
    }
}
