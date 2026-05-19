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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link GenericRAGHook} message extraction logic.
 *
 * <p>These tests use a mocked {@link Knowledge} to isolate the hook's
 * query-extraction behavior, in particular the skipping of hook-injected
 * USER messages (name="long_term_memory").
 */
@Tag("unit")
@DisplayName("GenericRAGHook Unit Tests (core)")
class GenericRAGHookTest {

    private Knowledge mockKnowledge;
    private GenericRAGHook hook;
    private AgentBase mockAgent;

    @BeforeEach
    void setUp() {
        mockKnowledge = mock(Knowledge.class);
        hook = new GenericRAGHook(mockKnowledge);
        mockAgent =
                new AgentBase("MockAgent") {
                    @Override
                    protected Mono<Msg> doCall(List<Msg> msgs) {
                        return Mono.just(msgs.get(0));
                    }

                    @Override
                    protected Mono<Void> doObserve(Msg msg) {
                        return Mono.empty();
                    }

                    @Override
                    protected Mono<Msg> handleInterrupt(
                            InterruptContext context, Msg... originalArgs) {
                        return Mono.just(
                                Msg.builder()
                                        .name(getName())
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder().text("Interrupted").build())
                                        .build());
                    }
                };
    }

    @Test
    @DisplayName("Should skip long_term_memory message and use real user query for retrieval")
    void testSkipsLongTermMemoryMessageForQuery() {
        Document doc = createDocument("doc1", "Refund policy: 30 days return");
        when(mockKnowledge.retrieve(anyString(), any(RetrieveConfig.class)))
                .thenReturn(Mono.just(List.of(doc)));

        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is the refund policy?").build())
                        .build());
        // Simulate StaticLongTermMemoryHook having already injected its message
        inputMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("long_term_memory")
                        .content(
                                TextBlock.builder()
                                        .text("<long_term_memory>some memory</long_term_memory>")
                                        .build())
                        .build());

        PreCallEvent event = new PreCallEvent(mockAgent, inputMessages);

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        result -> {
                            List<Msg> messages = result.getInputMessages();
                            // original user msg + long_term_memory + RAG knowledge = 3
                            assertEquals(3, messages.size());
                            // The injected knowledge message must have "retrieved_knowledge" name
                            assertEquals("retrieved_knowledge", messages.get(2).getName());
                            assertEquals(MsgRole.USER, messages.get(2).getRole());
                            assertTrue(
                                    messages.get(2).getTextContent().contains("retrieved_knowledge")
                                            || messages.get(2)
                                                    .getTextContent()
                                                    .contains("knowledge base"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return unchanged messages when only long_term_memory USER msg exists")
    void testNoRealUserQueryWhenOnlyInjectedMessagesExist() {
        List<Msg> inputMessages = new ArrayList<>();
        // Only a hook-injected message — no genuine user input
        inputMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("long_term_memory")
                        .content(
                                TextBlock.builder()
                                        .text("<long_term_memory>memory content</long_term_memory>")
                                        .build())
                        .build());

        PreCallEvent event = new PreCallEvent(mockAgent, inputMessages);

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        result -> {
                            // No real user query found: message list must remain unchanged
                            assertEquals(1, result.getInputMessages().size());
                        })
                .verifyComplete();
    }

    private Document createDocument(String docId, String content) {
        TextBlock textBlock = TextBlock.builder().text(content).build();
        DocumentMetadata metadata = new DocumentMetadata(textBlock, docId, "0");
        return new Document(metadata);
    }
}
