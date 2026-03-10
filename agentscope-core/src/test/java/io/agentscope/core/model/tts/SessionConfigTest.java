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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SessionConfig.
 */
class SessionConfigTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should build with all properties")
        void shouldBuildWithAllProperties() {
            SessionConfig config =
                    SessionConfig.builder()
                            .mode("server_commit")
                            .voice("Cherry")
                            .languageType("Chinese")
                            .responseFormat("wav")
                            .sampleRate(24000)
                            .build();

            assertEquals("server_commit", config.getMode());
            assertEquals("Cherry", config.getVoice());
            assertEquals("Chinese", config.getLanguageType());
            assertEquals("wav", config.getResponseFormat());
            assertEquals(24000, config.getSampleRate());
        }

        @Test
        @DisplayName("should build with partial properties")
        void shouldBuildWithPartialProperties() {
            SessionConfig config =
                    SessionConfig.builder()
                            .mode("commit")
                            .voice("Serena")
                            .sampleRate(16000)
                            .build();

            assertEquals("commit", config.getMode());
            assertEquals("Serena", config.getVoice());
            assertEquals(16000, config.getSampleRate());
            // Null values are allowed
        }

        @Test
        @DisplayName("should build with minimal properties")
        void shouldBuildWithMinimalProperties() {
            SessionConfig config = SessionConfig.builder().mode("server_commit").build();

            assertNotNull(config);
            assertEquals("server_commit", config.getMode());
        }

        @Test
        @DisplayName("builder should return new instance")
        void builderShouldReturnNewInstance() {
            SessionConfig.Builder builder1 = SessionConfig.builder();
            SessionConfig.Builder builder2 = SessionConfig.builder();

            assertNotNull(builder1);
            assertNotNull(builder2);
        }
    }

    @Nested
    @DisplayName("JSON Serialization Tests")
    class JsonSerializationTests {

        @Test
        @DisplayName("should serialize to JSON correctly")
        void shouldSerializeToJson() {
            SessionConfig config =
                    SessionConfig.builder()
                            .mode("server_commit")
                            .voice("Cherry")
                            .languageType("Chinese")
                            .responseFormat("wav")
                            .sampleRate(24000)
                            .build();

            String json = JsonUtils.getJsonCodec().toJson(config);

            assertNotNull(json);
            // Verify JSON contains expected fields
            assertTrue(json.contains("\"mode\":\"server_commit\""));
            assertTrue(json.contains("\"voice\":\"Cherry\""));
            assertTrue(json.contains("\"language_type\":\"Chinese\""));
            assertTrue(json.contains("\"response_format\":\"wav\""));
            assertTrue(json.contains("\"sample_rate\":24000"));
        }

        @Test
        @DisplayName("should deserialize from JSON correctly")
        void shouldDeserializeFromJson() {
            String json =
                    "{\"mode\":\"commit\",\"voice\":\"Serena\",\"language_type\":\"English\","
                            + "\"response_format\":\"mp3\",\"sample_rate\":48000}";

            SessionConfig config = JsonUtils.getJsonCodec().fromJson(json, SessionConfig.class);

            assertNotNull(config);
            assertEquals("commit", config.getMode());
            assertEquals("Serena", config.getVoice());
            assertEquals("English", config.getLanguageType());
            assertEquals("mp3", config.getResponseFormat());
            assertEquals(48000, config.getSampleRate());
        }

        @Test
        @DisplayName("should handle round-trip serialization")
        void shouldHandleRoundTripSerialization() {
            SessionConfig original =
                    SessionConfig.builder()
                            .mode("server_commit")
                            .voice("Cherry")
                            .languageType("Chinese")
                            .responseFormat("wav")
                            .sampleRate(24000)
                            .build();

            String json = JsonUtils.getJsonCodec().toJson(original);
            SessionConfig deserialized =
                    JsonUtils.getJsonCodec().fromJson(json, SessionConfig.class);

            assertEquals(original.getMode(), deserialized.getMode());
            assertEquals(original.getVoice(), deserialized.getVoice());
            assertEquals(original.getLanguageType(), deserialized.getLanguageType());
            assertEquals(original.getResponseFormat(), deserialized.getResponseFormat());
            assertEquals(original.getSampleRate(), deserialized.getSampleRate());
        }
    }
}
