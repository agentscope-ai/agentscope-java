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
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Scanner;

/**
 * AutoContextMemoryExample - Demonstrates large message compression with AutoContextMemory.
 *
 * <p>This example shows how AutoContextMemory automatically compresses large messages,
 * including tool call and tool result messages, to prevent model errors and optimize
 * context window usage.
 *
 * <p>Key features demonstrated:
 * <ul>
 *   <li>Automatic compression when message count exceeds threshold</li>
 *   <li>Large tool result message handling and conversion to text format</li>
 *   <li>Context preservation during compression</li>
 *   <li>Interactive chat with automatic compression</li>
 * </ul>
 */
public class AutoContextMemoryExample {

    static {
        // Enable SLF4J simple logger with INFO level
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
    }

    public static void main(String[] args) throws Exception {
        // Print welcome message
        System.out.println("=".repeat(80));
        System.out.println("AutoContextMemory Example - Large Message Compression");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.println("This example demonstrates automatic compression of large messages,");
        System.out.println("including tool call/result messages that could trigger model errors.");
        System.out.println();
        System.out.println("Features:");
        System.out.println("  - Automatic compression when message count exceeds threshold");
        System.out.println("  - Tool message conversion to text format during compression");
        System.out.println("  - Context preservation with offloading");
        System.out.println();

        // Get API key
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println(
                    "Error: API key is required. Please set DASHSCOPE_API_KEY"
                            + " environment variable or enter it manually.");
            return;
        }

        // Create toolkit with demo tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new LargeDataTools());

        System.out.println("Registered tools:");
        System.out.println("  - search_data: Simulate search returning large results");
        System.out.println("  - analyze_data: Simulate data analysis with large output");
        System.out.println("  - fetch_document: Simulate document retrieval with large content");
        System.out.println();

        // Configure AutoContextMemory with low thresholds for demo
        // To trigger Strategy 5 (current round large message), we need:
        // 1. Disable Strategy 1-4 by setting high thresholds
        // 2. Set low largePayloadThreshold to trigger Strategy 5
        AutoContextConfig config =
                AutoContextConfig.builder()
                        .msgThreshold(6) // Low threshold to trigger compression
                        .maxToken(128 * 1024)
                        .tokenRatio(0.75)
                        .lastKeep(100) // High value to disable Strategy 2 (lastKeep protection)
                        .minConsecutiveToolMessages(100) // High value to disable Strategy 1
                        .largePayloadThreshold(
                                500) // Low threshold for current round large messages
                        .build();

        System.out.println("AutoContextMemory Configuration:");
        System.out.println("  - Message threshold: " + config.getMsgThreshold());
        System.out.println(
                "  - Large payload threshold: " + config.getLargePayloadThreshold() + " chars");
        System.out.println("  - Last keep: " + config.getLastKeep() + " messages");
        System.out.println(
                "  - Min consecutive tool messages: " + config.getMinConsecutiveToolMessages());
        System.out.println();
        System.out.println("Strategy Configuration:");
        System.out.println(
                "  - Strategy 1 (Tool compression): DISABLED (threshold="
                        + config.getMinConsecutiveToolMessages()
                        + ")");
        System.out.println(
                "  - Strategy 2 (Offload with lastKeep): DISABLED (lastKeep="
                        + config.getLastKeep()
                        + ")");
        System.out.println("  - Strategy 3 (Offload without lastKeep): ENABLED");
        System.out.println("  - Strategy 4 (Previous round summary): ENABLED");
        System.out.println("  - Strategy 5 (Current round large msg): ENABLED ← TARGET");
        System.out.println("  - Strategy 6 (Current round summary): ENABLED");
        System.out.println();

        // Create Agent with AutoContextMemory
        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-max").stream(true)
                        .enableThinking(false)
                        .formatter(new DashScopeChatFormatter())
                        .build();

