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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * E2E test for DashScope context caching (cache_control at content block level).
 *
 * <p>Verifies that the DashScope API accepts requests with cache_control placed at the content
 * block level, which is the correct placement per API documentation.
 *
 * <p>Two identical requests are made via a ReActAgent with cacheControl enabled. The first creates
 * the cache, and the second should hit the cache. Both requests must succeed without API errors.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set. Set
 * ENABLE_E2E_TESTS=true to run this test.
 */
@Tag("e2e")
@ExtendWith(E2ETestCondition.class)
@DisplayName("Cache Control E2E Test")
class CacheControlE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(120);

    /**
     * A long system prompt to ensure it meets the minimum token length for caching. DashScope
     * requires at least 1024 tokens for cache creation.
     */
    private static final String LONG_SYSTEM_PROMPT =
            """
            You are a highly knowledgeable assistant specializing in computer science and software \
            engineering. You have deep expertise in the following areas:

            1. Programming Languages: You are proficient in Java, Python, JavaScript, TypeScript, \
            C++, Rust, Go, and many other languages. You can explain language features, design \
            patterns, and best practices for each.

            2. Data Structures and Algorithms: You have comprehensive knowledge of arrays, linked \
            lists, trees, graphs, hash tables, heaps, and their various implementations. You can \
            analyze time and space complexity using Big-O notation.

            3. System Design: You can design scalable, distributed systems including load balancers, \
            caches, message queues, databases, and microservices architectures. You understand CAP \
            theorem, consistency models, and distributed consensus protocols.

            4. Databases: You are familiar with relational databases (MySQL, PostgreSQL), NoSQL \
            databases (MongoDB, Redis, Cassandra), and NewSQL databases. You understand indexing, \
            query optimization, sharding, and replication strategies.

            5. Cloud Computing: You have expertise in AWS, Azure, and GCP services. You understand \
            containerization with Docker and Kubernetes, serverless computing, and infrastructure \
            as code with Terraform and CloudFormation.

            6. Machine Learning and AI: You understand supervised learning, unsupervised learning, \
            reinforcement learning, neural networks, transformers, and large language models. You \
            can explain attention mechanisms, backpropagation, and optimization techniques.

            7. Security: You are knowledgeable about encryption, authentication, authorization, \
            OAuth, JWT, XSS, CSRF, SQL injection, and other security topics. You understand \
            secure coding practices and threat modeling.

            8. DevOps and CI/CD: You understand continuous integration, continuous deployment, \
            GitOps, monitoring, logging, and observability. You can set up pipelines using \
            Jenkins, GitHub Actions, GitLab CI, and ArgoCD.

            9. Software Engineering Practices: You advocate for clean code, SOLID principles, \
            test-driven development, code reviews, and documentation. You understand agile \
            methodologies including Scrum and Kanban.

            10. Networking: You understand TCP/IP, HTTP/HTTPS, WebSockets, gRPC, DNS, CDN, \
            and network security. You can explain how data flows through the internet stack.

            When answering questions, be concise but thorough. Provide code examples when \
            appropriate. Always consider edge cases and potential pitfalls. If you are unsure \
            about something, say so rather than guessing. Cite relevant documentation or \
            resources when possible.

            Remember to format your responses clearly with proper headings, bullet points, \
            and code blocks. Use markdown formatting for better readability.

            Please respond in a professional and helpful manner. Your goal is to help the \
            user understand complex technical concepts and solve real-world engineering problems.
            """;

    @Test
    @DisplayName("DashScope should accept cache_control at content block level")
    void testDashScopeCacheControlAccepted() {
        assumeTrue(
                ProviderFactory.hasDashScopeKey(),
                "DASHSCOPE_API_KEY not set, skipping cache control E2E test");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty("DASHSCOPE_API_KEY");
        }

        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(false)
                        .formatter(new DashScopeChatFormatter())
                        .defaultOptions(GenerateOptions.builder().cacheControl(true).build())
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("CacheControlTestAgent")
                        .model(model)
                        .toolkit(new Toolkit())
                        .sysPrompt(LONG_SYSTEM_PROMPT)
                        .memory(new InMemoryMemory())
                        .build();

        // First call: creates the cache
        System.out.println("=== Cache Control E2E: First call (cache creation) ===");
        Msg input1 = TestUtils.createUserMessage("User", "What is a binary search tree?");
        Msg response1 = agent.call(input1).block(TEST_TIMEOUT);

        assertNotNull(response1, "First response should not be null");
        String text1 = TestUtils.extractTextContent(response1);
        assertNotNull(text1, "First response should have text content");
        assertTrue(!text1.isEmpty(), "First response text should not be empty");
        System.out.println(
                "First call succeeded. Response: "
                        + text1.substring(0, Math.min(100, text1.length()))
                        + "...");

        // Reset memory for a clean second call with the same system prompt
        agent.getMemory().clear();

        // Second call: should hit the cache
        System.out.println("=== Cache Control E2E: Second call (cache hit expected) ===");
        Msg input2 = TestUtils.createUserMessage("User", "What is a binary search tree?");
        Msg response2 = agent.call(input2).block(TEST_TIMEOUT);

        assertNotNull(response2, "Second response should not be null");
        String text2 = TestUtils.extractTextContent(response2);
        assertNotNull(text2, "Second response should have text content");
        assertTrue(!text2.isEmpty(), "Second response text should not be empty");
        System.out.println(
                "Second call succeeded. Response: "
                        + text2.substring(0, Math.min(100, text2.length()))
                        + "...");

        System.out.println(
                "Cache control E2E test passed - DashScope native API accepted cache_control at"
                        + " content block level");
    }

    @Test
    @DisplayName(
            "DashScope OpenAI-compatible endpoint should accept cache_control at content block"
                    + " level")
    void testOpenAICompatibleCacheControlAccepted() {
        assumeTrue(
                ProviderFactory.hasDashScopeKey(),
                "DASHSCOPE_API_KEY not set, skipping OpenAI-compatible cache control E2E test");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty("DASHSCOPE_API_KEY");
        }

        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                        .apiKey(apiKey)
                        .modelName("qwen-plus")
                        .stream(false)
                        .formatter(new OpenAIChatFormatter())
                        .generateOptions(GenerateOptions.builder().cacheControl(true).build())
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("OpenAICacheControlTestAgent")
                        .model(model)
                        .toolkit(new Toolkit())
                        .sysPrompt(LONG_SYSTEM_PROMPT)
                        .memory(new InMemoryMemory())
                        .build();

        // First call: creates the cache
        System.out.println(
                "=== OpenAI-Compatible Cache Control E2E: First call (cache creation) ===");
        Msg input1 = TestUtils.createUserMessage("User", "What is a binary search tree?");
        Msg response1 = agent.call(input1).block(TEST_TIMEOUT);

        assertNotNull(response1, "First response should not be null");
        String text1 = TestUtils.extractTextContent(response1);
        assertNotNull(text1, "First response should have text content");
        assertTrue(!text1.isEmpty(), "First response text should not be empty");
        System.out.println(
                "First call succeeded. Response: "
                        + text1.substring(0, Math.min(100, text1.length()))
                        + "...");

        // Reset memory for a clean second call with the same system prompt
        agent.getMemory().clear();

        // Second call: should hit the cache
        System.out.println(
                "=== OpenAI-Compatible Cache Control E2E: Second call (cache hit expected) ===");
        Msg input2 = TestUtils.createUserMessage("User", "What is a binary search tree?");
        Msg response2 = agent.call(input2).block(TEST_TIMEOUT);

        assertNotNull(response2, "Second response should not be null");
        String text2 = TestUtils.extractTextContent(response2);
        assertNotNull(text2, "Second response should have text content");
        assertTrue(!text2.isEmpty(), "Second response text should not be empty");
        System.out.println(
                "Second call succeeded. Response: "
                        + text2.substring(0, Math.min(100, text2.length()))
                        + "...");

        System.out.println(
                "Cache control E2E test passed - OpenAI-compatible endpoint accepted"
                        + " cache_control at content block level");
    }
}
