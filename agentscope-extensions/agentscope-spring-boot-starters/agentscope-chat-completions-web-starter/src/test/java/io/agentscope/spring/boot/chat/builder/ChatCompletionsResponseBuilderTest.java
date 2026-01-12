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
package io.agentscope.spring.boot.chat.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatCompletionsResponseBuilder}.
 *
 * <p>These tests verify the builder's behavior for constructing response objects.
 */
@DisplayName("ChatCompletionsResponseBuilder Tests")
class ChatCompletionsResponseBuilderTest {

    private ChatCompletionsResponseBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ChatCompletionsResponseBuilder();
    }

    @Nested
    @DisplayName("Build Response Tests")
    class BuildResponseTests {

        @Test
        @DisplayName("Should build successful response correctly")
        void shouldBuildSuccessfulResponseCorrectly() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setMessages(List.of(new ChatMessage("user", "Hello")));

            Msg reply =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hi there!").build())
                            .build();

            ChatCompletionsResponse response = builder.buildResponse(request, reply, "test-id");

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo("test-id");
            assertThat(response.getModel()).isEqualTo("test-model");
            assertThat(response.getCreated()).isPositive();
            assertThat(response.getChoices()).hasSize(1);
            assertThat(response.getChoices().get(0).getIndex()).isEqualTo(0);
            assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
            assertThat(response.getChoices().get(0).getMessage().getRole()).isEqualTo("assistant");
            assertThat(response.getChoices().get(0).getMessage().getContent())
                    .isEqualTo("Hi there!");
        }

        @Test
        @DisplayName("Should handle null reply message")
        void shouldHandleNullReplyMessage() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");

            ChatCompletionsResponse response = builder.buildResponse(request, null, "test-id");

            assertThat(response).isNotNull();
            assertThat(response.getChoices()).hasSize(1);
            assertThat(response.getChoices().get(0).getMessage().getContent()).isEmpty();
        }

        @Test
        @DisplayName("Should handle empty reply content")
        void shouldHandleEmptyReplyContent() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");

            Msg reply =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("").build())
                            .build();

            ChatCompletionsResponse response = builder.buildResponse(request, reply, "test-id");

            assertThat(response).isNotNull();
            assertThat(response.getChoices().get(0).getMessage().getContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Build Error Response Tests")
    class BuildErrorResponseTests {

        @Test
        @DisplayName("Should build error response correctly")
        void shouldBuildErrorResponseCorrectly() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");

            RuntimeException error = new RuntimeException("Test error message");

            ChatCompletionsResponse response =
                    builder.buildErrorResponse(request, error, "test-id");

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo("test-id");
            assertThat(response.getModel()).isEqualTo("test-model");
            assertThat(response.getCreated()).isPositive();
            assertThat(response.getChoices()).hasSize(1);
            assertThat(response.getChoices().get(0).getIndex()).isEqualTo(0);
            assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("error");
            assertThat(response.getChoices().get(0).getMessage().getContent())
                    .contains("Error:")
                    .contains("Test error message");
        }

        @Test
        @DisplayName("Should handle null error")
        void shouldHandleNullError() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");

            ChatCompletionsResponse response = builder.buildErrorResponse(request, null, "test-id");

            assertThat(response).isNotNull();
            assertThat(response.getChoices().get(0).getMessage().getContent())
                    .contains("Unknown error occurred");
        }
    }

    @Nested
    @DisplayName("Extract Text Content Tests")
    class ExtractTextContentTests {

        @Test
        @DisplayName("Should extract text content correctly")
        void shouldExtractTextContentCorrectly() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello world").build())
                            .build();

            String result = builder.extractTextContent(msg);

            assertThat(result).isEqualTo("Hello world");
        }

        @Test
        @DisplayName("Should return empty string for null message")
        void shouldReturnEmptyStringForNullMessage() {
            String result = builder.extractTextContent(null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty string for null content")
        void shouldReturnEmptyStringForNullContent() {
            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).build();

            String result = builder.extractTextContent(msg);

            assertThat(result).isEmpty();
        }
    }
}
