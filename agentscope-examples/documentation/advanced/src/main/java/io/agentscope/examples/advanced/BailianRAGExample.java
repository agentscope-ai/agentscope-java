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
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;

/**
 * Example demonstrating how to use Bailian Knowledge Base for RAG.
 */
public class BailianRAGExample {

    public static void main(String[] args) throws Exception {
        // Check environment variables
        String accessKeyId = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID");
        String accessKeySecret = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET");
        String workspaceId = System.getenv("BAILIAN_WORKSPACE_ID");
        String indexId = System.getenv("BAILIAN_INDEX_ID");
        String apiKey = ExampleUtils.getDashScopeApiKey();

        if (accessKeyId == null
                || accessKeySecret == null
                || workspaceId == null
                || indexId == null) {
            System.err.println("Error: Required environment variables not set.");
            System.err.println("Please set the following environment variables:");
            System.err.println("  - ALIBABA_CLOUD_ACCESS_KEY_ID");
            System.err.println("  - ALIBABA_CLOUD_ACCESS_KEY_SECRET");
            System.err.println("  - BAILIAN_WORKSPACE_ID");
            System.err.println("  - BAILIAN_INDEX_ID");
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
                                BailianKnowledge.builder()
                                        .config(
                                                BailianConfig.builder()
                                                        .accessKeyId(accessKeyId)
                                                        .accessKeySecret(accessKeySecret)
                                                        .workspaceId(workspaceId)
                                                        .indexId(indexId)
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
