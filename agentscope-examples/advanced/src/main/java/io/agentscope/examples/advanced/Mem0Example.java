/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.advanced;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;

/**
 * Mem0Example - Demonstrates long-term memory using Mem0 backend.
 */
public class Mem0Example {

    public static void main(String[] args) throws Exception {
        // Get API keys
        String dashscopeApiKey = ExampleUtils.getDashScopeApiKey();
        String mem0BaseUrl = getMem0BaseUrl();
        String mem0ApiType = getMem0ApiType();

        Mem0LongTermMemory.Builder memoryBuilder =
                Mem0LongTermMemory.builder()
                        .agentName("SmartAssistant")
                        .userId("static-control01126")
                        .apiBaseUrl(mem0BaseUrl)
                        .apiKey(System.getenv("MEM0_API_KEY"));

        // Set apiType if specified (for self-hosted Mem0)
        if (mem0ApiType != null && !mem0ApiType.isEmpty()) {
            memoryBuilder.apiType(mem0ApiType);
        }

        Mem0LongTermMemory longTermMemory = memoryBuilder.build();

        // Create agent with AGENT_CONTROL mode
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(dashscopeApiKey)
                                        .modelName("qwen-plus")
                                        .build())
                        .longTermMemory(longTermMemory)
                        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                        .build();

        UserAgent userAgent = UserAgent.builder().name("User").build();

        Msg msg = null;
        while (true) {
            msg = userAgent.call(msg).block();
            if (msg.getTextContent().equals("exit")) {
                break;
            }
            msg = agent.call(msg).block();
        }
    }

    /**
     * Gets Mem0 API base URL from environment variable or uses default.
     */
    private static String getMem0BaseUrl() {
        String baseUrl = System.getenv("MEM0_API_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "https://api.mem0.ai";
        }
        return baseUrl;
    }

    /**
     * Gets Mem0 API type from environment variable.
     *
     * @return API type: "platform" (default) or "self-hosted", or null if not specified
     */
    private static String getMem0ApiType() {
        return System.getenv("MEM0_API_TYPE");
    }
}
