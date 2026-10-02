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
package io.agentscope.examples.documentation2.multimodal;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * VisionExample - Demonstrates vision capabilities with images.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>Removed {@code .memory(new InMemoryMemory())} — not required in 2.0.</li>
 * </ul>
 */
public class VisionExample {

    /**
     * Runs the vision example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Vision Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates how to use vision capabilities.\n"
                        + "The agent can analyze images and describe what it sees.\n"
                        + "\nNote: DashScope vision requires Base64-encoded images for best"
                        + " compatibility.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("VisionAssistant")
                        .sysPrompt(
                                "You are a helpful AI assistant with vision capabilities. Analyze"
                                        + " images carefully and provide accurate descriptions.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-vl-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .defaultOptions(GenerateOptions.builder().build())
                                        .build())
                        .toolkit(new Toolkit())
                        .build();

        demonstrateVision(agent);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("Interactive Mode");
        System.out.println("=".repeat(80));
        System.out.println("You can now chat with the agent normally.");
        System.out.println("=".repeat(80) + "\n");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg userMsg = new UserMessage(input.trim());
            System.out.print("\nAgent: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }

    private static void demonstrateVision(ReActAgent agent) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("Vision Capability Demo");
        System.out.println("=".repeat(80));
        System.out.println("Testing with a simple image (20x20 red square PNG)");
        System.out.println("Question: What color is this image?\n");

        try {
            // Minimal valid 20x20 red square PNG (Base64 encoded)
            String redSquareBase64 =
                    "iVBORw0KGgoAAAANSUhEUgAAABQAAAAUCAIAAAAC64paAAAAFklEQVR42mP8z8DAwMj4n4FhFIw"
                            + "CMgBmBQEAAhUCYwAAAABJRU5ErkJggg==";

            Msg userMsg =
                    new UserMessage(
                            TextBlock.builder()
                                    .text("What color is this image? Please describe it.")
                                    .build(),
                            ImageBlock.builder()
                                    .source(
                                            Base64Source.builder()
                                                    .data(redSquareBase64)
                                                    .mediaType("image/png")
                                                    .build())
                                    .build());

            System.out.println("Sending request to vision model...");
            Msg response = agent.call(userMsg).block();

            System.out.println("\nAgent Response:");
            System.out.println("-".repeat(80));
            System.out.println(response.getTextContent());
            System.out.println("-".repeat(80));
            System.out.println("\nVision capability verified successfully!");
        } catch (Exception e) {
            System.err.println("\nError analyzing image: " + e.getMessage());
            System.err.println("\nThis may indicate an issue with:");
            System.err.println("  1. API key or model access");
            System.err.println("  2. Network connectivity");
            System.err.println("  3. Model configuration");
            System.err.println("\nYou can still test with text-only questions below.\n");
        }
    }
}
