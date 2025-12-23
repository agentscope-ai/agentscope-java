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

package io.agentscope.examples.bobatea.business;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Business MCP Server Application Main Class
 * Provides both MCP tools and REST API dual interfaces
 * Also provides feedback MCP tools
 */
@SpringBootApplication
public class BusinessServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider businessTools(
            OrderMcpTools orderMcpTools, FeedbackMcpTools feedbackMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(orderMcpTools, feedbackMcpTools)
                .build();
    }
}
