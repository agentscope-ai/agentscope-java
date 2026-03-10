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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DashScopeTTSRequest and its nested classes.
 */
class DashScopeTTSRequestTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should build with all properties")
        void shouldBuildWithAllProperties() {
            DashScopeTTSRequest.TTSInput input =
                    DashScopeTTSRequest.TTSInput.builder()
                            .text("Hello, world!")
                            .voice("Cherry")
                            .languageType("English")
                            .build();

            DashScopeTTSRequest.TTSParameters parameters =
                    DashScopeTTSRequest.TTSParameters.builder()
                            .sampleRate(24000)
                            .format("wav")
                            .rate(1.0)
                            .volume(50)
                            .pitch(1.0)
                            .build();

            DashScopeTTSRequest request =
                    DashScopeTTSRequest.builder()
                            .model("qwen3-tts-flash")
                            .input(input)
                            .parameters(parameters)
                            .build();

            assertEquals("qwen3-tts-flash", request.getModel());
            assertNotNull(request.getInput());
            assertEquals("Hello, world!", request.getInput().getText());
            assertEquals("Cherry", request.getInput().getVoice());
            assertEquals("English", request.getInput().getLanguageType());
            assertNotNull(request.getParameters());
            assertEquals(24000, request.getParameters().getSampleRate());
            assertEquals("wav", request.getParameters().getFormat());
            assertEquals(1.0, request.getParameters().getRate());
            assertEquals(50, request.getParameters().getVolume());
            assertEquals(1.0, request.getParameters().getPitch());
        }

        @Test
        @DisplayName("should build with minimal properties")
        void shouldBuildWithMinimalProperties() {
            DashScopeTTSRequest request =
                    DashScopeTTSRequest.builder().model("qwen3-tts-flash").build();

            assertNotNull(request);
            assertEquals("qwen3-tts-flash", request.getModel());
            assertNull(request.getInput());
            assertNull(request.getParameters());
        }
    }

    @Nested
    @DisplayName("TTSInput Tests")
    class TTSInputTests {

        @Test
        @DisplayName("should build TTSInput with all properties")
        void shouldBuildTTSInputWithAllProperties() {
            DashScopeTTSRequest.TTSInput input =
                    DashScopeTTSRequest.TTSInput.builder()
                            .text("测试文本")
                            .voice("zhimao")
                            .languageType("Chinese")
                            .build();

            assertEquals("测试文本", input.getText());
            assertEquals("zhimao", input.getVoice());
            assertEquals("Chinese", input.getLanguageType());
        }

        @Test
        @DisplayName("should build TTSInput with partial properties")
        void shouldBuildTTSInputWithPartialProperties() {
            DashScopeTTSRequest.TTSInput input =
                    DashScopeTTSRequest.TTSInput.builder().text("Hello").build();

            assertEquals("Hello", input.getText());
            assertNull(input.getVoice());
            assertNull(input.getLanguageType());
        }

        @Test
        @DisplayName("should serialize TTSInput to JSON")
        void shouldSerializeTTSInputToJson() {
            DashScopeTTSRequest.TTSInput input =
                    DashScopeTTSRequest.TTSInput.builder()
                            .text("Hello")
                            .voice("Cherry")
                            .languageType("English")
                            .build();

            String json = JsonUtils.getJsonCodec().toJson(input);

            assertNotNull(json);
            assertTrue(json.contains("\"text\":\"Hello\""));
            assertTrue(json.contains("\"voice\":\"Cherry\""));
            assertTrue(json.contains("\"language_type\":\"English\""));
        }
    }

    @Nested
    @DisplayName("TTSParameters Tests")
    class TTSParametersTests {

        @Test
        @DisplayName("should build TTSParameters with all properties")
        void shouldBuildTTSParametersWithAllProperties() {
            DashScopeTTSRequest.TTSParameters params =
                    DashScopeTTSRequest.TTSParameters.builder()
                            .sampleRate(48000)
                            .format("mp3")
                            .rate(1.5)
                            .volume(80)
                            .pitch(1.2)
                            .build();

            assertEquals(48000, params.getSampleRate());
            assertEquals("mp3", params.getFormat());
            assertEquals(1.5, params.getRate());
            assertEquals(80, params.getVolume());
            assertEquals(1.2, params.getPitch());
        }

        @Test
        @DisplayName("should build TTSParameters with partial properties")
        void shouldBuildTTSParametersWithPartialProperties() {
            DashScopeTTSRequest.TTSParameters params =
                    DashScopeTTSRequest.TTSParameters.builder()
                            .sampleRate(16000)
                            .format("wav")
                            .build();

            assertEquals(16000, params.getSampleRate());
            assertEquals("wav", params.getFormat());
            assertNull(params.getRate());
            assertNull(params.getVolume());
            assertNull(params.getPitch());
        }

        @Test
        @DisplayName("should serialize TTSParameters to JSON")
        void shouldSerializeTTSParametersToJson() {
            DashScopeTTSRequest.TTSParameters params =
                    DashScopeTTSRequest.TTSParameters.builder()
                            .sampleRate(24000)
                            .format("wav")
                            .rate(1.0)
                            .volume(50)
                            .pitch(1.0)
                            .build();

            String json = JsonUtils.getJsonCodec().toJson(params);

            assertNotNull(json);
            assertTrue(json.contains("\"sample_rate\":24000"));
            assertTrue(json.contains("\"format\":\"wav\""));
            assertTrue(json.contains("\"rate\":1.0"));
            assertTrue(json.contains("\"volume\":50"));
            assertTrue(json.contains("\"pitch\":1.0"));
        }
    }

    @Nested
    @DisplayName("JSON Serialization Tests")
    class JsonSerializationTests {

        @Test
        @DisplayName("should serialize complete request to JSON")
        void shouldSerializeCompleteRequestToJson() {
            DashScopeTTSRequest.TTSInput input =
                    DashScopeTTSRequest.TTSInput.builder()
                            .text("Hello, world!")
                            .voice("Cherry")
                            .languageType("English")
                            .build();

            DashScopeTTSRequest.TTSParameters parameters =
                    DashScopeTTSRequest.TTSParameters.builder()
                            .sampleRate(24000)
                            .format("wav")
                            .rate(1.0)
                            .volume(50)
                            .pitch(1.0)
                            .build();

            DashScopeTTSRequest request =
                    DashScopeTTSRequest.builder()
                            .model("qwen3-tts-flash")
                            .input(input)
                            .parameters(parameters)
                            .build();

            String json = JsonUtils.getJsonCodec().toJson(request);

            assertNotNull(json);
            assertTrue(json.contains("\"model\":\"qwen3-tts-flash\""));
            assertTrue(json.contains("\"input\""));
            assertTrue(json.contains("\"parameters\""));
        }

        @Test
        @DisplayName("should exclude null fields from JSON")
        void shouldExcludeNullFieldsFromJson() {
            DashScopeTTSRequest request =
                    DashScopeTTSRequest.builder().model("qwen3-tts-flash").build();

            String json = JsonUtils.getJsonCodec().toJson(request);

            assertNotNull(json);
            assertTrue(json.contains("\"model\":\"qwen3-tts-flash\""));
            // null fields should be excluded due to @JsonInclude(NON_NULL)
            assertTrue(!json.contains("\"input\""));
        }

        @Test
        @DisplayName("should deserialize from JSON")
        void shouldDeserializeFromJson() {
            String json =
                    "{\"model\":\"qwen3-tts-flash\","
                        + "\"input\":{\"text\":\"Hello\",\"voice\":\"Cherry\",\"language_type\":\"English\"},"
                        + "\"parameters\":{\"sample_rate\":24000,\"format\":\"wav\",\"rate\":1.0,\"volume\":50,\"pitch\":1.0}}";

            DashScopeTTSRequest request =
                    JsonUtils.getJsonCodec().fromJson(json, DashScopeTTSRequest.class);

            assertNotNull(request);
            assertEquals("qwen3-tts-flash", request.getModel());
            assertNotNull(request.getInput());
            assertEquals("Hello", request.getInput().getText());
            assertNotNull(request.getParameters());
            assertEquals(24000, request.getParameters().getSampleRate());
        }

        @Test
        @DisplayName("should handle round-trip serialization")
        void shouldHandleRoundTripSerialization() {
            DashScopeTTSRequest.TTSInput input =
                    DashScopeTTSRequest.TTSInput.builder()
                            .text("Test")
                            .voice("Cherry")
                            .languageType("English")
                            .build();

            DashScopeTTSRequest.TTSParameters parameters =
                    DashScopeTTSRequest.TTSParameters.builder()
                            .sampleRate(24000)
                            .format("wav")
                            .rate(1.0)
                            .build();

            DashScopeTTSRequest original =
                    DashScopeTTSRequest.builder()
                            .model("qwen3-tts-flash")
                            .input(input)
                            .parameters(parameters)
                            .build();

            String json = JsonUtils.getJsonCodec().toJson(original);
            DashScopeTTSRequest deserialized =
                    JsonUtils.getJsonCodec().fromJson(json, DashScopeTTSRequest.class);

            assertEquals(original.getModel(), deserialized.getModel());
            assertEquals(original.getInput().getText(), deserialized.getInput().getText());
            assertEquals(original.getInput().getVoice(), deserialized.getInput().getVoice());
            assertEquals(
                    original.getParameters().getSampleRate(),
                    deserialized.getParameters().getSampleRate());
        }
    }
}
