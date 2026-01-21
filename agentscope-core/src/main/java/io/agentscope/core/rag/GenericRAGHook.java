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
package io.agentscope.core.rag;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Generic RAG Hook for automatic knowledge retrieval before reasoning.
 *
 * <p>This hook implements the Generic RAG mode, where knowledge is automatically retrieved
 * and injected into the prompt before each reasoning step. Unlike Agentic mode (where agents
 * decide when to retrieve), Generic mode always retrieves relevant knowledge for user queries.
 *
 * <p>This hook intercepts {@link PreReasoningEvent} and:
 * <ol>
 *   <li>Extracts the query from user messages</li>
 *   <li>Retrieves relevant documents from the knowledge base</li>
 *   <li>Injects the retrieved knowledge as a system message</li>
 *   <li>Modifies the input messages to include the knowledge context</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * KnowledgeBase knowledgeBase = new SimpleKnowledge(embeddingModel, vectorStore);
 * GenericRAGHook ragHook = new GenericRAGHook(knowledgeBase);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(chatModel)
 *     .hook(ragHook)
 *     .build();
 * }</pre>
 *
 * <p>Configuration options:
 * <ul>
 *   <li>{@code defaultConfig} - Retrieval configuration (limit, score threshold)</li>
 * </ul>
 */
public class GenericRAGHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(GenericRAGHook.class);

    private final Knowledge knowledge;
    private final RetrieveConfig defaultConfig;

    /**
     * Creates a GenericRAGHook with default configuration.
     *
     * <p>Default configuration:
     * <ul>
     *   <li>Limit: 5 documents</li>
     *   <li>Score threshold: 0.5</li>
     * </ul>
     *
     * @param knowledge the knowledge base to retrieve from
     * @throws IllegalArgumentException if knowledgeBase is null
     */
    public GenericRAGHook(Knowledge knowledge) {
        this(knowledge, RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build());
    }

    /**
     * Creates a GenericRAGHook with custom configuration.
     *
     * @param knowledge the knowledge base to retrieve from
     * @param defaultConfig the default retrieval configuration
     * @throws IllegalArgumentException if knowledgeBase is null
     */
    public GenericRAGHook(Knowledge knowledge, RetrieveConfig defaultConfig) {
        if (knowledge == null) {
            throw new IllegalArgumentException("Knowledge base cannot be null");
        }
        if (defaultConfig == null) {
            throw new IllegalArgumentException("Default config cannot be null");
        }
        this.knowledge = knowledge;
        this.defaultConfig = defaultConfig;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePreCall(preCallEvent);
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
     * Handles PreCallEvent by retrieving knowledge and enhancing messages.
     *
     * @param event the PreReasoningEvent
     * @return Mono containing the potentially modified event
     */
    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        List<Msg> inputMessages = event.getInputMessages();
        if (inputMessages == null || inputMessages.isEmpty()) {
            return Mono.just(event);
        }

        // Extract query text from messages (finds the last user message from back to front)
        String query = extractQueryFromMessages(inputMessages);
        if (query == null || query.trim().isEmpty()) {
            return Mono.just(event);
        }

        // Retrieve relevant documents
        return knowledge
                .retrieve(query, defaultConfig)
                .flatMap(
                        retrievedDocs -> {
                            if (retrievedDocs == null || retrievedDocs.isEmpty()) {
                                return Mono.just(event);
                            }
                            List<Msg> enhancedMessages = new ArrayList<>();
                            // Build enhanced messages with knowledge context
                            Msg enhancedMessage = createEnhancedMessages(retrievedDocs);
                            enhancedMessages.addAll(inputMessages);
                            enhancedMessages.add(enhancedMessage);
                            event.setInputMessages(enhancedMessages);
                            return Mono.just(event);
                        })
                .onErrorResume(
                        error -> {
                            // Log error but don't interrupt the flow
                            log.warn("Generic RAG retrieval failed: {}", error.getMessage(), error);
                            return Mono.just(event);
                        });
    }

    /**
     * Extracts query text from message list.
     *
     * <p>Finds the last user message as the query source (not just the last message, which could be
     * ASSISTANT or TOOL in ReAct loops).
     *
     * @param messages the message list
     * @return the extracted query text, or empty string if no user message found
     */
    private String extractQueryFromMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        // Find the last user message (not just the last message, which could be
        // ASSISTANT or TOOL in ReAct loops)
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return "";
    }

    /**
     * Creates enhanced message list with knowledge context injected.
     *
     * <p>The knowledge is injected as a system message at the beginning of the message list.
     *
     * @param retrievedDocs the retrieved documents
     * @return the enhanced message list with knowledge context
     */
    private Msg createEnhancedMessages(List<Document> retrievedDocs) {
        String knowledgeContent = buildKnowledgeContent(retrievedDocs);

        return Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(knowledgeContent).build())
                .build();
    }

    /**
     * Builds knowledge content string from retrieved documents.
     *
     * <p>Formats documents with scores and content for inclusion in the prompt.
     *
     * @param documents the retrieved documents
     * @return the formatted knowledge content string
     */
    private String buildKnowledgeContent(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "<retrieved_knowledge>Use the following content from the knowledge base(s) if it is"
                        + " helpful:\n\n");
        for (Document doc : documents) {
            sb.append("- Score: ")
                    .append(String.format("%.3f", doc.getScore() != null ? doc.getScore() : 0.0))
                    .append(", ");
            sb.append("Content: ").append(doc.getMetadata().getContentText()).append("\n");
        }
        sb.append("</retrieved_knowledge>");

        return sb.toString();
    }

    /**
     * Gets the knowledge base used by this hook.
     *
     * @return the knowledge base
     */
    public Knowledge getKnowledgeBase() {
        return knowledge;
    }

    /**
     * Gets the default retrieval configuration.
     *
     * @return the default config
     */
    public RetrieveConfig getDefaultConfig() {
        return defaultConfig;
    }
}
