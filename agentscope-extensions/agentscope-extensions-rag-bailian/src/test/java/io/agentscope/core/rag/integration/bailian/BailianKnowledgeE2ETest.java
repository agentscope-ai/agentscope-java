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
package io.agentscope.core.rag.integration.bailian;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end test for BailianKnowledge.
 *
 * <p>This test requires the following environment variables to be set:
 * <ul>
 *   <li>ALIBABA_CLOUD_ACCESS_KEY_ID - Your Alibaba Cloud access key ID
 *   <li>ALIBABA_CLOUD_ACCESS_KEY_SECRET - Your Alibaba Cloud access key secret
 *   <li>BAILIAN_WORKSPACE_ID - Your Bailian workspace ID
 *   <li>BAILIAN_INDEX_ID - A knowledge base index ID to test with
 * </ul>
 *
 * <p>If these environment variables are not set, the test will be skipped.
 */
class BailianKnowledgeE2ETest {

    private static final Logger log = LoggerFactory.getLogger(BailianKnowledgeE2ETest.class);

    private static String accessKeyId;
    private static String accessKeySecret;
    private static String workspaceId;
    private static String indexId;

    @BeforeAll
    static void setUp() {
        accessKeyId = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID");
        accessKeySecret = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET");
        workspaceId = System.getenv("BAILIAN_WORKSPACE_ID");
        indexId = System.getenv("BAILIAN_INDEX_ID");

        if (accessKeyId == null
                || accessKeySecret == null
                || workspaceId == null
                || indexId == null) {
            log.info("Skipping BailianKnowledgeE2ETest: Required environment variables not set");
            log.info(
                    "To run this test, please set: ALIBABA_CLOUD_ACCESS_KEY_ID,"
                            + " ALIBABA_CLOUD_ACCESS_KEY_SECRET, BAILIAN_WORKSPACE_ID,"
                            + " BAILIAN_INDEX_ID");
        }
    }

    @Test
    void testRetrieveFromBailianKnowledgeBase() {
        assumeTrue(
                accessKeyId != null
                        && accessKeySecret != null
                        && workspaceId != null
                        && indexId != null,
                "Environment variables for Bailian not set");

        // Create configuration
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .workspaceId(workspaceId)
                        .indexId(indexId)
                        .build();

        // Create knowledge base
        BailianKnowledge knowledge = BailianKnowledge.builder().config(config).build();

        // Retrieve documents
        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.3).build();

        List<Document> results = knowledge.retrieve("测试查询", retrieveConfig).block();

        // Verify results
        assertNotNull(results);
        log.info("Retrieved {} documents", results.size());

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            log.info(
                    "Document {}: score={}, content={}",
                    i + 1,
                    doc.getScore(),
                    doc.getMetadata()
                            .getContentText()
                            .substring(
                                    0, Math.min(100, doc.getMetadata().getContentText().length())));

            assertNotNull(doc.getScore());
            assertTrue(doc.getScore() >= 0.3);
            assertNotNull(doc.getMetadata());
            assertNotNull(doc.getMetadata().getContentText());
            assertFalse(doc.getMetadata().getContentText().isEmpty());
        }
    }

    @Test
    void testBailianKnowledgeWithKnowledgeRetrievalTools() {
        assumeTrue(
                accessKeyId != null
                        && accessKeySecret != null
                        && workspaceId != null
                        && indexId != null,
                "Environment variables for Bailian not set");

        // Create knowledge base
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .workspaceId(workspaceId)
                        .indexId(indexId)
                        .build();

        BailianKnowledge knowledge = BailianKnowledge.builder().config(config).build();

        // Create knowledge retrieval tools
        KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);

        // Register with toolkit
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);

        // Verify tool is registered
        assertNotNull(toolkit);
        assertTrue(toolkit.getToolNames().size() > 0);

        // Call the tool directly (pass null for agent since we're calling directly)
        String result = tools.retrieveKnowledge("测试查询", 3, null);

        assertNotNull(result);
        log.info("Tool result: {}", result);

        // The result should contain information about retrieved documents
        assertTrue(
                result.contains("document")
                        || result.contains("Document")
                        || result.contains("文档"));
    }

    @Test
    void testEmptyQueryReturnsEmptyResults() {
        assumeTrue(
                accessKeyId != null
                        && accessKeySecret != null
                        && workspaceId != null
                        && indexId != null,
                "Environment variables for Bailian not set");

        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .workspaceId(workspaceId)
                        .indexId(indexId)
                        .build();

        BailianKnowledge knowledge = BailianKnowledge.builder().config(config).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        List<Document> results = knowledge.retrieve("", retrieveConfig).block();

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }
}
