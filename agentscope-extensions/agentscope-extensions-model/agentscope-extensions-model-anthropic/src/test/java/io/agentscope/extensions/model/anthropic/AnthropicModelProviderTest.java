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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.ProxyConfig;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnthropicModelProviderTest {

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void supportsAnthropicModelIds() {
        AnthropicModelProvider provider = new AnthropicModelProvider();
        assertTrue(provider.supports("anthropic:claude-sonnet-4.5"));
        assertFalse(provider.supports("anthropic:"));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
    }

    @Test
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        AnthropicModelProvider provider = new AnthropicModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("anthropic:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("claude-sonnet-4.5"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createUsesModelCreationContext() {
        AnthropicModelProvider provider = new AnthropicModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-anthropic-key")
                        .baseUrl("https://anthropic.example.com")
                        .stream(false)
                        .component(GenerateOptions.class, GenerateOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 200000)
                        .build();

        Model model = provider.create("anthropic:claude-sonnet-4.5", context);

        assertTrue(model instanceof AnthropicChatModel);
        assertTrue(model.getModelName().equals("claude-sonnet-4.5"));
        assertEquals(200000, model.getContextWindowSize());
    }

    @Test
    void createWithAuthTokenOptionSendsBearerHeader() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    """
                                    {
                                      "id": "msg_gateway", "type": "message", "role": "assistant",
                                      "model": "claude-sonnet-4.5",
                                      "content": [{"type": "text", "text": "Hello"}],
                                      "stop_reason": "end_turn", "stop_sequence": null,
                                      "usage": {"input_tokens": 1, "output_tokens": 1}
                                    }
                                    """));

            AnthropicModelProvider provider = new AnthropicModelProvider();
            ModelCreationContext context =
                    ModelCreationContext.builder()
                            .baseUrl(server.url("/anthropic/").toString())
                            .stream(false)
                            .option("authToken", "gateway-token")
                            .build();

            Model model = provider.create("anthropic:claude-sonnet-4.5", context);
            java.util.List<ChatResponse> responses =
                    model.stream(
                                    java.util.List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("Hello")
                                                    .build()),
                                    null,
                                    null)
                            .collectList()
                            .block(Duration.ofSeconds(10));

            assertEquals(1, responses.size());
            RecordedRequest request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("Bearer gateway-token", request.getHeader("Authorization"));
            assertNull(request.getHeader("X-Api-Key"));
        }
    }

    @Test
    void createRejectsConflictingExplicitCredentials() {
        AnthropicModelProvider provider = new AnthropicModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-anthropic-key")
                        .option("authToken", "gateway-token")
                        .build();

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> provider.create("anthropic:claude-sonnet-4.5", context));
        assertEquals(
                "apiKey and authToken are mutually exclusive; configure only one credential",
                error.getMessage());
    }

    @Test
    void createRejectsNonStringAuthTokenOption() {
        AnthropicModelProvider provider = new AnthropicModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .baseUrl("https://anthropic.example.com")
                        .option("authToken", 123)
                        .build();

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> provider.create("anthropic:claude-sonnet-4.5", context));
        assertTrue(error.getMessage().contains("authToken"));
    }

    @Test
    void modelRegistryFindsAnthropicProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("anthropic:claude-sonnet-4.5"));
    }
}
