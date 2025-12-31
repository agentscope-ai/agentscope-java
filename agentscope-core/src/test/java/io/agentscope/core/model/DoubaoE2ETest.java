/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * E2E test for Doubao (ByteDance) models using OpenAI-compatible API.
 *
 * <p>Set the DOUBAO_API_KEY environment variable to run these tests:
 * <pre>
 * export DOUBAO_API_KEY="your-api-key"
 * mvn test -Dtest=DoubaoE2ETest
 * </pre>
 */
public class DoubaoE2ETest {

    private static final String API_KEY = System.getenv("DOUBAO_API_KEY");
    private static final String BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    private static final String MODEL = "doubao-seed-1-6-250615";

    @Test
    @Disabled("Manual E2E test - run with real API")
    public void testBasicConversation() {
        System.out.println("=== Test: Basic Conversation with Doubao ===");

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey(API_KEY).baseUrl(BASE_URL).modelName(MODEL).stream(
                                false)
                        .build();

        Msg input =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is 7 + 2?").build())
                        .build();

        ChatResponse response =
                model.stream(List.of(input), null, null).blockLast(Duration.ofSeconds(30));

        assertNotNull(response, "Response should not be null");
        String content =
                response.getContent().stream()
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .reduce("", (a, b) -> a + b);

        System.out.println("Response: " + content);
        assertTrue(content.contains("9") || content.contains("九"), "Should contain answer");
    }

    @Test
    @Disabled("Manual E2E test - run with real API")
    public void testStreamingConversation() {
        System.out.println("=== Test: Streaming Conversation with Doubao ===");

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey(API_KEY).baseUrl(BASE_URL).modelName(MODEL).stream(
                                true)
                        .build();

        Msg input =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is the capital of France?").build())
                        .build();

        StringBuilder fullResponse = new StringBuilder();
        model.stream(List.of(input), null, null)
                .doOnNext(
                        response -> {
                            String content =
                                    response.getContent().stream()
                                            .filter(b -> b instanceof TextBlock)
                                            .map(b -> ((TextBlock) b).getText())
                                            .reduce("", (a, b) -> a + b);
                            fullResponse.append(content);
                        })
                .blockLast(Duration.ofSeconds(30));

        System.out.println("Response: " + fullResponse.toString());
        assertTrue(fullResponse.toString().toLowerCase().contains("paris"), "Should contain Paris");
    }

    @Test
    @Disabled("Tool calling test - not enabled")
    public void testToolCalling() {
        // Tool calling test skipped for now
    }

    @Test
    @Disabled("Manual E2E test - run with real API")
    public void testMultiRoundConversation() {
        System.out.println("=== Test: Multi-Round Conversation with Doubao ===");

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey(API_KEY).baseUrl(BASE_URL).modelName(MODEL).stream(
                                false)
                        .build();

        // Round 1
        Msg msg1 =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("My favorite color is blue.").build())
                        .build();

        ChatResponse response1 =
                model.stream(List.of(msg1), null, null).blockLast(Duration.ofSeconds(30));
        String content1 =
                response1.getContent().stream()
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .reduce("", (a, b) -> a + b);
        System.out.println("Round 1 Response: " + content1);

        // Round 2 - include conversation history
        Msg msg2 =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is my favorite color?").build())
                        .build();

        // Build conversation history: user msg1 + assistant response1 + user msg2
        List<Msg> conversation = new java.util.ArrayList<>();
        conversation.add(msg1);
        // Add assistant response from round 1 (filter out ThinkingBlock, only keep TextBlock)
        List<io.agentscope.core.message.ContentBlock> textOnlyContent =
                response1.getContent().stream().filter(b -> b instanceof TextBlock).toList();
        conversation.add(
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(textOnlyContent)
                        .build());
        conversation.add(msg2);

        ChatResponse response2 =
                model.stream(conversation, null, null).blockLast(Duration.ofSeconds(30));
        String content2 =
                response2.getContent().stream()
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .reduce("", (a, b) -> a + b);

        System.out.println("Round 2 Response: " + content2);
        assertTrue(
                content2.toLowerCase().contains("blue"), "Should remember favorite color is blue");
    }
}
