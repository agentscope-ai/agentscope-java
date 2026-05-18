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
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * StructuredOutputExample - Demonstrates structured output generation.
 */
public class StructuredOutputExample {

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Structured Output Example",
                "This example demonstrates how to generate structured output from agents.\n"
                        + "The agent will analyze user queries and return structured data.");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Create Agent
        ReActAgent agent =
                ReActAgent.builder()
                        .name("AnalysisAgent")
                        .sysPrompt(
                                "You are an intelligent analysis assistant. "
                                        + "Analyze user requests and provide structured responses.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        // Example 1: Extract product information
        System.out.println("=== Example 1: Product Information ===\n");
        runProductAnalysisExample(agent);

        // Example 2: Extract contact information
        System.out.println("\n=== Example 2: Contact Information ===\n");
        runContactExtractionExample(agent);

        // Example 3: Sentiment analysis
        System.out.println("\n=== Example 3: Sentiment Analysis ===\n");
        runSentimentAnalysisExample(agent);

        // Example 4: Extract product information by Stream
        System.out.println("=== Example 4: Product Information ===\n");
        runStreamProductAnalysisExample(agent);

        System.out.println("\n=== All examples completed ===");
    }

    /** Example 1: Extract product information from natural language description. */
    private static void runProductAnalysisExample(ReActAgent agent) {
        String query =
                "I'm looking for a laptop. I need at least 16GB RAM, "
                        + "prefer Apple brand, and my budget is around $2000. "
                        + "It should be lightweight for travel.";

        System.out.println("Query: " + query);
        System.out.println("\nRequesting structured output...\n");

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "Extract the product requirements from this query: "
                                                        + query)
                                        .build())
                        .build();

        try {
            Msg msg = agent.call(userMsg, ProductRequirements.class).block();
            ProductRequirements result = msg.getStructuredData(ProductRequirements.class);

            System.out.println("Extracted structured data:");
            System.out.println("  Product Type: " + result.productType);
            System.out.println("  Brand: " + result.brand);
            System.out.println("  Min RAM: " + result.minRam + " GB");
            System.out.println("  Max Budget: $" + result.maxBudget);
            System.out.println("  Features: " + result.features);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Example 2: Extract contact information from text. */
    private static void runContactExtractionExample(ReActAgent agent) {
        String query =
                "Please contact John Smith at john.smith@example.com or "
                        + "call him at +1-555-123-4567. His company is TechCorp Inc.";

        System.out.println("Text: " + query);
        System.out.println("\nExtracting contact information...\n");

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("Extract contact information from: " + query)
                                        .build())
                        .build();

        try {
            Msg msg = agent.call(userMsg, ContactInfo.class).block();
            ContactInfo result = msg.getStructuredData(ContactInfo.class);

            System.out.println("Extracted contact information:");
            System.out.println("  Name: " + result.name);
            System.out.println("  Email: " + result.email);
            System.out.println("  Phone: " + result.phone);
            System.out.println("  Company: " + result.company);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Example 3: Perform sentiment analysis with detailed scores. */
    private static void runSentimentAnalysisExample(ReActAgent agent) {
        String review =
                "This product exceeded my expectations! The quality is amazing "
                        + "and the customer service was very helpful. However, "
                        + "the shipping took a bit longer than expected.";

        System.out.println("Review: " + review);
        System.out.println("\nAnalyzing sentiment...\n");

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "Analyze the sentiment of this review and provide"
                                                        + " scores: "
                                                        + review)
                                        .build())
                        .build();

        try {
            Msg msg = agent.call(userMsg, SentimentAnalysis.class).block();
            SentimentAnalysis result = msg.getStructuredData(SentimentAnalysis.class);

            System.out.println("Sentiment analysis results:");
            System.out.println("  Overall Sentiment: " + result.overallSentiment);
            System.out.println("  Positive Score: " + result.positiveScore);
            System.out.println("  Negative Score: " + result.negativeScore);
            System.out.println("  Neutral Score: " + result.neutralScore);
            System.out.println("  Key Topics: " + result.keyTopics);
            System.out.println("  Summary: " + result.summary);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Example 4: Extract product information from natural language description. */
    private static void runStreamProductAnalysisExample(ReActAgent agent) {
        String query =
                "I'm looking for a laptop. I need at least 16GB RAM, "
                        + "prefer Apple brand, and my budget is around $2000. "
                        + "It should be lightweight for travel.";

        System.out.println("Query: " + query);
        System.out.println("\nRequesting structured output...\n");

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "Extract the product requirements from this query: "
                                                        + query)
                                        .build())
                        .build();

        try {
            Flux<Event> eventFlux =
                    agent.stream(userMsg, StreamOptions.defaults(), ProductRequirements.class);
            ProductRequirements result =
                    eventFlux.blockLast().getMessage().getStructuredData(ProductRequirements.class);

            System.out.println("Extracted structured data:");
            System.out.println("  Product Type: " + result.productType);
            System.out.println("  Brand: " + result.brand);
            System.out.println("  Min RAM: " + result.minRam + " GB");
            System.out.println("  Max Budget: $" + result.maxBudget);
            System.out.println("  Features: " + result.features);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== Structured Output Schema Classes ====================

    /** Schema for product requirements extraction. */
    public static class ProductRequirements {
        public String productType;
        public String brand;
        public Integer minRam;
        public Double maxBudget;
        public List<String> features;

        public ProductRequirements() {}
    }

    /** Schema for contact information extraction. */
    public static class ContactInfo {
        public String name;
        public String email;
        public String phone;
        public String company;

        public ContactInfo() {}
    }

    /** Schema for sentiment analysis results. */
    public static class SentimentAnalysis {
        public String overallSentiment; // "positive", "negative", or "neutral"
        public Double positiveScore; // 0.0 to 1.0
        public Double negativeScore; // 0.0 to 1.0
        public Double neutralScore; // 0.0 to 1.0
        public List<String> keyTopics;
        public String summary;

        public SentimentAnalysis() {}
    }
}
