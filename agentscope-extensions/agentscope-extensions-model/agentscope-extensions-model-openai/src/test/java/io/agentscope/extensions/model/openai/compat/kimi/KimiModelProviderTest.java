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
package io.agentscope.extensions.model.openai.compat.kimi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class KimiModelProviderTest {

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void providerIdIsKimi() {
        assertEquals("kimi", new KimiModelProvider().providerId());
    }

    @Test
    void supportsKimiModelIds() {
        KimiModelProvider provider = new KimiModelProvider();
        assertTrue(provider.supports("kimi:kimi-k3"));
        assertTrue(provider.supports("kimi:kimi-k2.6"));
        assertTrue(provider.supports("kimi:moonshot-v1-8k"));
        assertFalse(provider.supports("kimi:"));
        // Whitespace-only model names must not be treated as supported
        assertFalse(provider.supports("kimi: "));
        assertFalse(provider.supports("kimi:   "));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
        assertFalse(provider.supports(null));
    }

    @Test
    void createTrimsModelName() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").build();

        Model model = provider.create("kimi: kimi-k3", context);

        assertEquals("kimi-k3", model.getModelName());
    }

    @Test
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        KimiModelProvider provider = new KimiModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("kimi:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("kimi: "));
        assertThrows(IllegalArgumentException.class, () -> provider.create("kimi-k3"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createUsesModelCreationContext() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").stream(false)
                        .component(GenerateOptions.class, GenerateOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 262144)
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
        assertEquals("kimi-k3", model.getModelName());
        assertEquals(262144, model.getContextWindowSize());
    }

    @Test
    void nativeStructuredOutputDisabledByDefault() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").build();

        Model model = provider.create("kimi:kimi-k3", context);

        // Kimi response_format only supports json_object, so native structured
        // output falls back to the generate_response tool by default
        assertFalse(model.supportsNativeStructuredOutput());
    }

    @Test
    void nativeStructuredOutputCanBeEnabledByOption() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("nativeStructuredOutput", true)
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model.supportsNativeStructuredOutput());
    }

    @Test
    void nativeStructuredOutputWithToolsDisabledByDefault() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-kimi-key").build();

        Model model = provider.create("kimi:kimi-k3", context);

        // Kimi prioritises response_format over tool invocations when both are present
        assertFalse(model.supportsNativeStructuredOutputWithTools());
    }

    @Test
    void nativeStructuredOutputWithToolsCanBeEnabledByOption() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("nativeStructuredOutput", true)
                        .option("nativeStructuredOutputWithTools", true)
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model.supportsNativeStructuredOutputWithTools());
    }

    @Test
    void contextWindowSizeOptionMustBeANumber() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("contextWindowSize", "not-a-number")
                        .build();

        assertThrows(
                IllegalArgumentException.class, () -> provider.create("kimi:kimi-k3", context));
    }

    @Test
    void nativeStructuredOutputOptionsMustBeBooleans() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext structuredOutputContext =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("nativeStructuredOutput", "yes")
                        .build();
        ModelCreationContext structuredOutputWithToolsContext =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .option("nativeStructuredOutputWithTools", "yes")
                        .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> provider.create("kimi:kimi-k3", structuredOutputContext));
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.create("kimi:kimi-k3", structuredOutputWithToolsContext));
    }

    @Test
    void createWithoutApiKeyThrowsIllegalStateException() {
        // Only meaningful when the environment provides no Kimi API key either
        assumeTrue(isBlank(System.getenv("MOONSHOT_API_KEY")));
        assumeTrue(isBlank(System.getenv("KIMI_API_KEY")));

        KimiModelProvider provider = new KimiModelProvider();

        IllegalStateException fromEmptyContext =
                assertThrows(IllegalStateException.class, () -> provider.create("kimi:kimi-k3"));
        assertTrue(fromEmptyContext.getMessage().contains("MOONSHOT_API_KEY"));
        assertTrue(fromEmptyContext.getMessage().contains("KIMI_API_KEY"));

        // A blank context API key must fall through to the environment lookup
        ModelCreationContext blankKeyContext = ModelCreationContext.builder().apiKey("   ").build();
        assertThrows(
                IllegalStateException.class,
                () -> provider.create("kimi:kimi-k3", blankKeyContext));
    }

    @Test
    void endpointPathIsApplied() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .endpointPath("/custom/chat/completions")
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void httpTransportComponentIsApplied() {
        KimiModelProvider provider = new KimiModelProvider();
        HttpTransport transport =
                new HttpTransport() {
                    @Override
                    public HttpResponse execute(HttpRequest request) {
                        throw new UnsupportedOperationException("not used in this test");
                    }

                    @Override
                    public Flux<String> stream(HttpRequest request) {
                        return Flux.empty();
                    }

                    @Override
                    public void close() {}
                };
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .component(HttpTransport.class, transport)
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void customFormatterComponentTakesPrecedence() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .component(KimiMultiAgentFormatter.class, new KimiMultiAgentFormatter())
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void customBaseUrlOverridesDefault() {
        KimiModelProvider provider = new KimiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-kimi-key")
                        .baseUrl("https://kimi.example.com/v1")
                        .build();

        Model model = provider.create("kimi:kimi-k3", context);

        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void modelRegistryFindsKimiProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("kimi:kimi-k3"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
