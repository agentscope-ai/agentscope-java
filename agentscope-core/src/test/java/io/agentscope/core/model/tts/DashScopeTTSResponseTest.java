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
package io.agentscope.core.model.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DashScopeTTSResponse.
 */
class DashScopeTTSResponseTest {

    @Nested
    @DisplayName("Deserialization Tests")
    class DeserializationTests {

        @Test
        @DisplayName("should deserialize response with audio data")
        void shouldDeserializeResponseWithAudioData() {
            String json =
                    "{\n"
                            + "  \"request_id\": \"req-123\",\n"
                            + "  \"output\": {\n"
                            + "    \"audio\": {\n"
                            + "      \"data\": \"base64encodedaudio==\"\n"
                            + "    }\n"
                            + "  }\n"
                            + "}";

            DashScopeTTSResponse response =
                    JsonUtils.getJsonCodec().fromJson(json, DashScopeTTSResponse.class);

            assertNotNull(response);
            assertEquals("req-123", response.getRequestId());
            assertNull(response.getCode());
            assertNull(response.getMessage());
            assertNotNull(response.getOutput());
            assertNotNull(response.getOutput().getAudio());
            assertEquals("base64encodedaudio==", response.getOutput().getAudio().getData());
            assertNull(response.getOutput().getAudio().getUrl());
        }

        @Test
        @DisplayName("should deserialize response with audio URL")
        void shouldDeserializeResponseWithAudioUrl() {
            String json =
                    "{\n"
                            + "  \"request_id\": \"req-456\",\n"
                            + "  \"output\": {\n"
                            + "    \"audio\": {\n"
                            + "      \"url\": \"https://example.com/audio.wav\"\n"
                            + "    }\n"
                            + "  }\n"
                            + "}";

            DashScopeTTSResponse response =
                    JsonUtils.getJsonCodec().fromJson(json, DashScopeTTSResponse.class);

            assertNotNull(response);
            assertEquals("req-456", response.getRequestId());
            assertNotNull(response.getOutput());
            assertNotNull(response.getOutput().getAudio());
            assertEquals("https://example.com/audio.wav", response.getOutput().getAudio().getUrl());
            assertNull(response.getOutput().getAudio().getData());
        }

        @Test
        @DisplayName("should deserialize response with both audio data and URL")
        void shouldDeserializeResponseWithBothAudioDataAndUrl() {
            String json =
                    "{\n"
                            + "  \"request_id\": \"req-789\",\n"
                            + "  \"output\": {\n"
                            + "    \"audio\": {\n"
                            + "      \"url\": \"https://example.com/audio.wav\",\n"
                            + "      \"data\": \"base64encodedaudio==\"\n"
                            + "    }\n"
                            + "  }\n"
                            + "}";

            DashScopeTTSResponse response =
                    JsonUtils.getJsonCodec().fromJson(json, DashScopeTTSResponse.class);

            assertNotNull(response);
            assertEquals("req-789", response.getRequestId());
            assertNotNull(response.getOutput());
            assertNotNull(response.getOutput().getAudio());
            assertEquals("https://example.com/audio.wav", response.getOutput().getAudio().getUrl());
            assertEquals("base64encodedaudio==", response.getOutput().getAudio().getData());
        }

        @Test
        @DisplayName("should deserialize error response")
        void shouldDeserializeErrorResponse() {
            String json =
                    "{\n"
                            + "  \"code\": \"InvalidApiKey\",\n"
                            + "  \"message\": \"API key is invalid\"\n"
                            + "}";

            DashScopeTTSResponse response =
                    JsonUtils.getJsonCodec().fromJson(json, DashScopeTTSResponse.class);

            assertNotNull(response);
            assertEquals("InvalidApiKey", response.getCode());
            assertEquals("API key is invalid", response.getMessage());
            assertNull(response.getRequestId());
            assertNull(response.getOutput());
        }

        @Test
        @DisplayName("should deserialize response with null fields")
        void shouldDeserializeResponseWithNullFields() {
            String json = "{}";

            DashScopeTTSResponse response =
                    JsonUtils.getJsonCodec().fromJson(json, DashScopeTTSResponse.class);

            assertNotNull(response);
            assertNull(response.getCode());
            assertNull(response.getMessage());
            assertNull(response.getRequestId());
            assertNull(response.getOutput());
        }
    }

    @Nested
    @DisplayName("Serialization Tests")
    class SerializationTests {

        @Test
        @DisplayName("should serialize and deserialize round trip")
        void shouldSerializeAndDeserializeRoundTrip() {
            DashScopeTTSResponse.Audio audio =
                    new DashScopeTTSResponse.Audio("https://example.com/audio.wav", "base64data==");
            DashScopeTTSResponse.Output output = new DashScopeTTSResponse.Output(audio);
            DashScopeTTSResponse original = new DashScopeTTSResponse(null, null, "req-123", output);

            String json = JsonUtils.getJsonCodec().toJson(original);
            assertNotNull(json);

            DashScopeTTSResponse deserialized =
                    JsonUtils.getJsonCodec().fromJson(json, DashScopeTTSResponse.class);

            assertNotNull(deserialized);
            assertEquals(original.getRequestId(), deserialized.getRequestId());
            assertNotNull(deserialized.getOutput());
            assertNotNull(deserialized.getOutput().getAudio());
            assertEquals(
                    original.getOutput().getAudio().getUrl(),
                    deserialized.getOutput().getAudio().getUrl());
            assertEquals(
                    original.getOutput().getAudio().getData(),
                    deserialized.getOutput().getAudio().getData());
        }
    }
}
