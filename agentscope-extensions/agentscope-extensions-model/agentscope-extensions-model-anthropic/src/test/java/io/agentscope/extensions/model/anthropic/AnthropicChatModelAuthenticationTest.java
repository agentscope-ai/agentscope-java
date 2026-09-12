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
package io.agentscope.extensions.model.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verifies authentication headers through the real SDK against a local HTTP server. */
@Tag("integration")
class AnthropicChatModelAuthenticationTest {

    private static final String MESSAGE_RESPONSE =
            """
            {
              "id": "msg_gateway", "type": "message", "role": "assistant",
              "model": "claude-sonnet-4.5",
              "content": [{"type": "text", "text": "Hello"}],
              "stop_reason": "end_turn", "stop_sequence": null,
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
            """;

    private static final String STREAM_RESPONSE =
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_gateway","type":"message","role":"assistant","model":"claude-sonnet-4.5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @ParameterizedTest
    @CsvSource({
        "false, , test-gateway-token",
        "true, , test-gateway-token",
        "false, test-api-key, ",
        "true, test-api-key, "
    })
    void shouldSendConfiguredAuthenticationHeaders(
            boolean streaming, String apiKey, String authToken) throws Exception {
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .baseUrl(server.url("/anthropic/").toString())
                        .apiKey(apiKey)
                        .authToken(authToken)
                        .modelName("claude-sonnet-4.5")
                        .stream(streaming)
                        .build();

        assertExchange(model, streaming, apiKey, authToken);
    }

    @Test
    void shouldPreserveApiKeyAuthenticationWithExistingConstructor() throws Exception {
        AnthropicChatModel model =
                new AnthropicChatModel(
                        server.url("/anthropic/").toString(),
                        "test-api-key",
                        "claude-sonnet-4.5",
                        false,
                        null,
                        null,
                        null,
                        null);

        assertExchange(model, false, "test-api-key", null);
    }

    @Test
    void shouldRejectBothCredentialsWithoutExposingTheirValues() {
        IllegalArgumentException builderError =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                AnthropicChatModel.builder()
                                        .apiKey("secret-api-key")
                                        .authToken("secret-bearer-token")
                                        .build());
        IllegalArgumentException constructorError =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new AnthropicChatModel(
                                        null,
                                        "secret-api-key",
                                        "secret-bearer-token",
                                        "claude-sonnet-4.5",
                                        false,
                                        null,
                                        null,
                                        null,
                                        null));

        for (IllegalArgumentException error : List.of(builderError, constructorError)) {
            assertEquals(
                    "apiKey and authToken are mutually exclusive; configure only one credential",
                    error.getMessage());
            assertFalse(error.toString().contains("secret-api-key"));
            assertFalse(error.toString().contains("secret-bearer-token"));
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void shouldNotExposeBearerTokenInModelOrBuilderToString() {
        AnthropicChatModel.Builder builder =
                AnthropicChatModel.builder().authToken("secret-bearer-token");
        assertFalse(builder.toString().contains("secret-bearer-token"));
        assertFalse(builder.build().toString().contains("secret-bearer-token"));
    }

    private void assertExchange(
            AnthropicChatModel model, boolean streaming, String apiKey, String authToken)
            throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader(
                                "Content-Type",
                                streaming ? "text/event-stream" : "application/json")
                        .setBody(streaming ? STREAM_RESPONSE : MESSAGE_RESPONSE));

        List<String> text =
                model.stream(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("Hello")
                                                .build()),
                                null,
                                null)
                        .flatMapIterable(ChatResponse::getContent)
                        .ofType(TextBlock.class)
                        .map(TextBlock::getText)
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertEquals(List.of("Hello"), text);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/anthropic/v1/messages", request.getPath());
        assertEquals(
                apiKey == null ? List.of() : List.of(apiKey),
                request.getHeaders().values("X-Api-Key"));
        assertEquals(
                authToken == null ? List.of() : List.of("Bearer " + authToken),
                request.getHeaders().values("Authorization"));
        assertEquals(1, server.getRequestCount());
    }
}
