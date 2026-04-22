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
package io.agentscope.micronaut;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Real API integration test for DashScope.
 *
 * <p>This test makes actual API calls to verify the integration works correctly. It requires a
 * valid DashScope API key to be configured via environment variable.
 *
 * <p><b>Note:</b> These tests use real API calls and may incur costs. They are disabled by default
 * and should only be enabled for manual integration testing with a valid API key.
 *
 * <p><b>To run these tests:</b>
 * <pre>
 * DASHSCOPE_API_KEY=your-key mvn test -Dtest=RealApiIntegrationTest
 * </pre>
 * And remove the {@code @Disabled} annotation.
 */
@Disabled("Real API tests require valid API key and are disabled in CI/CD")
@MicronautTest
class RealApiIntegrationTest {

    @Inject private Model model;

    @Test
    void testDashScopeRealApiCall() {
        // Create a simple test message
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Say hello in one word").build()))
                        .build();

        // Call the API with streaming (tools and options are optional)
        Flux<ChatResponse> responseFlux = model.stream(List.of(userMsg), null, null);

        // Collect all responses with timeout
        List<ChatResponse> responses = responseFlux.collectList().block(Duration.ofSeconds(30));

        // Verify response
        assertNotNull(responses, "Response list should not be null");
        assertFalse(responses.isEmpty(), "Response list should not be empty");

        // Extract and accumulate text from all chunks
        StringBuilder fullResponse = new StringBuilder();
        for (ChatResponse response : responses) {
            String chunkText = extractTextContent(response.getContent());
            if (chunkText != null && !chunkText.isEmpty()) {
                fullResponse.append(chunkText);
            }
        }

        String responseText = fullResponse.toString();
        assertNotNull(responseText, "Response text should not be null");
        assertFalse(responseText.trim().isEmpty(), "Response text should not be empty");

        System.out.println("DashScope Response: " + responseText);

        // Verify the response contains some text (basic validation)
        assertTrue(responseText.length() > 0, "Response should contain some text");
    }

    @Test
    void testDashScopeStreamingResponse() {
        // Create a message that should generate multiple chunks
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Count from 1 to 5").build()))
                        .build();

        // Call the API with streaming
        Flux<ChatResponse> responseFlux = model.stream(List.of(userMsg), null, null);

        // Count chunks
        Long chunkCount =
                responseFlux
                        .doOnNext(
                                chunk -> {
                                    String text = extractTextContent(chunk.getContent());
                                    if (text != null && !text.isEmpty()) {
                                        System.out.println("Received chunk: " + text);
                                    }
                                })
                        .count()
                        .block(Duration.ofSeconds(30));

        assertNotNull(chunkCount, "Chunk count should not be null");
        assertTrue(chunkCount > 0, "Should receive at least one chunk");

        System.out.println("Total chunks received: " + chunkCount);
    }

    @Test
    void testDashScopeConversation() {
        // Test a simple conversation flow
        Msg userMsg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("My name is Alice").build()))
                        .build();

        // First message - accumulate all chunks
        List<ChatResponse> responses1 =
                model.stream(List.of(userMsg1), null, null)
                        .collectList()
                        .block(Duration.ofSeconds(30));

        assertNotNull(responses1);
        assertFalse(responses1.isEmpty());

        // Accumulate response from all chunks
        StringBuilder fullResponse1 = new StringBuilder();
        for (ChatResponse response : responses1) {
            String chunkText = extractTextContent(response.getContent());
            if (chunkText != null && !chunkText.isEmpty()) {
                fullResponse1.append(chunkText);
            }
        }

        String response1Text = fullResponse1.toString();
        Msg assistantMsg1 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text(response1Text).build()))
                        .build();

        System.out.println("First response: " + response1Text);

        // Second message asking about the name
        Msg userMsg2 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("What is my name?").build()))
                        .build();

        List<ChatResponse> responses2 =
                model.stream(List.of(userMsg1, assistantMsg1, userMsg2), null, null)
                        .collectList()
                        .block(Duration.ofSeconds(30));

        assertNotNull(responses2);
        assertFalse(responses2.isEmpty());

        // Accumulate response from all chunks
        StringBuilder fullResponse2 = new StringBuilder();
        for (ChatResponse response : responses2) {
            String chunkText = extractTextContent(response.getContent());
            if (chunkText != null && !chunkText.isEmpty()) {
                fullResponse2.append(chunkText);
            }
        }

        String response2Text = fullResponse2.toString();
        System.out.println("Second response: " + response2Text);

        // The model should remember the name Alice
        assertTrue(
                response2Text.toLowerCase().contains("alice"),
                "Model should remember the name Alice from the conversation");
    }

    /**
     * Helper method to extract text content from content blocks.
     */
    private String extractTextContent(List<ContentBlock> content) {
        if (content == null) {
            return null;
        }

        return content.stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .filter(text -> text != null && !text.isEmpty())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
