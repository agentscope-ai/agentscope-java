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
package io.agentscope.core.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Tool adapter that exposes long-term memory operations as agent-callable tools.
 *
 * <p>This class provides a clean separation between the core memory API (defined in
 * {@link LongTermMemory}) and the tool interface used by agents. It adapts the
 * developer-facing {@code record()} and {@code retrieve()} methods into tool functions
 * with agent-friendly signatures.
 *
 * <p><b>Architecture:</b>
 * <ul>
 *   <li><b>LongTermMemoryBase:</b> Defines core storage API ({@code record()}, {@code retrieve()})
 *   <li><b>LongTermMemoryTools:</b> Adapts core API to tool interface for agent control
 *   <li><b>ReActAgent:</b> Registers tools when {@code AGENT_CONTROL} mode is enabled
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create long-term memory instance
 * LongTermMemoryBase memory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userName("user_123")
 *     .build();
 *
 * // Create tool adapter
 * LongTermMemoryTools tools = new LongTermMemoryTools(memory);
 *
 * // Register in ReActAgent (done automatically by framework)
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .longTermMemory(memory)
 *     .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
 *     .build();
 * }</pre>
 *
 * @see LongTermMemory
 * @see LongTermMemoryMode
 */
public class LongTermMemoryTools {

    private final LongTermMemory memory;

    /**
     * Creates a new tool adapter for the given long-term memory instance.
     *
     * @param memory The long-term memory instance to adapt
     * @throws IllegalArgumentException if memory is null
     */
    public LongTermMemoryTools(LongTermMemory memory) {
        if (memory == null) {
            throw new IllegalArgumentException("Long-term memory cannot be null");
        }
        this.memory = memory;
    }

    /*
     * Wraps the given text in a special format to indicate that it is retrieved from long-term memory.
     */
    public static String wrap(String text) {
        return "Below is content retrieved from the long-term memory associated with the current"
                + " user, Please extract useful information from it in the context of the"
                + " current conversation.\n"
                + "<long_term_memory>\n"
                + text
                + "\n</long_term_memory>";
    }

    /**
     * Tool function for agent to record important information to long-term memory.
     *
     * @param thinking Agent's reasoning about what to record and why
     * @param content List of specific facts to remember (should be clear and concise)
     * @return A status message indicating success or failure
     */
    @Tool(
            description =
                    "Record important information to long-term memory for future reference. Use"
                            + " this when the user shares preferences, personal information, or"
                            + " important facts that should be remembered across conversations.")
    public Mono<String> recordToMemory(
            @ToolParam(
                            name = "thinking",
                            description = "Your reasoning about what to record and why")
                    String thinking,
            @ToolParam(
                            name = "content",
                            description =
                                    "List of specific facts to remember. Each item should be clear"
                                            + " and concise.")
                    List<String> content) {
        if (content == null || content.isEmpty()) {
            return Mono.just("No content provided to record");
        }

        // Combine thinking and content into messages
        List<Msg> messages = new ArrayList<>();

        if (thinking != null && !thinking.isEmpty()) {
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(thinking).build())
                            .build());
        }

        for (String item : content) {
            if (item != null && !item.isEmpty()) {
                messages.add(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(item).build())
                                .build());
            }
        }

        if (messages.isEmpty()) {
            return Mono.just("No valid content to record");
        }

        // Call underlying memory record method
        return memory.record(messages)
                .then(Mono.just("Successfully recorded to long-term memory"))
                .onErrorResume(e -> Mono.just("Error recording memory: " + e.getMessage()));
    }

    /**
     * Tool function for agent to retrieve information from long-term memory.
     *
     * <p>This method adapts the agent's keyword input into a query message and calls
     * the underlying memory's {@link LongTermMemory#retrieve(Msg)} method.
     *
     * <p><b>When to use:</b>
     * <ul>
     *   <li>User asks about past preferences or information
     *   <li>Agent needs context from previous conversations
     *   <li>Looking for patterns or historical data
     *   <li>Verifying stored information
     * </ul>
     *
     * <p><b>Example Agent Usage:</b>
     * <pre>{@code
     * {
     *   "name": "retrieve_from_memory",
     *   "input": {
     *     "keywords": ["travel", "Hangzhou", "preferences"]
     *   }
     * }
     * }</pre>
     *
     * @param keywords List of keywords to search for (should be specific and relevant)
     * @return The retrieved memories as text
     */
    @Tool(
            description =
                    "Retrieve information from long-term memory based on keywords. Use this to"
                            + " recall user preferences, past conversations, or important facts.")
    public Mono<String> retrieveFromMemory(
            @ToolParam(
                            name = "keywords",
                            description =
                                    "Keywords to search for in memory. Be specific (e.g., person"
                                            + " names, dates, locations).")
                    List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Mono.just("No keywords provided for search");
        }

        // Combine keywords into a query message
        String query = String.join(" ", keywords);
        Msg queryMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(query).build())
                        .build();

        // Call underlying memory retrieve method
        return memory.retrieve(queryMsg)
                .map(
                        result -> {
                            if (result == null || result.isEmpty()) {
                                return "No relevant memories found";
                            }
                            return wrap(result);
                        })
                .onErrorResume(e -> Mono.just("Error retrieving memory: " + e.getMessage()));
    }
}
