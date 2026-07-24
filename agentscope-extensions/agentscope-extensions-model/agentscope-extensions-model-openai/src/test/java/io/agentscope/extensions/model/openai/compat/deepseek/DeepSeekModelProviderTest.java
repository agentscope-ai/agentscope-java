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
package io.agentscope.extensions.model.openai.compat.deepseek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

@DisplayName("DeepSeekModelProvider Unit Tests")
class DeepSeekModelProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    @DisplayName("Returns provider id")
    void returnsProviderId() {
        assertEquals("deepseek", new DeepSeekModelProvider().providerId());
    }

    @Test
    @DisplayName("Supports DeepSeek model ids")
    void supportsDeepSeekModelIds() {
        DeepSeekModelProvider provider = new DeepSeekModelProvider();

        assertTrue(provider.supports("deepseek:deepseek-v4-flash"));
        assertTrue(provider.supports("deepseek:deepseek-v4-pro"));
        assertFalse(provider.supports("deepseek:"));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
        assertFalse(provider.supports(null));
    }

    @Test
    @DisplayName("Rejects unsupported model ids before reading environment")
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        DeepSeekModelProvider provider = new DeepSeekModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("deepseek:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("openai:gpt-4o-mini"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    @DisplayName("Fails clearly when DEEPSEEK_API_KEY is missing")
    void createFailsClearlyWhenApiKeyMissing() {
        assumeTrue(System.getenv("DEEPSEEK_API_KEY") == null);
        DeepSeekModelProvider provider = new DeepSeekModelProvider();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> provider.create("deepseek:deepseek-v4-flash"));

        assertTrue(error.getMessage().contains("DEEPSEEK_API_KEY"));
    }

    @Test
    @DisplayName("Creates model from context and applies default request settings")
    void createUsesModelCreationContextAndDefaultDeepSeekRequestSettings() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-deepseek-key")
                        .baseUrl("   ")
                        .endpointPath("   ")
                        .stream(false)
                        .enableThinking(true)
                        .component(HttpTransport.class, transport)
                        .component(ProxyConfig.class, ProxyConfig.http("proxy.example.com", 8080))
                        .option("contextWindowSize", 128000)
                        .option("nativeStructuredOutput", false)
                        .build();

        Model model = new DeepSeekModelProvider().create("deepseek:deepseek-v4-flash", context);

        assertInstanceOf(OpenAIChatModel.class, model);
        assertEquals("deepseek-v4-flash", model.getModelName());
        assertEquals(128000, model.getContextWindowSize());
        assertFalse(model.supportsNativeStructuredOutput());

        ((OpenAIChatModel) model).stream(userMessages(), null, null).blockLast();

        assertEquals("https://api.deepseek.com/v1/chat/completions", transport.request.getUrl());
        assertEquals(
                "Bearer test-deepseek-key", transport.request.getHeaders().get("Authorization"));

        Map<String, Object> body = parseBody(transport.request.getBody());
        assertEquals("deepseek-v4-flash", body.get("model"));
        assertEquals(false, body.get("stream"));
        assertEquals(Map.of("type", "enabled"), body.get("thinking"));
    }

    @Test
    @DisplayName("Applies base URL, endpoint path, and structured-output overrides")
    void createAppliesEndpointAndStructuredOutputOverrides() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-deepseek-key")
                        .baseUrl("https://deepseek.example.com")
                        .endpointPath("/custom/chat")
                        .stream(false)
                        .enableThinking(false)
                        .component(HttpTransport.class, transport)
                        .option("nativeStructuredOutputWithTools", true)
                        .build();

        OpenAIChatModel model =
                (OpenAIChatModel)
                        new DeepSeekModelProvider().create("deepseek:deepseek-v4-flash", context);

        assertTrue(model.supportsNativeStructuredOutputWithTools());

        model.stream(userMessages(), null, null).blockLast();

        assertEquals("https://deepseek.example.com/custom/chat", transport.request.getUrl());
        Map<String, Object> body = parseBody(transport.request.getBody());
        assertEquals(Map.of("type", "disabled"), body.get("thinking"));
    }

    @Test
    @DisplayName("Rejects invalid advanced option types")
    void createRejectsInvalidAdvancedOptionTypes() {
        DeepSeekModelProvider provider = new DeepSeekModelProvider();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        provider.create(
                                "deepseek:deepseek-v4-flash",
                                ModelCreationContext.builder()
                                        .apiKey("test-deepseek-key")
                                        .option("contextWindowSize", "large")
                                        .build()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        provider.create(
                                "deepseek:deepseek-v4-flash",
                                ModelCreationContext.builder()
                                        .apiKey("test-deepseek-key")
                                        .option("nativeStructuredOutput", "true")
                                        .build()));
    }

    @Test
    @DisplayName("Keeps reasoning_effort independent from thinking")
    void generateOptionsControlReasoningEffortIndependentlyFromThinking() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        GenerateOptions generateOptions = GenerateOptions.builder().reasoningEffort("high").build();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-deepseek-key").stream(false)
                        .enableThinking(true)
                        .component(GenerateOptions.class, generateOptions)
                        .component(HttpTransport.class, transport)
                        .build();

        OpenAIChatModel model =
                (OpenAIChatModel)
                        new DeepSeekModelProvider().create("deepseek:deepseek-v4-pro", context);

        model.stream(userMessages(), null, null).blockLast();

        Map<String, Object> body = parseBody(transport.request.getBody());
        assertEquals("high", body.get("reasoning_effort"));
        assertEquals(Map.of("type", "enabled"), body.get("thinking"));
    }

    @Test
    @DisplayName("Allows user options to override default thinking")
    void userGenerateOptionsCanOverrideDeepSeekThinkingDefault() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        GenerateOptions generateOptions =
                GenerateOptions.builder()
                        .additionalBodyParam("thinking", Map.of("type", "disabled"))
                        .build();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-deepseek-key").stream(false)
                        .enableThinking(true)
                        .component(GenerateOptions.class, generateOptions)
                        .component(HttpTransport.class, transport)
                        .build();

        OpenAIChatModel model =
                (OpenAIChatModel)
                        new DeepSeekModelProvider().create("deepseek:deepseek-v4-pro", context);

        model.stream(userMessages(), null, null).blockLast();

        Map<String, Object> body = parseBody(transport.request.getBody());
        assertEquals(Map.of("type", "disabled"), body.get("thinking"));
    }

    @Test
    @DisplayName("Allows context formatter to override provider default")
    void contextFormatterOverrideTakesPrecedenceOverProviderDefault() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter =
                new OpenAIChatFormatter();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-deepseek-key").stream(false)
                        .component(Formatter.class, formatter)
                        .component(HttpTransport.class, transport)
                        .build();
        OpenAIChatModel model =
                (OpenAIChatModel)
                        new DeepSeekModelProvider().create("deepseek:deepseek-v4-flash", context);

        model.stream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.SYSTEM)
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("System instruction")
                                                                .build()))
                                        .build(),
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Reply with pong")
                                                                .build()))
                                        .build()),
                        null,
                        null)
                .blockLast();

        Map<String, Object> body = parseBody(transport.request.getBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertEquals("system", messages.get(0).get("role"));
    }

    @Test
    @DisplayName("Registers through ServiceLoader")
    void modelRegistryFindsDeepSeekProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("deepseek:deepseek-v4-flash"));
    }

    private static List<Msg> userMessages() {
        return List.of(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Reply with pong").build()))
                        .build());
    }

    private static Map<String, Object> parseBody(String body) throws Exception {
        return MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
    }

    private static final class CapturingTransport implements HttpTransport {
        private HttpRequest request;

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.request = request;
            return HttpResponse.builder()
                    .statusCode(200)
                    .body(
                            """
                            {
                              "id": "deepseek-test",
                              "object": "chat.completion",
                              "created": 1,
                              "model": "deepseek-v4-flash",
                              "choices": [{
                                "index": 0,
                                "message": {
                                  "role": "assistant",
                                  "content": "pong"
                                },
                                "finish_reason": "stop"
                              }],
                              "usage": {
                                "prompt_tokens": 1,
                                "completion_tokens": 1,
                                "total_tokens": 2
                              }
                            }
                            """)
                    .build();
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            this.request = request;
            return Flux.empty();
        }

        @Override
        public void close() {}
    }
}