        // Re-create memory with model for compression
        AutoContextMemory memory = new AutoContextMemory(config, model);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("AutoContextAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to data analysis tools."
                                    + " When users ask for information, use the appropriate tools."
                                    + " Be concise in your responses after receiving tool results.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .hook(new AutoContextHook())
                        .build();
        System.out.println("Agent created with AutoContextMemory.");
        System.out.println();

        // Demo mode or interactive mode
        System.out.println("Choose mode:");
        System.out.println("  1. Interactive chat (type your messages)");
        System.out.println("  2. Auto demo (automatic demonstration with preset messages)");
        System.out.print("Enter choice (1 or 2): ");

        Scanner scanner = new Scanner(System.in);
        String choice = scanner.nextLine().trim();

        if ("2".equals(choice)) {
            runAutoDemo(agent, memory);
        } else {
            runInteractiveChat(agent, memory);
        }

        scanner.close();
    }

    /**
     * Run automatic demonstration with preset messages.
     */
    private static void runAutoDemo(ReActAgent agent, AutoContextMemory memory) throws Exception {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("Running Auto Demo...");
        System.out.println("=".repeat(80));
        System.out.println();

        // Preset messages to trigger Strategy 5 (current round large message compression)
        // Strategy 5 triggers when: latest user message has large messages AFTER it
        String[] demoMessages = {
            // Round 1: Normal conversation
            "Hello, I need help with data analysis.",

            // Round 2: Use tool to generate large output
            "Can you search for information about machine learning trends in 2024?",

            // Round 3: Another tool call with large output
            "Please analyze the data and give me a detailed report.",

            // Round 4: Yet another large tool result
            "Now fetch the full document about AI development.",

            // Round 5: This will trigger compression (6 messages reached)
            // The large tool result AFTER this user message will trigger Strategy 5
            "What are the key findings from all the data you collected? Please search for more"
                    + " details.",

            // Round 6: Continue conversation - should trigger compression now
            "Can you search for more details about deep learning?",

            // Round 7: More analysis
            "Please analyze the deep learning data as well.",

            // Round 8: Summary request
            "Summarize everything you've found so far.",

            // Round 9: Final question
            "What are the main conclusions?"
        };

        for (int i = 0; i < demoMessages.length; i++) {
            String userMessage = demoMessages[i];
            System.out.println("─".repeat(80));
            System.out.println("[User Message " + (i + 1) + "/" + demoMessages.length + "]");
            System.out.println(userMessage);
            System.out.println("─".repeat(80));

            // Show memory status before
            int msgCountBefore = memory.getMessages().size();
            System.out.println("[Memory] Messages before: " + msgCountBefore);

            // Process message
            Msg response =
                    agent.call(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .name("user")
                                            .content(TextBlock.builder().text(userMessage).build())
                                            .build())
                            .block();

            // Show memory status after
            int msgCountAfter = memory.getMessages().size();
            System.out.println("[Memory] Messages after: " + msgCountAfter);

            // Check if compression occurred
            if (msgCountAfter < msgCountBefore + 2) {
                System.out.println("[Compression] ⚡ Compression likely triggered!");
            }

            // Check for offload hints
            boolean hasOffload =
                    memory.getMessages().stream()
                            .anyMatch(
                                    msg ->
                                            msg.getTextContent() != null
                                                    && msg.getTextContent()
                                                            .contains("CONTEXT_OFFLOAD"));
            if (hasOffload) {
                System.out.println("[Compression] 📦 Messages have been offloaded to save context");
            }

            // Show response
            if (response != null) {
                String responseText = response.getTextContent();
                if (responseText != null && !responseText.isEmpty()) {
                    System.out.println();
                    System.out.println("[Assistant Response]");
                    // Show first 200 chars of response
                    String preview =
                            responseText.length() > 200
                                    ? responseText.substring(0, 200) + "..."
                                    : responseText;
                    System.out.println(preview);
                }
            }

            System.out.println();
            System.out.println("Offload context entries: " + memory.getOffloadContext().size());
            System.out.println();

            // Small delay for readability
            Thread.sleep(1000);
        }

        System.out.println("=".repeat(80));
        System.out.println("Auto Demo Complete!");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.println("Summary:");
        System.out.println("  - Total messages processed: " + demoMessages.length);
        System.out.println("  - Current working messages: " + memory.getMessages().size());
        System.out.println("  - Offloaded contexts: " + memory.getOffloadContext().size());
        System.out.println("  - Original memory size: " + memory.getOriginalMemoryMsgs().size());
    }

