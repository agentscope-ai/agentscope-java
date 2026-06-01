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
import io.agentscope.core.memory.reme.ReMeLongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;

/**
 * ReMeExample - Demonstrates long-term memory using ReMe backend.
 *
 * <p><b>Install from Source:</b>
 * <pre>
 * git clone https://github.com/agentscope-ai/ReMe.git
 * cd ReMe
 * pip install .
 * </pre>
 *
 * <p><b>Environment Configuration:</b>
 * Copy example.env to .env and modify the corresponding parameters:
 * <pre>
 * FLOW_LLM_API_KEY=sk-xxxx
 * FLOW_LLM_BASE_URL=https://xxxx/v1
 * FLOW_EMBEDDING_API_KEY=sk-xxxx
 * FLOW_EMBEDDING_BASE_URL=https://xxxx/v1
 * </pre>
 *
 * <p><b>Quick Start - HTTP Service:</b>
 * <pre>
 * reme \
 *   backend=http \
 *   http.port=8002 \
 *   llm.default.model_name=qwen3-30b-a3b-thinking-2507 \
 *   embedding_model.default.model_name=text-embedding-v4 \
 *   vector_store.default.backend=local
 * </pre>
 *
 * <p>The service will run at http://localhost:8002 by default.
 * You can override the base URL by setting the REME_API_BASE_URL environment variable.
 */
public class ReMeExample {

    public static void main(String[] args) throws Exception {
        // Get API keys
        String dashscopeApiKey = ExampleUtils.getDashScopeApiKey();
        String remeBaseUrl = getReMeBaseUrl();

        ReMeLongTermMemory longTermMemory =
                ReMeLongTermMemory.builder().userId("example_user").apiBaseUrl(remeBaseUrl).build();

        // Create agent with STATIC_CONTROL mode
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
     * Gets ReMe API base URL from environment variable or uses default.
     */
    private static String getReMeBaseUrl() {
        String baseUrl = System.getenv("REME_API_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "http://localhost:8002";
        }
        return baseUrl;
    }
}
