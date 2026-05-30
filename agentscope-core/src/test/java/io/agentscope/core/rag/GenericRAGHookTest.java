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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("GenericRAGHook Tests")
class GenericRAGHookTest {

    @Test
    @DisplayName("Should validate constructor arguments and expose configuration")
    void shouldValidateConstructorArgumentsAndExposeConfiguration() {
        Knowledge knowledge = mock(Knowledge.class);
        RetrieveConfig config = RetrieveConfig.builder().limit(2).scoreThreshold(0.8).build();

        GenericRAGHook hook = new GenericRAGHook(knowledge, config);

        assertSame(knowledge, hook.getKnowledgeBase());
        assertSame(config, hook.getDefaultConfig());
        assertEquals(50, hook.priority());
        assertThrows(IllegalArgumentException.class, () -> new GenericRAGHook(null));
        assertThrows(IllegalArgumentException.class, () -> new GenericRAGHook(knowledge, null));
    }

    @Test
    @DisplayName("Should enhance pre-call messages with retrieved text and image blocks")
    void shouldEnhancePreCallMessagesWithRetrievedBlocks() {
        Knowledge knowledge = mock(Knowledge.class);
        RetrieveConfig config = RetrieveConfig.builder().limit(1).build();
        GenericRAGHook hook = new GenericRAGHook(knowledge, config);
        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT).textContent("previous").build();
        Msg user = Msg.builder().role(MsgRole.USER).textContent("latest question").build();
        PreCallEvent event = new PreCallEvent(mock(Agent.class), List.of(assistant, user));
        Document textDoc = document(TextBlock.builder().text("text knowledge").build(), 0.91);
        ImageBlock image =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/a.png").build())
                        .build();
        Document imageDoc = document(image, null);
        when(knowledge.retrieve(eq("latest question"), eq(config)))
                .thenReturn(Mono.just(List.of(textDoc, imageDoc)));

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        enhanced -> {
                            assertEquals(3, enhanced.getInputMessages().size());
                            Msg knowledgeMessage = enhanced.getInputMessages().get(2);
                            assertEquals(MsgRole.USER, knowledgeMessage.getRole());
                            assertEquals("user", knowledgeMessage.getName());
                            assertInstanceOf(TextBlock.class, knowledgeMessage.getContent().get(0));
                            assertEquals(image, knowledgeMessage.getContent().get(1));
                            assertInstanceOf(TextBlock.class, knowledgeMessage.getContent().get(2));
                        })
                .verifyComplete();
        verify(knowledge).retrieve("latest question", config);
    }

    @Test
    @DisplayName("Should preserve retrieved documents with null content")
    void shouldPreserveRetrievedDocumentsWithNullContent() {
        Knowledge knowledge = mock(Knowledge.class);
        GenericRAGHook hook = new GenericRAGHook(knowledge);
        PreCallEvent event =
                new PreCallEvent(
                        mock(Agent.class),
                        List.of(Msg.builder().role(MsgRole.USER).textContent("query").build()));
        DocumentMetadata metadata = mock(DocumentMetadata.class);
        when(metadata.getDocId()).thenReturn("doc-null");
        when(metadata.getChunkId()).thenReturn("chunk-null");
        when(metadata.getContent()).thenReturn(null);
        Document nullContentDoc = new Document(metadata);
        nullContentDoc.setScore(0.4);
        when(knowledge.retrieve(eq("query"), eq(hook.getDefaultConfig())))
                .thenReturn(Mono.just(List.of(nullContentDoc)));

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        enhanced -> {
                            Msg knowledgeMessage = enhanced.getInputMessages().get(1);
                            assertInstanceOf(TextBlock.class, knowledgeMessage.getContent().get(0));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should keep event unchanged when retrieval is skipped or fails")
    void shouldKeepEventUnchangedWhenRetrievalIsSkippedOrFails() {
        Knowledge knowledge = mock(Knowledge.class);
        GenericRAGHook hook = new GenericRAGHook(knowledge);
        PreCallEvent emptyUser =
                new PreCallEvent(
                        mock(Agent.class),
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .textContent("no user")
                                        .build()));

        StepVerifier.create(hook.onEvent(emptyUser)).expectNext(emptyUser).verifyComplete();

        PreCallEvent event =
                new PreCallEvent(
                        mock(Agent.class),
                        List.of(Msg.builder().role(MsgRole.USER).textContent("query").build()));
        when(knowledge.retrieve(eq("query"), eq(hook.getDefaultConfig())))
                .thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
        assertEquals(1, event.getInputMessages().size());
    }

    @Test
    @DisplayName("Should ignore non-pre-call hook events")
    void shouldIgnoreNonPreCallHookEvents() {
        Knowledge knowledge = mock(Knowledge.class);
        GenericRAGHook hook = new GenericRAGHook(knowledge);
        HookEvent event =
                new PostCallEvent(
                        mock(Agent.class),
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build());

        StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
    }

    private Document document(TextBlock text, Double score) {
        return document((io.agentscope.core.message.ContentBlock) text, score);
    }

    private Document document(io.agentscope.core.message.ContentBlock content, Double score) {
        Document doc =
                new Document(
                        DocumentMetadata.builder()
                                .docId("doc")
                                .chunkId("chunk-" + content.hashCode())
                                .content(content)
                                .build());
        doc.setScore(score);
        return doc;
    }
}
