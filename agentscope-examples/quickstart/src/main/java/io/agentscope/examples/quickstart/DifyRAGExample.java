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
import io.agentscope.core.rag.integration.dify.DifyKnowledge;
import io.agentscope.core.rag.integration.dify.DifyRAGConfig;

/**
 * Example demonstrating how to use Dify Knowledge Base for RAG.
 */
public class DifyRAGExample {

    public static void main(String[] args) throws Exception {
        // Check environment variables
        String difyApiKey = System.getenv("DIFY_RAG_API_KEY");
        String difyBaseUrl = System.getenv("DIFY_API_BASE_URL");
        String datasetId = System.getenv("DIFY_DATASET_ID");
        String apiKey = ExampleUtils.getDashScopeApiKey();

        if (difyApiKey == null || difyBaseUrl == null || datasetId == null) {
            System.err.println("Error: Required environment variables not set.");
            System.err.println("Please set the following environment variables:");
            System.err.println("  - DIFY_RAG_API_KEY");
            System.err.println("  - DIFY_API_BASE_URL");
            System.err.println("  - DIFY_DATASET_ID");
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
                                DifyKnowledge.builder()
                                        .config(
                                                DifyRAGConfig.builder()
                                                        .apiKey(difyApiKey)
                                                        .apiBaseUrl(difyBaseUrl)
                                                        .datasetId(datasetId)
                                                        .build())
                                        .build())
                        .ragMode(RAGMode.AGENTIC)
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
