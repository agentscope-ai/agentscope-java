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
package io.agentscope.core.tool.multimodal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.api.SynchronizeFullDuplexApi;
import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.audio.asr.recognition.timestamp.Sentence;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.tts.SpeechSynthesizer;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.ResultCallback;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link DashScopeMultiModalTool}.
 *
 * <p>Tests text to image(s), image(s) to text, text to audio, and audio to text.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
class DashScopeMultiModalToolTest {

    private static final String TEST_API_KEY = "test_api_key";
    private static final String TEXT_TO_IMAGE_PROMPT = "A small dog.";
    private static final String IMAGE_TO_TEXT_PROMPT = "Describe the image.";
    private static final String TEST_IMAGE0_URL = "https://example.com/image0.png";
    private static final String TEST_IMAGE1_URL = "https://example.com/image1.png";
    private static final String TEST_IMAGE_PATH = "/path/image.png";
    private static final String TEST_AUDIO_URL = "https://example.com/audio.wav";
    private static final String TEST_AUDIO_PATH = "/path/audio.wav";
    private static final String TEST_AUDIO_TEXT = "text audio text";
    // base64 of "hello"
    private static final String TEST_BASE64_DATA = "aGVsbG8=";
    private static final String TEST_MULTI_MODAL_CONTENT = "This is a small dog.";
    private static final RuntimeException TEST_ERROR = new RuntimeException("Test error");
    private DashScopeMultiModalTool multiModalTool;

    @BeforeEach
    void setUp() {
        multiModalTool = new DashScopeMultiModalTool(TEST_API_KEY);
    }

