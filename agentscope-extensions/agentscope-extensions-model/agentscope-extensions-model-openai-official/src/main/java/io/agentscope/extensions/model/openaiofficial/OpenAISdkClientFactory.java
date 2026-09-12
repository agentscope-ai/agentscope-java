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

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Factory for constructing official OpenAI Java SDK clients.
 *
 * <p>This is the only entry point for production client creation. Tests bypass this factory
 * and inject a fake/mock {@link OpenAIClient} via the package-private constructor on
 * {@code OpenAIResponsesChatModel}.
 *
 * <p>Key behaviours:
 * <ul>
 *   <li>{@code maxRetries = 0} — AgentScope owns retry.</li>
 *   <li>Builder-level additional headers are injected into {@code ClientOptions} and remain
 *       constant across all requests. Per-request headers are not supported.</li>
 *   <li>{@code apiKey} missing or blank → fail-fast with a non-retryable
 *       {@link OpenAIOfficialModelException}. The {@code OPENAI_API_KEY} fallback should
 *       be resolved by the caller (SPI/Builder) before invoking this factory.</li>
 *   <li>{@code baseUrl} null/blank → SDK defaults to {@code https://api.openai.com/v1}
 *       .</li>
 * </ul>
 */
final class OpenAISdkClientFactory {

    private OpenAISdkClientFactory() {}

    /**
     * Creates an {@link OpenAIClient} from the resolved configuration.
     *
     * @param apiKey            the API key (must be non-blank; caller resolves
     *                          {@code OPENAI_API_KEY} fallback before calling)
     * @param baseUrl           the base URL, or null/blank for SDK default
     * @param additionalHeaders builder-level headers to inject into the client (may be null)
     * @param timeout           the request timeout, or null for SDK default
     * @return a configured {@link OpenAIClient} with {@code maxRetries=0}
     * @throws OpenAIOfficialModelException if apiKey is missing/blank or SDK construction fails
     */
    static OpenAIClient createClient(
            String apiKey,
            String baseUrl,
            Map<String, String> additionalHeaders,
            Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new OpenAIOfficialModelException(
                    "apiKey is required for the openai-official provider. Set it via"
                            + " builder.apiKey(...) or the OPENAI_API_KEY environment variable.",
                    null,
                    null);
        }

        try {
            OpenAIOkHttpClient.Builder builder =
                    OpenAIOkHttpClient.builder().apiKey(apiKey).maxRetries(0);

            if (baseUrl != null && !baseUrl.isBlank()) {
                builder.baseUrl(baseUrl);
            }

            if (timeout != null) {
                builder.timeout(timeout);
            }

            if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
                additionalHeaders.forEach(builder::putHeader);
            }

            return builder.build();
        } catch (RuntimeException e) {
            throw new OpenAIOfficialModelException(
                    "Failed to construct OpenAI client: " + e.getMessage(), e, null);
        }
    }
}
