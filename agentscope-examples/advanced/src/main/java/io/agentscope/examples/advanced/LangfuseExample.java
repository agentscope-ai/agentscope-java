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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;
import java.util.Base64;
import java.util.Scanner;

/**
 * Example demonstrating Langfuse integration for tracing and evaluation.
 *
 * <p>This example shows how to send trace data to Langfuse for:
 * <ul>
 *   <li>Agent call tracing</li>
 *   <li>LLM call tracing (with token usage, latency, etc.)</li>
 *   <li>Tool execution tracing</li>
 *   <li>Multi-turn conversation tracking</li>
 * </ul>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Set LANGFUSE_PUBLIC_KEY environment variable</li>
 *   <li>Set LANGFUSE_SECRET_KEY environment variable</li>
 *   <li>Set LANGFUSE_ENDPOINT environment variable (optional, defaults to US region)</li>
 *   <li>Set DASHSCOPE_API_KEY environment variable (or your preferred model API key)</li>
 * </ul>
 *
 * <p>After running, visit <a href="https://cloud.langfuse.com">Langfuse Console</a>
 * to view traces and run evaluations.
 */
public class LangfuseExample {

    // Langfuse OTLP endpoints
    private static final String LANGFUSE_US_ENDPOINT =
            "https://us.cloud.langfuse.com/api/public/otel/v1/traces";
    private static final String LANGFUSE_EU_ENDPOINT =
            "https://cloud.langfuse.com/api/public/otel/v1/traces";

    public static void main(String[] args) {
        // Get API keys and configuration
        String dashScopeApiKey = ExampleUtils.getDashScopeApiKey();
        String langfusePublicKey = getLangfusePublicKey();
        String langfuseSecretKey = getLangfuseSecretKey();
        String langfuseEndpoint = getLangfuseEndpoint();

        System.out.println("Starting Langfuse Tracing Example...\n");

        // Initialize Langfuse tracing
        System.out.println("Initializing Langfuse tracing...");
        initLangfuseTracing(langfusePublicKey, langfuseSecretKey, langfuseEndpoint);
        System.out.println("Langfuse tracing initialized\n");

        // Create agent
        System.out.println("Creating agent with Langfuse tracing...");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful AI assistant. Be concise and informative in your"
                                        + " responses.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(dashScopeApiKey)
                                        .modelName("qwen-plus")
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .build();
        System.out.println("Agent created\n");

        // Multi-turn conversation loop
        System.out.println("========================================");
        System.out.println("All traces will be sent to Langfuse");
        System.out.println("Type 'exit' to quit");
        System.out.println("========================================\n");

        try (Scanner scanner = new Scanner(System.in)) {
            Msg lastResponse = null;
            int turn = 1;

            while (true) {
                // Get user input
                System.out.print("[Turn " + turn + "] You: ");
                String userInput = scanner.nextLine().trim();

                if (userInput.isEmpty()) {
                    continue;
                }

                if ("exit".equalsIgnoreCase(userInput)) {
                    System.out.println("\nConversation ended");
                    break;
                }

                // Create user message
                Msg userMsg =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(userInput).build())
                                .build();

                // Call agent - this will be traced to Langfuse
                System.out.println("[Turn " + turn + "] Agent thinking...");
                long startTime = System.currentTimeMillis();

                Msg response = agent.call(userMsg).block();

                long elapsed = System.currentTimeMillis() - startTime;

                if (response != null) {
                    System.out.println(
                            "[Turn "
                                    + turn
                                    + "] Agent ("
                                    + elapsed
                                    + "ms): "
                                    + response.getTextContent());
                    System.out.println();
                }

                lastResponse = response;
                turn++;
            }
        } finally {
            System.out.println("\n========================================");
            System.out.println("Session complete!");
            System.out.println("========================================\n");
        }
    }

    /**
     * Initialize Langfuse tracing by registering a TelemetryTracer with Langfuse OTLP endpoint.
     *
     * @param publicKey Langfuse public key
     * @param secretKey Langfuse secret key
     * @param endpoint  Langfuse OTLP endpoint URL
     */
    private static void initLangfuseTracing(String publicKey, String secretKey, String endpoint) {
        // Build Basic Auth header
        String credentials = publicKey + ":" + secretKey;
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

        // Create and register TelemetryTracer with Langfuse configuration
        TelemetryTracer langfuseTracer =
                TelemetryTracer.builder()
                        .endpoint(endpoint)
                        .addHeader("Authorization", authHeader)
                        .build();

        TracerRegistry.register(langfuseTracer);

        System.out.println("  Endpoint: " + endpoint);
        System.out.println(
                "  Public Key: "
                        + publicKey.substring(0, Math.min(10, publicKey.length()))
                        + "...");
    }

    /**
     * Get Langfuse public key from environment variable.
     *
     * @return The public key
     * @throws IllegalStateException if not set
     */
    private static String getLangfusePublicKey() {
        String key = System.getenv("LANGFUSE_PUBLIC_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "LANGFUSE_PUBLIC_KEY environment variable is not set. "
                            + "Get your keys from https://cloud.langfuse.com");
        }
        return key;
    }

    /**
     * Get Langfuse secret key from environment variable.
     *
     * @return The secret key
     * @throws IllegalStateException if not set
     */
    private static String getLangfuseSecretKey() {
        String key = System.getenv("LANGFUSE_SECRET_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "LANGFUSE_SECRET_KEY environment variable is not set. "
                            + "Get your keys from https://cloud.langfuse.com");
        }
        return key;
    }

    /**
     * Get Langfuse OTLP endpoint from environment variable.
     * Falls back to US region endpoint if not set.
     *
     * @return The OTLP endpoint URL
     */
    private static String getLangfuseEndpoint() {
        String endpoint = System.getenv("LANGFUSE_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            // Default to US region endpoint
            return LANGFUSE_US_ENDPOINT;
        }
        return endpoint;
    }
}
