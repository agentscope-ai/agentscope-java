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
        String difyApiKey = "dataset-XQkWpRwqI06e4h5mN0eDUHgB";
        String difyBaseUrl = "http://117.72.68.96/v1";
        String datasetId = "dd263f99-eab2-4eae-ba57-69f2467436e4";
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

        Msg msg = agent.call(Msg.builder().textContent("检索机器人知识库").build()).block();
        System.out.println(msg.getTextContent());
    }
}