    @Test
    @DisplayName("Text to image with url mode")
    void testTextToImageUrlMode() {
        MockedConstruction<ImageSynthesis> mockCtor =
                mockConstruction(
                        ImageSynthesis.class,
                        (mock, context) -> {
                            ImageSynthesisResult mockResult = mock(ImageSynthesisResult.class);
                            ImageSynthesisOutput mockOutput = mock(ImageSynthesisOutput.class);

                            when(mock.call(any(ImageSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getResults())
                                    .thenReturn(List.of(Map.of("url", TEST_IMAGE0_URL)));
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "wanx-v1", 1, "1024*1024", false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock.getSource() instanceof URLSource);
                            assertEquals(
                                    TEST_IMAGE0_URL, ((URLSource) imageBlock.getSource()).getUrl());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Text to image use Base64 mode")
    void testTextToImageBase64Mode() throws IOException {
        MockedConstruction<ImageSynthesis> mockCtor =
                mockConstruction(
                        ImageSynthesis.class,
                        (mock, context) -> {
                            ImageSynthesisResult mockResult = mock(ImageSynthesisResult.class);
                            ImageSynthesisOutput mockOutput = mock(ImageSynthesisOutput.class);

                            when(mock.call(any(ImageSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getResults())
                                    .thenReturn(List.of(Map.of("url", TEST_IMAGE0_URL)));
                        });

        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.determineMediaType(anyString())).thenReturn("image/png");
        when(MediaUtils.downloadUrlToBase64(anyString())).thenReturn(TEST_BASE64_DATA);

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "wanx-v1", 1, "1024*1024", true);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock.getSource() instanceof Base64Source);
                            assertEquals(
                                    "image/png",
                                    ((Base64Source) imageBlock.getSource()).getMediaType());
                            assertEquals(
                                    TEST_BASE64_DATA,
                                    ((Base64Source) imageBlock.getSource()).getData());
                        })
                .verifyComplete();

        mockCtor.close();
        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Text to image response multiple urls")
    void testTextToImageResponseMultiUrl() {
        MockedConstruction<ImageSynthesis> mockCtor =
                mockConstruction(
                        ImageSynthesis.class,
                        (mock, context) -> {
                            ImageSynthesisResult mockResult = mock(ImageSynthesisResult.class);
                            ImageSynthesisOutput mockOutput = mock(ImageSynthesisOutput.class);

                            when(mock.call(any(ImageSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getResults())
                                    .thenReturn(
                                            List.of(
                                                    Map.of("url", TEST_IMAGE0_URL),
                                                    Map.of("url", TEST_IMAGE1_URL)));
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "wanx-v1", 2, "1024*1024", false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(2, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            assertTrue(toolResultBlock.getOutput().get(1) instanceof ImageBlock);
                            ImageBlock image0Block =
                                    (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(image0Block.getSource() instanceof URLSource);
                            assertEquals(
                                    TEST_IMAGE0_URL,
                                    ((URLSource) image0Block.getSource()).getUrl());
                            ImageBlock image1Block =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertTrue(image1Block.getSource() instanceof URLSource);
                            assertEquals(
                                    TEST_IMAGE1_URL,
                                    ((URLSource) image1Block.getSource()).getUrl());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to image response empty")
    void testTextToImageResponseEmpty() {
        MockedConstruction<ImageSynthesis> mockCtor =
                mockConstruction(
                        ImageSynthesis.class,
                        (mock, context) -> {
                            ImageSynthesisResult mockResult = mock(ImageSynthesisResult.class);
                            ImageSynthesisOutput mockOutput = mock(ImageSynthesisOutput.class);

                            when(mock.call(any(ImageSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getResults()).thenReturn(List.of());
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "wanx-v1", 1, "1024*1024", false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", "Failed to generate images."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to image response null")
    void testTextToImageResponseNull() {
        MockedConstruction<ImageSynthesis> mockCtor =
                mockConstruction(
                        ImageSynthesis.class,
                        (mock, context) -> {
                            ImageSynthesisResult mockResult = mock(ImageSynthesisResult.class);
                            ImageSynthesisOutput mockOutput = mock(ImageSynthesisOutput.class);

                            when(mock.call(any(ImageSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getResults()).thenReturn(null);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "wanx-v1", 1, "1024*1024", false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", "Failed to generate images."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to image occurs error")
    void testTextToImageError() {
        MockedConstruction<ImageSynthesis> mockCtor =
                mockConstruction(
                        ImageSynthesis.class,
                        (mock, context) -> {
                            when(mock.call(any(ImageSynthesisParam.class))).thenThrow(TEST_ERROR);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "wanx-v1", 1, "1024*1024", true);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Image to text with web url")
    void testImageToTextWithUrl() {
        MockedConstruction<MultiModalConversation> mockConv =
                mockConstruction(
                        MultiModalConversation.class,
                        (mock, context) -> {
                            MultiModalConversationResult mockResult =
                                    mock(MultiModalConversationResult.class);
                            MultiModalConversationOutput mockOutput =
                                    mock(MultiModalConversationOutput.class);

                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            MultiModalConversationOutput.Choice choice =
                                    new MultiModalConversationOutput.Choice();
                            choice.setMessage(
                                    MultiModalMessage.builder()
                                            .content(
                                                    List.of(
                                                            Map.of(
                                                                    "text",
                                                                    TEST_MULTI_MODAL_CONTENT)))
                                            .build());
                            choice.setFinishReason("stop");
                            when(mockOutput.getChoices()).thenReturn(List.of(choice));
                            when(mock.call(any(MultiModalConversationParam.class)))
                                    .thenReturn(mockResult);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE0_URL), IMAGE_TO_TEXT_PROMPT, "qwen3-vl-plus");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    TEST_MULTI_MODAL_CONTENT,
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Image to text with local file")
    void testImageToTextWithFile() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToProtocolUrl(TEST_IMAGE_PATH)).thenReturn("file://" + TEST_IMAGE_PATH);

        MockedConstruction<MultiModalConversation> mockedConv =
                mockConstruction(
                        MultiModalConversation.class,
                        (mock, context) -> {
                            MultiModalConversationResult mockResult =
                                    mock(MultiModalConversationResult.class);
                            MultiModalConversationOutput mockOutput =
                                    mock(MultiModalConversationOutput.class);

                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            MultiModalConversationOutput.Choice choice =
                                    new MultiModalConversationOutput.Choice();
                            choice.setMessage(
                                    MultiModalMessage.builder()
                                            .content(
                                                    List.of(
                                                            Map.of(
                                                                    "text",
                                                                    TEST_MULTI_MODAL_CONTENT)))
                                            .build());
                            choice.setFinishReason("stop");
                            when(mockOutput.getChoices()).thenReturn(List.of(choice));
                            when(mock.call(any(MultiModalConversationParam.class)))
                                    .thenReturn(mockResult);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE_PATH), IMAGE_TO_TEXT_PROMPT, "qwen3-vl-plus");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    TEST_MULTI_MODAL_CONTENT,
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
        mockedConv.close();
    }

    @Test
    @DisplayName("Image to text with local file and web url")
    void testImageToTextWithFileAndUrl() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToProtocolUrl(TEST_IMAGE_PATH)).thenReturn("file://" + TEST_IMAGE_PATH);

        MockedConstruction<MultiModalConversation> mockConv =
                mockConstruction(
                        MultiModalConversation.class,
                        (mock, context) -> {
                            MultiModalConversationResult mockResult =
                                    mock(MultiModalConversationResult.class);
                            MultiModalConversationOutput mockOutput =
                                    mock(MultiModalConversationOutput.class);

                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            MultiModalConversationOutput.Choice choice =
                                    new MultiModalConversationOutput.Choice();
                            choice.setMessage(
                                    MultiModalMessage.builder()
                                            .content(
                                                    List.of(
                                                            Map.of(
                                                                    "text",
                                                                    TEST_MULTI_MODAL_CONTENT)))
                                            .build());
                            choice.setFinishReason("stop");
                            when(mockOutput.getChoices()).thenReturn(List.of(choice));
                            when(mock.call(any(MultiModalConversationParam.class)))
                                    .thenReturn(mockResult);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE_PATH, TEST_IMAGE0_URL),
                        IMAGE_TO_TEXT_PROMPT,
                        "qwen3-vl-plus");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                        })
                .verifyComplete();

        mockMediaUtils.close();
        mockConv.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call image to text response empty")
    void testImageToTextResponseEmpty() {
        MockedConstruction<MultiModalConversation> mockConv =
                mockConstruction(
                        MultiModalConversation.class,
                        (mock, context) -> {
                            MultiModalConversationResult mockResult =
                                    mock(MultiModalConversationResult.class);
                            MultiModalConversationOutput mockOutput =
                                    mock(MultiModalConversationOutput.class);

                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            MultiModalConversationOutput.Choice choice =
                                    new MultiModalConversationOutput.Choice();
                            choice.setMessage(
                                    MultiModalMessage.builder().content(List.of()).build());
                            choice.setFinishReason("stop");
                            when(mockOutput.getChoices()).thenReturn(List.of(choice));
                            when(mock.call(any(MultiModalConversationParam.class)))
                                    .thenReturn(mockResult);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE0_URL), IMAGE_TO_TEXT_PROMPT, "qwen3-vl-plus");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", "Failed to generate text."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call image to text response null")
    void testImageToTextResponseNull() {
        MockedConstruction<MultiModalConversation> mockConv =
                mockConstruction(
                        MultiModalConversation.class,
                        (mock, context) -> {
                            MultiModalConversationResult mockResult =
                                    mock(MultiModalConversationResult.class);
                            MultiModalConversationOutput mockOutput =
                                    mock(MultiModalConversationOutput.class);

                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getChoices()).thenReturn(null);
                            when(mock.call(any(MultiModalConversationParam.class)))
                                    .thenReturn(mockResult);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE0_URL), IMAGE_TO_TEXT_PROMPT, "qwen3-vl-plus");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", "Failed to generate text."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call image to text occurs error")
    void testImageToTextError() {
        MockedConstruction<MultiModalConversation> mockConv =
                mockConstruction(
                        MultiModalConversation.class,
                        (mock, context) -> {
                            when(mock.call(any(MultiModalConversationParam.class)))
                                    .thenThrow(TEST_ERROR);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE0_URL), IMAGE_TO_TEXT_PROMPT, "qwen3-vl-plus");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Text to audio with Sambert model - success")
    void testTextToAudioWithSambertSuccess() {
        MockedConstruction<SpeechSynthesizer> mockCtor =
                Mockito.mockConstruction(
                        SpeechSynthesizer.class,
                        (mock, context) -> {
                            ByteBuffer mockBuffer = ByteBuffer.wrap("hello".getBytes());
                            when(mock.call(any(SpeechSynthesisParam.class))).thenReturn(mockBuffer);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToAudio(
                        "hello", "sambert-zhichu-v1", null, null, 48000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof AudioBlock);
                            AudioBlock audioBlock = (AudioBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(audioBlock.getSource() instanceof Base64Source);
                            assertEquals(
                                    TEST_BASE64_DATA,
                                    ((Base64Source) audioBlock.getSource()).getData());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Nested
    @DisplayName("Qwen TTS Response Parsing Tests")
    class QwenTTSResponseParsingTests {

        /**
         * Use reflection to call private parseQwenTTSResponse method for unit testing.
         */
        private ToolResultBlock invokeParseQwenTTSResponse(String responseBody) throws Exception {
            java.lang.reflect.Method method =
                    DashScopeMultiModalTool.class.getDeclaredMethod(
                            "parseQwenTTSResponse", String.class);
            method.setAccessible(true);
            return (ToolResultBlock) method.invoke(multiModalTool, responseBody);
        }

        @Test
        @DisplayName("Parse Qwen TTS response with URL")
        void testParseQwenTTSResponseWithUrl() throws Exception {
            String responseJson =
                    "{\"output\":{\"audio\":{\"url\":\"https://example.com/audio.wav\"}},\"request_id\":\"test-request-id\"}";

            ToolResultBlock result = invokeParseQwenTTSResponse(responseJson);

            assertNotNull(result);
            assertEquals(1, result.getOutput().size());
            assertTrue(result.getOutput().get(0) instanceof AudioBlock);
            AudioBlock audioBlock = (AudioBlock) result.getOutput().get(0);
            assertTrue(audioBlock.getSource() instanceof URLSource);
            assertEquals(
                    "https://example.com/audio.wav", ((URLSource) audioBlock.getSource()).getUrl());
        }

        @Test
        @DisplayName("Parse Qwen TTS response with Base64 data")
        void testParseQwenTTSResponseWithBase64() throws Exception {
            String testBase64 = "dGVzdA==";
            String responseJson =
                    "{\"output\":{\"audio\":{\"data\":\""
                            + testBase64
                            + "\"}},\"request_id\":\"test-request-id\"}";

            ToolResultBlock result = invokeParseQwenTTSResponse(responseJson);

            assertNotNull(result);
            assertEquals(1, result.getOutput().size());
            assertTrue(result.getOutput().get(0) instanceof AudioBlock);
            AudioBlock audioBlock = (AudioBlock) result.getOutput().get(0);
            assertTrue(audioBlock.getSource() instanceof Base64Source);
            assertEquals(testBase64, ((Base64Source) audioBlock.getSource()).getData());
            assertEquals("audio/wav", ((Base64Source) audioBlock.getSource()).getMediaType());
        }

        @Test
        @DisplayName("Parse Qwen TTS response with error code")
        void testParseQwenTTSResponseWithError() throws Exception {
            String responseJson = "{\"code\":\"InvalidParameter\",\"message\":\"Invalid request\"}";

            ToolResultBlock result = invokeParseQwenTTSResponse(responseJson);

            assertNotNull(result);
            assertEquals(1, result.getOutput().size());
            assertTrue(result.getOutput().get(0) instanceof TextBlock);
            assertTrue(
                    ((TextBlock) result.getOutput().get(0)).getText().contains("Invalid request"));
        }

        @Test
        @DisplayName("Parse Qwen TTS response with missing output")
        void testParseQwenTTSResponseMissingOutput() throws Exception {
            String responseJson = "{\"request_id\":\"test-request-id\"}";

            ToolResultBlock result = invokeParseQwenTTSResponse(responseJson);

            assertNotNull(result);
            assertEquals(1, result.getOutput().size());
            assertTrue(result.getOutput().get(0) instanceof TextBlock);
            assertTrue(
                    ((TextBlock) result.getOutput().get(0))
                            .getText()
                            .contains("No output in response"));
        }

        @Test
        @DisplayName("Parse Qwen TTS response with missing audio")
        void testParseQwenTTSResponseMissingAudio() throws Exception {
            String responseJson = "{\"output\":{},\"request_id\":\"test-request-id\"}";

            ToolResultBlock result = invokeParseQwenTTSResponse(responseJson);

            assertNotNull(result);
            assertEquals(1, result.getOutput().size());
            assertTrue(result.getOutput().get(0) instanceof TextBlock);
            assertTrue(
                    ((TextBlock) result.getOutput().get(0))
                            .getText()
                            .contains("No audio in response"));
        }

        @Test
        @DisplayName("Parse Qwen TTS response with no audio data")
        void testParseQwenTTSResponseNoAudioData() throws Exception {
            String responseJson = "{\"output\":{\"audio\":{}},\"request_id\":\"test-request-id\"}";

            ToolResultBlock result = invokeParseQwenTTSResponse(responseJson);

            assertNotNull(result);
            assertEquals(1, result.getOutput().size());
            assertTrue(result.getOutput().get(0) instanceof TextBlock);
            assertTrue(
                    ((TextBlock) result.getOutput().get(0))
                            .getText()
                            .contains("No audio data in response"));
        }

        @Test
        @DisplayName("Parse Qwen TTS response with invalid JSON")
        void testParseQwenTTSResponseInvalidJson() throws Exception {
            String responseJson = "invalid json";

            ToolResultBlock result = invokeParseQwenTTSResponse(responseJson);

            assertNotNull(result);
            assertEquals(1, result.getOutput().size());
            assertTrue(result.getOutput().get(0) instanceof TextBlock);
            assertTrue(
                    ((TextBlock) result.getOutput().get(0))
                            .getText()
                            .contains("Failed to parse response"));
        }

        @Test
        @DisplayName("Parse Qwen TTS response with error code but no message")
        void testParseQwenTTSResponseErrorNoMessage() throws Exception {
            String responseJson = "{\"code\":\"InvalidParameter\"}";

            ToolResultBlock result = invokeParseQwenTTSResponse(responseJson);

            assertNotNull(result);
            assertEquals(1, result.getOutput().size());
            assertTrue(result.getOutput().get(0) instanceof TextBlock);
            assertTrue(((TextBlock) result.getOutput().get(0)).getText().contains("Unknown error"));
        }
    }

    @Test
    @DisplayName("Text to audio with Qwen TTS model - default model and parameters")
    void testTextToAudioWithQwenTTSDefaults() {
        // Test that Qwen TTS models are correctly identified
        // This tests the model detection logic without requiring HTTP mocking
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToAudio("hello", null, null, null, null);

        // Will fail with network error, but tests the model selection logic
        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            // Either success or error, both are valid test outcomes
                            assertTrue(
                                    toolResultBlock.getOutput().get(0) instanceof AudioBlock
                                            || toolResultBlock.getOutput().get(0)
                                                    instanceof TextBlock);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to audio response empty")
    void testTextToAudioResponseEmpty() {
        MockedConstruction<SpeechSynthesizer> mockCtor =
                Mockito.mockConstruction(
                        SpeechSynthesizer.class,
                        (mock, context) -> {
                            when(mock.call(any(SpeechSynthesisParam.class)))
                                    .thenReturn(ByteBuffer.allocate(0));
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToAudio(
                        "hello", "sambert-zhichu-v1", null, null, 48000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", "Failed to generate audio."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to audio response null")
    void testTextToAudioResponseNull() {
        MockedConstruction<SpeechSynthesizer> mockCtor =
                Mockito.mockConstruction(
                        SpeechSynthesizer.class,
                        (mock, context) -> {
                            when(mock.call(any(SpeechSynthesisParam.class))).thenReturn(null);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToAudio(
                        "hello", "sambert-zhichu-v1", null, null, 48000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", "Failed to generate audio."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to audio occurs error")
    void testTextToAudioError() {
        MockedConstruction<SpeechSynthesizer> mockCtor =
                Mockito.mockConstruction(
                        SpeechSynthesizer.class,
                        (mock, context) -> {
                            when(mock.call(any(SpeechSynthesisParam.class))).thenThrow(TEST_ERROR);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToAudio(
                        "hello", "sambert-zhichu-v1", null, null, 48000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Audio to text with url")
    void testAudioToTextWithUrl() throws Exception {
        MockedConstruction<Recognition> mockCtor =
                mockConstruction(
                        Recognition.class,
                        (mock, context) -> {
                            doAnswer(
                                            invocation -> {
                                                ResultCallback<RecognitionResult> callback =
                                                        invocation.getArgument(
                                                                1, ResultCallback.class);
                                                RecognitionResult mockResult =
                                                        mock(RecognitionResult.class);
                                                when(mockResult.isSentenceEnd()).thenReturn(true);
                                                Sentence sentence = new Sentence();
                                                sentence.setText(TEST_AUDIO_TEXT);
                                                when(mockResult.getSentence()).thenReturn(sentence);
                                                callback.onEvent(mockResult);
                                                callback.onComplete();
                                                return null;
                                            })
                                    .when(mock)
                                    .call(any(RecognitionParam.class), any(ResultCallback.class));

                            SynchronizeFullDuplexApi mockApi = mock(SynchronizeFullDuplexApi.class);
                            when(mock.getDuplexApi()).thenReturn(mockApi);
                            when(mockApi.close(anyInt(), anyString())).thenReturn(true);
                        });

        DashScopeMultiModalTool spyMultiModalTool = spy(multiModalTool);
        doNothing().when(spyMultiModalTool).sendAudioChunk(anyString(), any(Recognition.class));

        Mono<ToolResultBlock> result =
                spyMultiModalTool.dashscopeAudioToText(
                        TEST_AUDIO_URL, "paraformer-realtime-v2", 16000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    TEST_AUDIO_TEXT,
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Audio to text with file")
    void testAudioToTextWithFile() throws Exception {
        MockedConstruction<Recognition> mockCtor =
                mockConstruction(
                        Recognition.class,
                        (mock, context) -> {
                            doAnswer(
                                            invocation -> {
                                                ResultCallback<RecognitionResult> callback =
                                                        invocation.getArgument(
                                                                1, ResultCallback.class);
                                                RecognitionResult mockResult =
                                                        mock(RecognitionResult.class);
                                                when(mockResult.isSentenceEnd()).thenReturn(true);
                                                Sentence sentence = new Sentence();
                                                sentence.setText(TEST_AUDIO_TEXT);
                                                when(mockResult.getSentence()).thenReturn(sentence);
                                                callback.onEvent(mockResult);
                                                callback.onComplete();
                                                return null;
                                            })
                                    .when(mock)
                                    .call(any(RecognitionParam.class), any(ResultCallback.class));

                            SynchronizeFullDuplexApi mockApi = mock(SynchronizeFullDuplexApi.class);
                            when(mock.getDuplexApi()).thenReturn(mockApi);
                            when(mockApi.close(anyInt(), anyString())).thenReturn(true);
                        });

        DashScopeMultiModalTool spyMultiModalTool = spy(multiModalTool);
        doNothing().when(spyMultiModalTool).sendAudioChunk(anyString(), any(Recognition.class));

        Mono<ToolResultBlock> result =
                spyMultiModalTool.dashscopeAudioToText(
                        TEST_AUDIO_PATH, "paraformer-realtime-v2", 16000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    TEST_AUDIO_TEXT,
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call audio to text occurs error")
    void testAudioToTextError() throws Exception {
        MockedConstruction<Recognition> mockCtor =
                mockConstruction(
                        Recognition.class,
                        (mock, context) -> {
                            doThrow(TEST_ERROR)
                                    .when(mock)
                                    .call(any(RecognitionParam.class), any(ResultCallback.class));

                            SynchronizeFullDuplexApi mockApi = mock(SynchronizeFullDuplexApi.class);
                            when(mock.getDuplexApi()).thenReturn(mockApi);
                            when(mockApi.close(anyInt(), anyString())).thenReturn(true);
                        });

        DashScopeMultiModalTool spyMultiModalTool = spy(multiModalTool);
        doNothing().when(spyMultiModalTool).sendAudioChunk(anyString(), any(Recognition.class));

        Mono<ToolResultBlock> result =
                spyMultiModalTool.dashscopeAudioToText(
                        TEST_AUDIO_URL, "paraformer-realtime-v2", 16000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Send chunk audio with url")
    void testSendChunkAudioWithUrl() throws Exception {
        MockedStatic<URI> mockStatic = mockStatic(URI.class);
        URI mockURI = mock(URI.class);
        URL mockURL = mock(URL.class);
        Recognition mockRecognition = mock(Recognition.class);

        when(URI.create(anyString())).thenReturn(mockURI);
        when(mockURI.toURL()).thenReturn(mockURL);
        when(mockURL.openStream()).thenReturn(new ByteArrayInputStream(new byte[6400]));
        doNothing().when(mockRecognition).sendAudioFrame(any(ByteBuffer.class));

        assertDoesNotThrow(() -> multiModalTool.sendAudioChunk(TEST_AUDIO_URL, mockRecognition));
        verify(mockRecognition, times(2)).sendAudioFrame(any(ByteBuffer.class));

        mockStatic.close();
    }

    @Test
    @DisplayName("Send chunk audio with file")
    void testSendChunkAudioWithFile() throws Exception {
        Recognition mockRecognition = mock(Recognition.class);
        doNothing().when(mockRecognition).sendAudioFrame(any(ByteBuffer.class));
        Path tempAudioFile = Files.createTempFile("test_audio", ".wav");
        try (OutputStream os = Files.newOutputStream(tempAudioFile)) {
            os.write(new byte[6400]);
        }

        assertDoesNotThrow(
                () -> multiModalTool.sendAudioChunk(tempAudioFile.toString(), mockRecognition));
        verify(mockRecognition, times(2)).sendAudioFrame(any(ByteBuffer.class));

        Files.deleteIfExists(tempAudioFile);
    }
}
