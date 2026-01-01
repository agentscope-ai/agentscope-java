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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Unit tests for {@link LongTermMemoryTools}. */
class LongTermMemoryToolsTest {

    private LongTermMemory mockMemory;
    private LongTermMemoryTools tools;

    @BeforeEach
    void setUp() {
        mockMemory = mock(LongTermMemory.class);
        tools = new LongTermMemoryTools(mockMemory);
    }

    @Test
    void testConstructorWithNullMemory() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> new LongTermMemoryTools(null));

        assertEquals("Long-term memory cannot be null", exception.getMessage());
    }

    @Test
    void testConstructorWithValidMemory() {
        LongTermMemoryTools tools = new LongTermMemoryTools(mockMemory);
        assertNotNull(tools);
    }

    @Test
    void testRecordToMemoryWithThinkingAndContent() {
        when(mockMemory.record(anyList())).thenReturn(Mono.empty());

        String thinking = "User shared their preference";
        List<String> content = List.of("Prefers dark mode", "Likes coffee");

        StepVerifier.create(tools.recordToMemory(thinking, content))
                .expectNext("Successfully recorded to long-term memory")
                .verifyComplete();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockMemory, times(1)).record(captor.capture());

        List<Msg> capturedMessages = captor.getValue();
        assertEquals(3, capturedMessages.size()); // 1 thinking + 2 content items
    }

    @Test
    void testRecordToMemoryWithContentOnly() {
        when(mockMemory.record(anyList())).thenReturn(Mono.empty());

        List<String> content = List.of("User likes tea");

        StepVerifier.create(tools.recordToMemory(null, content))
                .expectNext("Successfully recorded to long-term memory")
                .verifyComplete();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockMemory, times(1)).record(captor.capture());

        List<Msg> capturedMessages = captor.getValue();
        assertEquals(1, capturedMessages.size());
    }

    @Test
    void testRecordToMemoryWithEmptyThinking() {
        when(mockMemory.record(anyList())).thenReturn(Mono.empty());

        String emptyThinking = "";
        List<String> content = List.of("Important fact");

        StepVerifier.create(tools.recordToMemory(emptyThinking, content))
                .expectNext("Successfully recorded to long-term memory")
                .verifyComplete();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockMemory, times(1)).record(captor.capture());

        List<Msg> capturedMessages = captor.getValue();
        assertEquals(1, capturedMessages.size()); // Empty thinking not included
    }

    @Test
    void testRecordToMemoryWithNullContent() {
        StepVerifier.create(tools.recordToMemory("thinking", null))
                .expectNext("No content provided to record")
                .verifyComplete();

        verify(mockMemory, never()).record(anyList());
    }

    @Test
    void testRecordToMemoryWithEmptyContent() {
        StepVerifier.create(tools.recordToMemory("thinking", List.of()))
                .expectNext("No content provided to record")
                .verifyComplete();

        verify(mockMemory, never()).record(anyList());
    }

    @Test
    void testRecordToMemoryWithEmptyStringsInContent() {
        when(mockMemory.record(anyList())).thenReturn(Mono.empty());

        List<String> content = java.util.Arrays.asList("Valid content", "", null, "Another valid");

        StepVerifier.create(tools.recordToMemory("thinking", content))
                .expectNext("Successfully recorded to long-term memory")
                .verifyComplete();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockMemory, times(1)).record(captor.capture());

        List<Msg> capturedMessages = captor.getValue();
        assertEquals(
                3, capturedMessages.size()); // 1 thinking + 2 valid content (empty/null filtered)
    }

    @Test
    void testRecordToMemoryWithOnlyEmptyAndNullContent() {
        // Test with only empty strings and null (no valid content), and no thinking
        List<String> content = java.util.Arrays.asList("", null, "");

        StepVerifier.create(tools.recordToMemory(null, content))
                .expectNext("No valid content to record")
                .verifyComplete();

        verify(mockMemory, never()).record(anyList());
    }

    @Test
    void testRecordToMemoryError() {
        when(mockMemory.record(anyList()))
                .thenReturn(Mono.error(new RuntimeException("Storage error")));

        List<String> content = List.of("Test content");

        StepVerifier.create(tools.recordToMemory("thinking", content))
                .expectNext("Error recording memory: Storage error")
                .verifyComplete();
    }

    @Test
    void testRetrieveFromMemoryWithKeywords() {
        when(mockMemory.retrieve(any(Msg.class))).thenReturn(Mono.just("Relevant memory found"));

        List<String> keywords = List.of("travel", "preferences");

        StepVerifier.create(tools.retrieveFromMemory(keywords))
                .expectNext(LongTermMemoryTools.wrap("Relevant memory found"))
                .verifyComplete();

        ArgumentCaptor<Msg> captor = ArgumentCaptor.forClass(Msg.class);
        verify(mockMemory, times(1)).retrieve(captor.capture());

        Msg capturedMsg = captor.getValue();
        assertNotNull(capturedMsg);
        assertTrue(capturedMsg.getTextContent().contains("travel"));
        assertTrue(capturedMsg.getTextContent().contains("preferences"));
    }

    @Test
    void testRetrieveFromMemoryWithSingleKeyword() {
        when(mockMemory.retrieve(any(Msg.class))).thenReturn(Mono.just("Single result"));

        List<String> keywords = List.of("coffee");

        StepVerifier.create(tools.retrieveFromMemory(keywords))
                .expectNext(LongTermMemoryTools.wrap("Single result"))
                .verifyComplete();

        verify(mockMemory, times(1)).retrieve(any(Msg.class));
    }

    @Test
    void testRetrieveFromMemoryWithNullKeywords() {
        StepVerifier.create(tools.retrieveFromMemory(null))
                .expectNext("No keywords provided for search")
                .verifyComplete();

        verify(mockMemory, never()).retrieve(any(Msg.class));
    }

    @Test
    void testRetrieveFromMemoryWithEmptyKeywords() {
        StepVerifier.create(tools.retrieveFromMemory(List.of()))
                .expectNext("No keywords provided for search")
                .verifyComplete();

        verify(mockMemory, never()).retrieve(any(Msg.class));
    }

    @Test
    void testRetrieveFromMemoryReturnsEmpty() {
        when(mockMemory.retrieve(any(Msg.class))).thenReturn(Mono.just(""));

        List<String> keywords = List.of("nonexistent");

        StepVerifier.create(tools.retrieveFromMemory(keywords))
                .expectNext("No relevant memories found")
                .verifyComplete();
    }

    @Test
    void testRetrieveFromMemoryReturnsNullString() {
        // Test when memory returns empty Mono - should complete without next
        when(mockMemory.retrieve(any(Msg.class))).thenReturn(Mono.empty());

        List<String> keywords = List.of("test");

        // When underlying Mono is empty, map() doesn't execute, so we get empty result
        StepVerifier.create(tools.retrieveFromMemory(keywords)).verifyComplete();
    }

    @Test
    void testRetrieveFromMemoryError() {
        when(mockMemory.retrieve(any(Msg.class)))
                .thenReturn(Mono.error(new RuntimeException("Retrieval error")));

        List<String> keywords = List.of("test");

        StepVerifier.create(tools.retrieveFromMemory(keywords))
                .expectNext("Error retrieving memory: Retrieval error")
                .verifyComplete();
    }

    @Test
    void testRecordToMemoryMessageRoles() {
        when(mockMemory.record(anyList())).thenReturn(Mono.empty());

        String thinking = "Analysis";
        List<String> content = List.of("Fact 1", "Fact 2");

        StepVerifier.create(tools.recordToMemory(thinking, content))
                .expectNext("Successfully recorded to long-term memory")
                .verifyComplete();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockMemory, times(1)).record(captor.capture());

        List<Msg> messages = captor.getValue();
        // First message (thinking) should be ASSISTANT role
        assertEquals(io.agentscope.core.message.MsgRole.ASSISTANT, messages.get(0).getRole());
        // Content messages should be USER role
        assertEquals(io.agentscope.core.message.MsgRole.USER, messages.get(1).getRole());
        assertEquals(io.agentscope.core.message.MsgRole.USER, messages.get(2).getRole());
    }

    @Test
    void testRetrieveFromMemoryQueryConstruction() {
        when(mockMemory.retrieve(any(Msg.class))).thenReturn(Mono.just("result"));

        List<String> keywords = List.of("word1", "word2", "word3");

        StepVerifier.create(tools.retrieveFromMemory(keywords))
                .expectNext(LongTermMemoryTools.wrap("result"))
                .verifyComplete();

        ArgumentCaptor<Msg> captor = ArgumentCaptor.forClass(Msg.class);
        verify(mockMemory, times(1)).retrieve(captor.capture());

        Msg queryMsg = captor.getValue();
        String query = queryMsg.getTextContent();
        // Keywords should be joined with spaces
        assertEquals("word1 word2 word3", query);
    }

    @Test
    void testMultipleRecordOperations() {
        when(mockMemory.record(anyList())).thenReturn(Mono.empty());

        List<String> content1 = List.of("First fact");
        List<String> content2 = List.of("Second fact");

        StepVerifier.create(tools.recordToMemory("thinking1", content1))
                .expectNext("Successfully recorded to long-term memory")
                .verifyComplete();

        StepVerifier.create(tools.recordToMemory("thinking2", content2))
                .expectNext("Successfully recorded to long-term memory")
                .verifyComplete();

        verify(mockMemory, times(2)).record(anyList());
    }

    @Test
    void testMultipleRetrieveOperations() {
        when(mockMemory.retrieve(any(Msg.class)))
                .thenReturn(Mono.just("result1"))
                .thenReturn(Mono.just("result2"));

        StepVerifier.create(tools.retrieveFromMemory(List.of("query1")))
                .expectNext(LongTermMemoryTools.wrap("result1"))
                .verifyComplete();

        StepVerifier.create(tools.retrieveFromMemory(List.of("query2")))
                .expectNext(LongTermMemoryTools.wrap("result2"))
                .verifyComplete();

        verify(mockMemory, times(2)).retrieve(any(Msg.class));
    }
}
