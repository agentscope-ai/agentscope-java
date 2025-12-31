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
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.integration.haystack.HayStackConfig;
import io.agentscope.core.rag.integration.haystack.HayStackKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;

/**
 * Example demonstrating how to use HayStack Knowledge Base for RAG.
 */
public class HayStackRAGExample {

    public static void main(String[] args) throws Exception {
        // Check environment variables
        String haystackBaseUrl = System.getenv("HAYSTACK_BASE_URL");
        String apiKey = ExampleUtils.getDashScopeApiKey();

        if (haystackBaseUrl == null) {
            System.err.println("Error: Required environment variables not set.");
            System.err.println("Please set the following environment variables:");
            System.err.println("  - HAYSTACK_BASE_URL");
            System.exit(1);
        }

        ReActAgent agent =
                ReActAgent.builder()
                        .name("KnowledgeAssistant")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .build())
                        .knowledge(
                                HayStackKnowledge.builder()
                                        .config(
                                                HayStackConfig.builder()
                                                        .baseUrl(haystackBaseUrl)
                                                        .scoreThreshold(0.2)
                                                        .topK(3)
                                                        .build())
                                        .build())
                        .retrieveConfig(RetrieveConfig.builder().scoreThreshold(0.2).build())
                        .ragMode(RAGMode.GENERIC)
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
}