    /**
     * Run interactive chat mode.
     */
    private static void runInteractiveChat(ReActAgent agent, AutoContextMemory memory) {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("Interactive Chat Mode");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.println("Tips:");
        System.out.println(
                "  - Ask the agent to use tools (search_data, analyze_data," + " fetch_document)");
        System.out.println("  - The tools will generate large outputs to trigger compression");
        System.out.println("  - Watch for compression messages as conversation grows");
        System.out.println("  - Type 'status' to see memory statistics");
        System.out.println("  - Type 'quit' or 'exit' to end the chat");
        System.out.println();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("You: ");
            String userInput = scanner.nextLine().trim();

            if (userInput.isEmpty()) {
                continue;
            }

            if ("quit".equalsIgnoreCase(userInput) || "exit".equalsIgnoreCase(userInput)) {
                System.out.println("Goodbye!");
                break;
            }

            // Show status command
            if ("status".equalsIgnoreCase(userInput)) {
                printMemoryStatus(memory);
                continue;
            }

            // Show memory status before
            int msgCountBefore = memory.getMessages().size();

            // Process message
            Msg response =
                    agent.call(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .name("user")
                                            .content(TextBlock.builder().text(userInput).build())
                                            .build())
                            .block();

            // Show memory status after
            int msgCountAfter = memory.getMessages().size();
            if (msgCountAfter != msgCountBefore + 1) {
                System.out.println(
                        "[Compression] ⚡ Compression triggered! "
                                + "Messages: "
                                + msgCountBefore
                                + " → "
                                + msgCountAfter);
            }

            // Check for offload hints
            boolean hasOffload =
                    memory.getMessages().stream()
                            .anyMatch(
                                    msg ->
                                            msg.getTextContent() != null
                                                    && msg.getTextContent()
                                                            .contains("CONTEXT_OFFLOAD"));
            if (hasOffload) {
                System.out.println("[Compression] 📦 Context offloading active");
            }

            // Show response
            if (response != null) {
                String responseText = response.getTextContent();
                if (responseText != null && !responseText.isEmpty()) {
                    System.out.println();
                    System.out.println("Assistant: " + responseText);
                    System.out.println();
                }
            }
        }
    }

    /**
     * Print memory status statistics.
     */
    private static void printMemoryStatus(AutoContextMemory memory) {
        System.out.println();
        System.out.println("─".repeat(80));
        System.out.println("Memory Status:");
        System.out.println("  Working messages: " + memory.getMessages().size());
        System.out.println("  Original messages: " + memory.getOriginalMemoryMsgs().size());
        System.out.println("  Offloaded contexts: " + memory.getOffloadContext().size());

        // Check for compressed messages
        long compressedCount =
                memory.getMessages().stream()
                        .filter(
                                msg ->
                                        msg.getMetadata() != null
                                                && msg.getMetadata().containsKey("_compress_meta"))
                        .count();
        System.out.println("  Compressed messages: " + compressedCount);

        // Show offload context details
        if (!memory.getOffloadContext().isEmpty()) {
            System.out.println("  Offload context details:");
            memory.getOffloadContext()
                    .forEach(
                            (uuid, msgs) ->
                                    System.out.println(
                                            "    - " + uuid + ": " + msgs.size() + " messages"));
        }
        System.out.println("─".repeat(80));
        System.out.println();
    }

    /**
     * Get API key from environment or user input.
     */
    private static String getApiKey() {
        // Try environment variable first
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            return apiKey;
        }

        // Ask user for API key
        System.out.print("Enter your DashScope API key: ");
        Scanner scanner = new Scanner(System.in);
        apiKey = scanner.nextLine().trim();
        return apiKey;
    }

    /**
     * Tools that generate large outputs for testing compression.
     */
    public static class LargeDataTools {

        @Tool(
                name = "search_data",
                description = "Search for information and return detailed results")
        public String searchData(
                @ToolParam(name = "query", description = "Search query") String query) {
            StringBuilder result = new StringBuilder();
            result.append("Search Results for: ").append(query).append("\n\n");

            // Generate large output (500+ characters)
            for (int i = 1; i <= 10; i++) {
                result.append("Result ").append(i).append(":\n");
                result.append("  Title: Comprehensive Analysis of ")
                        .append(query)
                        .append(" - Part ")
                        .append(i)
                        .append("\n");
                result.append("  Summary: This is a detailed analysis covering various aspects of ")
                        .append(query)
                        .append(". ");
                result.append("The research indicates significant developments in this area, ");
                result.append("with multiple studies showing consistent patterns and trends. ");
                result.append("Key findings include statistical correlations, ");
                result.append("comparative analyses across different domains, ");
                result.append("and predictive models for future developments.\n\n");
            }

            result.append("Total results found: 10\n");
            result.append("Search completed successfully.");
            return result.toString();
        }

        @Tool(
                name = "analyze_data",
                description = "Perform detailed data analysis and generate comprehensive report")
        public String analyzeData(
                @ToolParam(name = "data_type", description = "Type of data to analyze")
                        String dataType) {
            StringBuilder result = new StringBuilder();
            result.append("Data Analysis Report for: ").append(dataType).append("\n\n");

            // Generate large analytical output
            result.append("1. Executive Summary\n");
            result.append("   This analysis provides comprehensive insights into ")
                    .append(dataType)
                    .append(".\n");
            result.append("   The findings are based on extensive data collection and ");
            result.append("statistical analysis across multiple dimensions.\n\n");

            result.append("2. Methodology\n");
            result.append("   Data was collected from multiple sources and processed using ");
            result.append("advanced analytical techniques. Quality assurance measures were ");
            result.append(
                    "applied throughout the pipeline to ensure accuracy and reliability.\n\n");

            result.append("3. Key Findings\n");
            for (int i = 1; i <= 8; i++) {
                result.append("   Finding ").append(i).append(": ");
                result.append("Significant pattern detected in ")
                        .append(dataType)
                        .append(" dataset.\n");
                result.append("   - Statistical significance: p < 0.05\n");
                result.append("   - Effect size: Cohen's d = 0.").append(50 + i).append("\n");
                result.append("   - Confidence interval: 95% [")
                        .append(0.3 + i * 0.05)
                        .append(", ")
                        .append(0.8 + i * 0.05)
                        .append("]\n\n");
            }

            result.append("4. Recommendations\n");
            result.append("   Based on the analysis, several actionable recommendations ");
            result.append("have been identified for further investigation and implementation.\n");

            result.append("\nAnalysis completed successfully.");
            return result.toString();
        }

        @Tool(
                name = "fetch_document",
                description = "Retrieve full document content with detailed information")
        public String fetchDocument(
                @ToolParam(name = "document_id", description = "Document identifier")
                        String documentId) {
            StringBuilder result = new StringBuilder();
            result.append("Document Retrieved: ").append(documentId).append("\n\n");

            // Generate large document content
            result.append("CHAPTER 1: INTRODUCTION\n");
            result.append("This document provides a comprehensive overview and detailed analysis ");
            result.append("of the subject matter. The content has been carefully researched and ");
            result.append("compiled to provide accurate and up-to-date information.\n\n");

            for (int chapter = 2; chapter <= 5; chapter++) {
                result.append("CHAPTER ").append(chapter).append(": DETAILED ANALYSIS\n");
                result.append("Section ").append(chapter).append(".1: Background and Context\n");
                result.append("The historical development of this field has been marked by ");
                result.append("significant milestones and breakthrough discoveries.\n\n");

                result.append("Section ").append(chapter).append(".2: Current State\n");
                result.append("Recent advances have transformed our understanding and opened ");
                result.append("new avenues for research and application.\n\n");

                result.append("Section ").append(chapter).append(".3: Future Directions\n");
                result.append("Looking ahead, several promising areas of investigation have ");
                result.append("been identified that warrant further exploration.\n\n");
            }

            result.append("CONCLUSION\n");
            result.append("This document has presented a thorough examination of the topic, ");
            result.append("highlighting key insights and providing recommendations for ");
            result.append("future work.\n\n");

            result.append("Document retrieval completed successfully.");
            return result.toString();
        }
    }
}
