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

import static io.agentscope.core.memory.LongTermMemoryTools.wrap;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Static Long-Term Memory Hook for automatic memory management.
 *
 * <p>This hook implements the STATIC_CONTROL mode for long-term memory, where memory
 * retrieval and recording are handled automatically by the framework without agent
 * involvement. The hook:
 * <ol>
 *   <li>Retrieves relevant memories before reasoning (PreReasoningEvent)</li>
 *   <li>Injects retrieved memories as system messages for context</li>
 *   <li>Records conversations to long-term memory after agent replies (PostCallEvent)</li>
 * </ol>
 *
 * <p><b>When to Use:</b>
 * <ul>
 *   <li>STATIC_CONTROL mode: Framework manages memory automatically</li>
 *   <li>BOTH mode: Combined with agent control tools</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * LongTermMemory memory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userName("user_123")
 *     .build();
 *
 * StaticLongTermMemoryHook hook = new StaticLongTermMemoryHook(memory, agentMemory);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .hook(hook)  // Hook is automatically registered in STATIC_CONTROL/BOTH mode
 *     .build();
 * }</pre>
 *
 * <p><b>Priority:</b> This hook has high priority (50) to ensure memory is retrieved
 * early in the event chain, before other processing occurs.
 *
 * @see LongTermMemory
 * @see LongTermMemoryMode
 */
public class StaticLongTermMemoryHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(StaticLongTermMemoryHook.class);

    private final LongTermMemory longTermMemory;
    private final Memory memory;

    /**
     * Creates a new StaticLongTermMemoryHook.
     *
     * @param longTermMemory The long-term memory instance for persistent storage
     * @param memory The agent's memory for accessing conversation history
     * @throws IllegalArgumentException if longTermMemory or memory is null
     */
    public StaticLongTermMemoryHook(LongTermMemory longTermMemory, Memory memory) {
        if (longTermMemory == null) {
            throw new IllegalArgumentException("Long-term memory cannot be null");
        }
        if (memory == null) {
            throw new IllegalArgumentException("Memory cannot be null");
        }
        this.longTermMemory = longTermMemory;
        this.memory = memory;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePreCall(preCallEvent);
            return result;
        } else if (event instanceof PostCallEvent postCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePostCall(postCallEvent);
            return result;
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // High priority to execute early in the hook chain
        return 50;
    }

    /**
     * Handles PreReasoningEvent by retrieving relevant memories and injecting them.
     *
     * <p>Retrieves memories relevant to the user's query and injects them as a system
     * message at the beginning of the message list. The memories are wrapped in
     * {@code <long_term_memory>} tags for clear identification.
     *
     * @param event the PreReasoningEvent
     * @return Mono containing the potentially modified event
     */
    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        List<Msg> inputMessages = event.getInputMessages();
        if (inputMessages == null || inputMessages.isEmpty()) {
            return Mono.just(event);
        }
        // Extract the last user message as the query
        int queryMsgIndex = extractLastUserMessageIndex(inputMessages);
        if (queryMsgIndex < 0) {
            return Mono.just(event);
        }

        // Retrieve relevant memories
        return longTermMemory
                .retrieve(inputMessages.get(queryMsgIndex))
                .filter(memoryText -> memoryText != null && !memoryText.isEmpty())
                .flatMap(
                        memoryText -> {
                            // Wrap memory content in tags
                            String wrappedMemory = wrap(memoryText);

                            // Create system message with retrieved memories
                            Msg memoryMsg =
                                    Msg.builder()
                                            .role(MsgRole.SYSTEM)
                                            .name("long_term_memory")
                                            .content(
                                                    TextBlock.builder().text(wrappedMemory).build())
                                            .build();

                            // Inject memory message at the beginning
                            List<Msg> enhancedMessages = new ArrayList<>();
                            enhancedMessages.addAll(inputMessages);
                            enhancedMessages.add(memoryMsg);
                            event.setInputMessages(enhancedMessages);

                            return Mono.just(event);
                        })
                .defaultIfEmpty(event)
                .onErrorResume(
                        error -> {
                            // Log error but don't interrupt the flow
                            log.warn(
                                    "Failed to retrieve from long-term memory: {}",
                                    error.getMessage());
                            return Mono.just(event);
                        });
    }

    /**
     * Handles PostCallEvent by recording conversation to long-term memory.
     *
     * <p>Records all messages from the agent's memory to long-term storage. This allows
     * the long-term memory backend (e.g., Mem0) to extract memorable information from
     * the entire conversation context.
     *
     * @param event the PostCallEvent
     * @return Mono containing the unmodified event
     */
    private Mono<PostCallEvent> handlePostCall(PostCallEvent event) {
        // Get all messages from memory
        List<Msg> allMessages = memory.getMessages();
        if (allMessages == null || allMessages.isEmpty()) {
            return Mono.just(event);
        }

        // Record to long-term memory
        return longTermMemory
                .record(allMessages)
                .thenReturn(event)
                .onErrorResume(
                        error -> {
                            // Log error but don't interrupt the flow
                            log.warn(
                                    "Failed to record to long-term memory: {}", error.getMessage());
                            return Mono.just(event);
                        });
    }

    /**
     * Extracts the last user message from the message list.
     *
     * <p>Scans the message list from end to start to find the most recent user message,
     * which is typically the current query that should be used for memory retrieval.
     *
     * @param messages the message list
     * @return the last user message, or null if none found
     */
    private int extractLastUserMessageIndex(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Gets the long-term memory instance used by this hook.
     *
     * @return the long-term memory instance
     */
    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    /**
     * Gets the memory instance used by this hook.
     *
     * @return the memory instance
     */
    public Memory getMemory() {
        return memory;
    }
}
