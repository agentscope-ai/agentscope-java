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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.formatter.LiveFormatter;
import io.agentscope.core.live.session.LiveSession;
import io.agentscope.core.live.transport.WebSocketClient;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@DisplayName("LiveModel Tests")
class LiveModelTest {

    /** Test implementation of LiveModelBase for testing purposes. */
    static class TestLiveModel extends LiveModelBase {

        private final String apiKey;
        private final String baseUrl;

        TestLiveModel(
                String modelName, String apiKey, String baseUrl, WebSocketClient webSocketClient) {
            super(modelName, webSocketClient);
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
        }

        @Override
        protected Mono<LiveSession> doConnect(LiveConfig config, List<ToolSchema> toolSchemas) {
            // Simplified implementation, actual would establish WebSocket connection
            return Mono.empty();
        }

        @Override
        protected String buildWebSocketUrl() {
            return baseUrl + "?model=" + modelName;
        }

        @Override
        protected Map<String, String> buildHeaders() {
            return Map.of("Authorization", "Bearer " + apiKey);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <T> LiveFormatter<T> createFormatter() {
            return (LiveFormatter<T>)
                    new LiveFormatter<String>() {
                        @Override
                        public String formatInput(Msg msg) {
                            return "{}";
                        }

                        @Override
                        public LiveEvent parseOutput(String data) {
                            return LiveEvent.unknown("test", data);
                        }

                        @Override
                        public String buildSessionConfig(
                                LiveConfig config, List<ToolSchema> toolSchemas) {
                            return "{}";
                        }
                    };
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }

    @Test
    @DisplayName("Should return model name")
    void shouldReturnModelName() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        assertEquals("test-model", model.getModelName());
    }

    @Test
    @DisplayName("Should return provider name")
    void shouldReturnProviderName() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        assertEquals("test", model.getProviderName());
    }

    @Test
    @DisplayName("Should not support native recovery by default")
    void shouldNotSupportNativeRecoveryByDefault() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        assertFalse(model.supportsNativeRecovery());
    }

    @Test
    @DisplayName("Should build WebSocket URL")
    void shouldBuildWebSocketUrl() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        assertEquals("wss://test.com?model=test-model", model.buildWebSocketUrl());
    }

    @Test
    @DisplayName("Should build headers with API key")
    void shouldBuildHeadersWithApiKey() {
        TestLiveModel model = new TestLiveModel("test-model", "my-api-key", "wss://test.com", null);

        Map<String, String> headers = model.buildHeaders();

        assertEquals("Bearer my-api-key", headers.get("Authorization"));
    }

    @Test
    @DisplayName("Should connect with default config")
    void shouldConnectWithDefaultConfig() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        // Connect with default configuration
        Mono<LiveSession> result = model.connect();

        assertNotNull(result);
    }

    @Test
    @DisplayName("Should connect with custom config")
    void shouldConnectWithCustomConfig() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        LiveConfig config =
                LiveConfig.builder()
                        .voice("alloy")
                        .instructions("You are a helpful assistant")
                        .build();

        // Connect with custom configuration
        Mono<LiveSession> result = model.connect(config);

        assertNotNull(result);
    }

    @Test
    @DisplayName("Should connect with tool schemas")
    void shouldConnectWithToolSchemas() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        List<ToolSchema> tools =
                List.of(
                        ToolSchema.builder()
                                .name("get_weather")
                                .description("Get weather info")
                                .build());

        // Connect with tool definitions
        Mono<LiveSession> result = model.connect(LiveConfig.defaults(), tools);

        assertNotNull(result);
    }

    @Test
    @DisplayName("Should create formatter")
    void shouldCreateFormatter() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        LiveFormatter<String> formatter = model.createFormatter();

        assertNotNull(formatter);
        assertEquals("{}", formatter.formatInput(Msg.builder().build()));
    }

    @Test
    @DisplayName("Formatter should parse output to LiveEvent")
    void formatterShouldParseOutputToLiveEvent() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        LiveFormatter<String> formatter = model.createFormatter();
        LiveEvent event = formatter.parseOutput("{\"type\":\"test\"}");

        assertNotNull(event);
    }

    @Test
    @DisplayName("Formatter should build session config")
    void formatterShouldBuildSessionConfig() {
        TestLiveModel model = new TestLiveModel("test-model", "api-key", "wss://test.com", null);

        LiveFormatter<String> formatter = model.createFormatter();
        String config = formatter.buildSessionConfig(LiveConfig.defaults(), null);

        assertEquals("{}", config);
    }
}
