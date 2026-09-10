/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.client.OpenAIClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OpenAISdkClientFactory}.
 *
 * <p>Covers the three key branches: apiKey fail-fast, happy path, and RuntimeException
 * wrapping. The factory is also exercised indirectly by {@link OpenAIOfficialModelProviderTest}.
 */
class OpenAISdkClientFactoryTest {

    @Test
    void blankApiKeyThrowsWithDescriptiveMessage() {
        OpenAIOfficialModelException ex =
                assertThrows(
                        OpenAIOfficialModelException.class,
                        () -> OpenAISdkClientFactory.createClient(null, null, null, null));
        assertTrue(ex.getMessage().contains("apiKey is required"));
    }

    @Test
    void validParamsReturnsClient() {
        OpenAIClient client =
                OpenAISdkClientFactory.createClient(
                        "sk-test",
                        "https://custom.example.com",
                        Map.of("X-Request-Id", "abc"),
                        Duration.ofSeconds(30));
        assertTrue(client != null);
    }

    @Test
    void sdkRuntimeExceptionWrappedInModelException() {
        // null header value triggers Kotlin null-check in putHeader(name, value)
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        headers.put("X-Null", null);

        OpenAIOfficialModelException ex =
                assertThrows(
                        OpenAIOfficialModelException.class,
                        () -> OpenAISdkClientFactory.createClient("sk-test", null, headers, null));
        assertTrue(ex.getMessage().contains("Failed to construct OpenAI client"));
        assertTrue(ex.getCause() != null);
    }
}
