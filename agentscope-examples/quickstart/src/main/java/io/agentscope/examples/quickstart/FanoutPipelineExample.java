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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.FanoutPipeline;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.quickstart.util.MsgUtils;
import java.time.Duration;
import java.util.List;

/**
 * FanoutPipelineExample - Multi-expert parallel review demonstration.
 */
public class FanoutPipelineExample {

    // Sample product idea for demonstration
    private static final String PRODUCT_IDEA =
            "A mobile app that uses AI to analyze your daily photos and automatically generates a"
                    + " personalized video diary with music and captions. Users can review their"
                    + " week/month/year in a beautifully edited video format. The app learns user"
                    + " preferences over time to improve video style and music selection.";

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Fanout Pipeline Example",
                "This example demonstrates parallel processing using FanoutPipeline.\n"
                        + "A product idea will be reviewed by 3 independent experts:\n"
                        + "  1. Technical Expert (feasibility assessment)\n"
                        + "  2. UX Expert (user experience evaluation)\n"
                        + "  3. Business Analyst (market value analysis)\n\n"
                        + "You'll see both concurrent and sequential execution for comparison!");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        System.out.println("Setting up expert review panel...\n");

        // Create three expert agents
        ReActAgent techExpert = createTechExpert(apiKey);
        ReActAgent uxExpert = createUXExpert(apiKey);
        ReActAgent bizAnalyst = createBusinessAnalyst(apiKey);

        // Build concurrent fanout pipeline (default behavior)
        FanoutPipeline concurrentPipeline =
                FanoutPipeline.builder()
                        .addAgent(techExpert)
                        .addAgent(uxExpert)
                        .addAgent(bizAnalyst)
                        .concurrent() // Explicit concurrent mode
                        .build();

        // Build sequential fanout pipeline for comparison
        FanoutPipeline sequentialPipeline =
                FanoutPipeline.builder()
                        .addAgent(techExpert)
                        .addAgent(uxExpert)
                        .addAgent(bizAnalyst)
                        .sequential() // Sequential execution
                        .build();

        System.out.println("Created two pipelines:");
        System.out.println("  [Concurrent] All 3 experts review in parallel");
        System.out.println("  [Sequential] Experts review one after another\n");

        // Create input message with product idea
        Msg inputMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(PRODUCT_IDEA).build())
                        .build();

        // Display product idea
        printSeparator();
        System.out.println("PRODUCT IDEA:");
        printSeparator();
        System.out.println(PRODUCT_IDEA);
        System.out.println();

        // Execute concurrent pipeline
        System.out.println("Executing CONCURRENT pipeline (parallel reviews)...\n");
        long startConcurrent = System.currentTimeMillis();

        List<Msg> concurrentResults =
                concurrentPipeline.execute(inputMsg).block(Duration.ofMinutes(3));

        long timeConcurrent = System.currentTimeMillis() - startConcurrent;

        // Display concurrent results
        displayExpertReviews("CONCURRENT MODE", concurrentResults, timeConcurrent);

        // Execute sequential pipeline
        System.out.println("\nExecuting SEQUENTIAL pipeline (one-by-one reviews)...\n");
        long startSequential = System.currentTimeMillis();

        List<Msg> sequentialResults =
                sequentialPipeline.execute(inputMsg).block(Duration.ofMinutes(3));

        long timeSequential = System.currentTimeMillis() - startSequential;

        // Display sequential results
        displayExpertReviews("SEQUENTIAL MODE", sequentialResults, timeSequential);

        // Display performance comparison
        displayPerformanceComparison(timeConcurrent, timeSequential);
    }

    /**
     * Create a technical expert agent.
     *
     * @param apiKey DashScope API key
     * @return Configured technical expert agent
     */
    private static ReActAgent createTechExpert(String apiKey) {
        return ReActAgent.builder()
                .name("TechnicalExpert")
                .sysPrompt(
                        "You are a senior technical expert specializing in software architecture"
                            + " and AI systems. Review the product idea from a TECHNICAL"
                            + " FEASIBILITY perspective. Your review should cover:\n"
                            + "1. Technical challenges and solutions\n"
                            + "2. Required technologies and frameworks\n"
                            + "3. Development complexity (1-10 scale)\n"
                            + "4. Potential technical risks\n"
                            + "Keep your review concise (3-5 sentences). Start your response with"
                            + " 'TECHNICAL REVIEW:'")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .memory(new InMemoryMemory())
                .toolkit(new Toolkit())
                .build();
    }

    /**
     * Create a UX expert agent.
     *
     * @param apiKey DashScope API key
     * @return Configured UX expert agent
     */
    private static ReActAgent createUXExpert(String apiKey) {
        return ReActAgent.builder()
                .name("UXExpert")
                .sysPrompt(
                        "You are a UX/UI design expert with deep understanding of user psychology. "
                                + "Review the product idea from a USER EXPERIENCE perspective. "
                                + "Your review should cover:\n"
                                + "1. User value proposition\n"
                                + "2. Usability and intuitiveness\n"
                                + "3. User engagement potential (1-10 scale)\n"
                                + "4. Key UX challenges or concerns\n"
                                + "Keep your review concise (3-5 sentences). "
                                + "Start your response with 'UX REVIEW:'")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .memory(new InMemoryMemory())
                .toolkit(new Toolkit())
                .build();
    }

    /**
     * Create a business analyst agent.
     *
     * @param apiKey DashScope API key
     * @return Configured business analyst agent
     */
    private static ReActAgent createBusinessAnalyst(String apiKey) {
        return ReActAgent.builder()
                .name("BusinessAnalyst")
                .sysPrompt(
                        "You are a business strategy analyst with expertise in market analysis. "
                                + "Review the product idea from a BUSINESS VALUE perspective. "
                                + "Your review should cover:\n"
                                + "1. Target market and audience\n"
                                + "2. Monetization potential\n"
                                + "3. Market opportunity score (1-10 scale)\n"
                                + "4. Competitive advantages or disadvantages\n"
                                + "Keep your review concise (3-5 sentences). "
                                + "Start your response with 'BUSINESS REVIEW:'")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .memory(new InMemoryMemory())
                .toolkit(new Toolkit())
                .build();
    }

    /**
     * Display expert reviews from the pipeline results.
     *
     * @param mode Execution mode (concurrent or sequential)
     * @param results List of messages from experts
     * @param executionTime Time taken to execute
     */
    private static void displayExpertReviews(String mode, List<Msg> results, long executionTime) {
        printSeparator();
        System.out.println("RESULTS - " + mode);
        printSeparator();

        if (results == null || results.isEmpty()) {
            System.out.println("[No results returned]");
            return;
        }

        String[] expertNames = {"Technical Expert", "UX Expert", "Business Analyst"};

        for (int i = 0; i < results.size() && i < expertNames.length; i++) {
            System.out.println("\n[" + (i + 1) + "] " + expertNames[i] + ":");
            System.out.println("-".repeat(70));
            String review = MsgUtils.getTextContent(results.get(i));
            System.out.println(review);
        }

        System.out.println("\n" + "-".repeat(70));
        System.out.println("Execution time: " + executionTime + "ms");
    }

    /**
     * Display performance comparison between concurrent and sequential execution.
     *
     * @param timeConcurrent Time for concurrent execution
     * @param timeSequential Time for sequential execution
     */
    private static void displayPerformanceComparison(long timeConcurrent, long timeSequential) {
        printSeparator();
        System.out.println("PERFORMANCE COMPARISON");
        printSeparator();

        System.out.printf("Concurrent execution: %dms%n", timeConcurrent);
        System.out.printf("Sequential execution: %dms%n", timeSequential);

        if (timeConcurrent > 0) {
            double speedup = (double) timeSequential / timeConcurrent;
            System.out.printf("Speedup: %.2fx faster with concurrent execution!%n", speedup);

            if (speedup >= 2.0) {
                System.out.println("\nExcellent parallelization efficiency!");
            } else if (speedup >= 1.2) {
                System.out.println("\nGood performance improvement with parallel execution.");
            } else {
                System.out.println(
                        "\nNote: Speedup may vary based on network latency and API response"
                                + " times.");
            }
        }

        System.out.println(
                "\nThis demonstrates the power of FanoutPipeline for parallel processing!\n"
                        + "When agents work independently on the same input, concurrent execution\n"
                        + "can significantly reduce total processing time.\n");
    }

    /** Print a separator line for visual clarity. */
    private static void printSeparator() {
        System.out.println("=".repeat(70));
    }
}
