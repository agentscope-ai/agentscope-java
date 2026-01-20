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
package io.agentscope.examples.advanced;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import java.util.HashMap;
import java.util.Map;

/**
 * Mem0Example - Demonstrates long-term memory using Mem0 backend.
 */
public class Mem0Example {

    public static void main(String[] args) throws Exception {
        // Get API keys
        String dashscopeApiKey = ExampleUtils.getDashScopeApiKey();
        String mem0BaseUrl = getMem0BaseUrl();
        Mem0ApiType mem0ApiType = getMem0ApiType();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agentName", "SmartAssistant");
        Mem0LongTermMemory.Builder memoryBuilder =
                Mem0LongTermMemory.builder()
                        .agentName("SmartAssistant")
                        .userId("static-control01126")
                        .apiBaseUrl(mem0BaseUrl)
                        .apiKey(System.getenv("MEM0_API_KEY"))
                        .apiType(mem0ApiType)
                        .metadata(metadata);

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
     * @return API type enum: PLATFORM (default) or SELF_HOSTED
     */
    private static Mem0ApiType getMem0ApiType() {
        String apiTypeStr = System.getenv("MEM0_API_TYPE");
        if (apiTypeStr == null || apiTypeStr.isEmpty()) {
            return Mem0ApiType.PLATFORM;
        }
        return Mem0ApiType.fromString(apiTypeStr);
    }
}
