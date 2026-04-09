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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for DashScopeTTSModel.
 */
class DashScopeTTSModelTest {

    private HttpTransport mockTransport;

    @BeforeEach
    void setUp() {
        mockTransport = mock(HttpTransport.class);
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should throw exception when API key is missing")
        void shouldThrowWhenApiKeyMissing() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DashScopeTTSModel.builder().modelName("qwen3-tts-flash").build());
        }

        @Test
        @DisplayName("should build with default values")
        void shouldBuildWithDefaults() {
            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            assertNotNull(model);
            assertEquals("qwen3-tts-flash", model.getModelName());
        }
    }

    @Nested
    @DisplayName("Synthesis Tests")
    class SynthesisTests {

        @Test
        @DisplayName("should synthesize text with URL response")
        void shouldSynthesizeWithUrlResponse() throws Exception {
            String responseJson =
                    """
                    {
                        "request_id": "test-request-id",
                        "output": {
                            "audio": {
                                "url": "https://example.com/audio.wav"
                            }
                        }
                    }
                    """;

            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(true);
            when(mockResponse.getBody()).thenReturn(responseJson);
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            TTSResponse response = model.synthesize("你好，世界！", null).block();

            assertNotNull(response);
            assertEquals("test-request-id", response.getRequestId());
            assertEquals("https://example.com/audio.wav", response.getAudioUrl());
        }

        @Test
        @DisplayName("should synthesize text with base64 audio data")
        void shouldSynthesizeWithBase64Response() throws Exception {
            byte[] audioBytes = "fake audio data".getBytes();
            String base64Audio = Base64.getEncoder().encodeToString(audioBytes);

            String responseJson =
                    String.format(
                            """
                            {
                                "request_id": "test-request-id",
                                "output": {
                                    "audio": {
                                        "data": "%s"
                                    }
                                }
                            }
                            """,
                            base64Audio);

            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(true);
            when(mockResponse.getBody()).thenReturn(responseJson);
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            TTSResponse response = model.synthesize("你好，世界！", null).block();

            assertNotNull(response);
            assertArrayEquals(audioBytes, response.getAudioData());
        }

        @Test
        @DisplayName("should place voice and language_type in input section")
        void shouldPlaceVoiceAndLanguageInInput() throws Exception {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(true);
            when(mockResponse.getBody())
                    .thenReturn(
                            "{\"request_id\":\"test\",\"output\":{\"audio\":{\"url\":\"http://test\"}}}");
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .voice("Cherry")
                            .httpTransport(mockTransport)
                            .build();

            TTSOptions options = TTSOptions.builder().language("Chinese").build();

            model.synthesize("测试文本", options).block();

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockTransport).execute(requestCaptor.capture());

            String requestBody = requestCaptor.getValue().getBody();
            assertTrue(
                    requestBody.contains("\"voice\":\"Cherry\""),
                    "voice should be in request body");
            assertTrue(
                    requestBody.contains("\"language_type\":\"Chinese\""),
                    "language_type should be in request body");
            assertTrue(requestBody.contains("\"text\":\"测试文本\""), "text should be in request body");
        }
    }

    @Nested
    @DisplayName("TTSResponse Tests")
    class TTSResponseTests {

        @Test
        @DisplayName("should convert base64 audio to AudioBlock")
        void shouldConvertBase64ToAudioBlock() {
            byte[] audioData = "test audio".getBytes();

            TTSResponse response = TTSResponse.builder().audioData(audioData).format("wav").build();

            AudioBlock audioBlock = response.toAudioBlock();

            assertNotNull(audioBlock);
            assertNotNull(audioBlock.getSource());
        }

        @Test
        @DisplayName("should convert URL to AudioBlock")
        void shouldConvertUrlToAudioBlock() {
            TTSResponse response =
                    TTSResponse.builder()
                            .audioUrl("https://example.com/audio.mp3")
                            .format("mp3")
                            .build();

            AudioBlock audioBlock = response.toAudioBlock();

            assertNotNull(audioBlock);
            assertNotNull(audioBlock.getSource());
        }

        @Test
        @DisplayName("should throw when no audio data or URL")
        void shouldThrowWhenNoAudioData() {
            TTSResponse response = TTSResponse.builder().requestId("test").build();

            assertThrows(IllegalStateException.class, response::toAudioBlock);
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should handle HTTP error response")
        void shouldHandleHttpErrorResponse() throws Exception {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(false);
            when(mockResponse.getStatusCode()).thenReturn(400);
            when(mockResponse.getBody()).thenReturn("Bad Request");
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            assertThrows(TTSException.class, () -> model.synthesize("test", null).block());
        }

        @Test
        @DisplayName("should handle API error in response")
        void shouldHandleApiErrorInResponse() throws Exception {
            String responseJson =
                    """
                    {
                        "code": "InvalidParameter",
                        "message": "Invalid text parameter"
                    }
                    """;

            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(true);
            when(mockResponse.getBody()).thenReturn(responseJson);
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            assertThrows(TTSException.class, () -> model.synthesize("test", null).block());
        }

        @Test
        @DisplayName("should handle response with no output")
        void shouldHandleResponseWithNoOutput() throws Exception {
            String responseJson = "{\"request_id\": \"test-id\"}";

            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(true);
            when(mockResponse.getBody()).thenReturn(responseJson);
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            TTSResponse response = model.synthesize("test", null).block();
            assertNotNull(response);
        }

        @Test
        @DisplayName("should handle response with empty audio URL")
        void shouldHandleResponseWithEmptyAudioUrl() throws Exception {
            String responseJson =
                    """
                    {
                        "request_id": "test-id",
                        "output": {
                            "audio": {
                                "url": ""
                            }
                        }
                    }
                    """;

            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(true);
            when(mockResponse.getBody()).thenReturn(responseJson);
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            TTSResponse response = model.synthesize("test", null).block();
            assertNotNull(response);
        }

        @Test
        @DisplayName("should handle response with null audio data")
        void shouldHandleResponseWithNullAudioData() throws Exception {
            String responseJson =
                    """
                    {
                        "request_id": "test-id",
                        "output": {
                            "audio": {
                                "data": null
                            }
                        }
                    }
                    """;

            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(true);
            when(mockResponse.getBody()).thenReturn(responseJson);
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            TTSResponse response = model.synthesize("test", null).block();
            assertNotNull(response);
        }

        @Test
        @DisplayName("should handle response with empty audio data")
        void shouldHandleResponseWithEmptyAudioData() throws Exception {
            String responseJson =
                    """
                    {
                        "request_id": "test-id",
                        "output": {
                            "audio": {
                                "data": ""
                            }
                        }
                    }
                    """;

            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(true);
            when(mockResponse.getBody()).thenReturn(responseJson);
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            TTSResponse response = model.synthesize("test", null).block();
            assertNotNull(response);
        }

        @Test
        @DisplayName("should handle JSON parsing error")
        void shouldHandleJsonParsingError() throws Exception {
            HttpResponse mockResponse = mock(HttpResponse.class);
            when(mockResponse.isSuccessful()).thenReturn(true);
            when(mockResponse.getBody()).thenReturn("invalid json");
            when(mockTransport.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .httpTransport(mockTransport)
                            .build();

            assertThrows(TTSException.class, () -> model.synthesize("test", null).block());
        }
    }

    @Nested
    @DisplayName("Builder Tests Extended")
    class BuilderTestsExtended {

        @Test
        @DisplayName("should build with custom base URL")
        void shouldBuildWithCustomBaseUrl() {
            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .baseUrl("https://custom.example.com")
                            .httpTransport(mockTransport)
                            .build();

            assertNotNull(model);
        }

        @Test
        @DisplayName("should build with default options")
        void shouldBuildWithDefaultOptions() {
            TTSOptions options = TTSOptions.builder().language("Chinese").build();
            DashScopeTTSModel model =
                    DashScopeTTSModel.builder()
                            .apiKey("test-api-key")
                            .defaultOptions(options)
                            .httpTransport(mockTransport)
                            .build();

            assertNotNull(model);
        }
    }
}
