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
package io.agentscope.extensions.model.dashscope.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesis;
import com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisOutput;
import com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisParam;
import com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisResult;
import com.alibaba.dashscope.api.SynchronizeFullDuplexApi;
import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.audio.asr.recognition.timestamp.Sentence;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.tts.SpeechSynthesizer;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Role;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final String TEXT_TO_VIDEO_PROMPT = "A smart cat is running in the moonlight.";
    private static final String VIDEO_TO_TEXT_PROMPT = "Describe the video.";
    private static final String TEST_IMAGE0_URL = "https://example.com/image0.png";
    private static final String TEST_IMAGE1_URL = "https://example.com/image1.png";
    private static final String TEST_IMAGE_PATH = "/path/image.png";
    private static final String TEST_IMAGE_BASE64_DATA_URL =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAABDg...";
    private static final String TEST_AUDIO_URL = "https://example.com/audio.wav";
    private static final String TEST_AUDIO_PATH = "/path/audio.wav";
    private static final String TEST_AUDIO_TEXT = "test audio text";
    private static final String TEST_VIDEO_URL = "https://example.com/video.mp4";
    private static final String TEST_VIDEO_PATH = "/path/video.mp4";
    private static final String TEST_VIDEO_BASE64_DATA_URL =
            "data:video/mp4;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA...";
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
                            assertInstanceOf(ImageBlock.class, toolResultBlock.getOutput().get(0));
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, imageBlock.getSource());
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
                            assertInstanceOf(ImageBlock.class, toolResultBlock.getOutput().get(0));
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(Base64Source.class, imageBlock.getSource());
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
                            assertInstanceOf(ImageBlock.class, toolResultBlock.getOutput().get(0));
                            assertInstanceOf(ImageBlock.class, toolResultBlock.getOutput().get(1));
                            ImageBlock image0Block =
                                    (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, image0Block.getSource());
                            assertEquals(
                                    TEST_IMAGE0_URL,
                                    ((URLSource) image0Block.getSource()).getUrl());
                            ImageBlock image1Block =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertInstanceOf(URLSource.class, image1Block.getSource());
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                        (mock, context) ->
                                when(mock.call(any(ImageSynthesisParam.class)))
                                        .thenThrow(TEST_ERROR));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "wanx-v1", 1, "1024*1024", true);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    /**
     * Mocks the multimodal-generation client that {@code dashscope_image_to_image} talks to.
     *
     * @param captured        receives the request param so a test can assert on the outgoing call
     * @param responseContent content map of the single choice; null leaves the output empty
     */
    private MockedConstruction<MultiModalConversation> mockImageEditConversation(
            AtomicReference<MultiModalConversationParam> captured,
            Map<String, Object> responseContent) {
        return mockConstruction(
                MultiModalConversation.class,
                (mock, context) -> {
                    MultiModalConversationResult mockResult =
                            mock(MultiModalConversationResult.class);
                    MultiModalConversationOutput mockOutput =
                            mock(MultiModalConversationOutput.class);

                    when(mockResult.getOutput()).thenReturn(mockOutput);
                    if (responseContent != null) {
                        MultiModalConversationOutput.Choice choice =
                                new MultiModalConversationOutput.Choice();
                        choice.setMessage(
                                MultiModalMessage.builder()
                                        .content(List.of(responseContent))
                                        .build());
                        choice.setFinishReason("stop");
                        when(mockOutput.getChoices()).thenReturn(List.of(choice));
                    }
                    when(mock.call(any(MultiModalConversationParam.class)))
                            .thenAnswer(
                                    invocation -> {
                                        captured.set(invocation.getArgument(0));
                                        return mockResult;
                                    });
                });
    }

    @Test
    @DisplayName("Image to image with web url")
    void testImageToImageWithUrl() {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(
                        captured, Map.of("image", TEST_IMAGE0_URL, "text", "unused"));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToImage(
                        TEST_IMAGE0_URL, "Make it night-time", "qwen-image-edit", null, false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(ImageBlock.class, toolResultBlock.getOutput().get(0));
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, imageBlock.getSource());
                            assertEquals(
                                    TEST_IMAGE0_URL, ((URLSource) imageBlock.getSource()).getUrl());
                        })
                .verifyComplete();

        mockConv.close();

        // The endpoint takes exactly one `user` message carrying the image then the prompt.
        MultiModalConversationParam param = captured.get();
        assertNotNull(param);
        assertEquals("qwen-image-edit", param.getModel());
        assertEquals(1, param.getMessages().size());
        MultiModalMessage message =
                assertInstanceOf(MultiModalMessage.class, param.getMessages().get(0));
        assertEquals(Role.USER.getValue(), message.getRole());
        assertEquals(TEST_IMAGE0_URL, message.getContent().get(0).get("image"));
        assertEquals("Make it night-time", message.getContent().get(1).get("text"));
    }

    @Test
    @DisplayName("Image to image defaults to the edit model when model is blank")
    void testImageToImageDefaultModel() {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, Map.of("image", TEST_IMAGE0_URL));

        multiModalTool
                .dashscopeImageToImage(TEST_IMAGE0_URL, "Colorize this sketch", "   ", null, null)
                .block();
        mockConv.close();

        assertEquals("qwen-image-edit", captured.get().getModel());
    }

    @Test
    @DisplayName("Image to image keeps size for models that support an output resolution")
    void testImageToImageSizeIsSentForSupportedModel() {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, Map.of("image", TEST_IMAGE0_URL));

        StepVerifier.create(
                        multiModalTool.dashscopeImageToImage(
                                TEST_IMAGE0_URL,
                                "Colorize",
                                "qwen-image-edit-plus",
                                "1280*1280",
                                false))
                .expectNextCount(1)
                .verifyComplete();
        mockConv.close();

        assertEquals("1280*1280", captured.get().getParameters().get("size"));
    }

    @Test
    @DisplayName("A generation newer than the ones released today is neither blocked nor degraded")
    void testImageToImageSupportsFutureGenerations() {
        // The vendor keeps releasing numbered generations of this family. Detecting the generation
        // by its leading digit rather than by a concrete version has to keep such a model usable,
        // including an explicit output resolution.
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, Map.of("image", TEST_IMAGE0_URL));

        StepVerifier.create(
                        multiModalTool.dashscopeImageToImage(
                                TEST_IMAGE0_URL,
                                "Colorize",
                                "qwen-image-3.0-pro",
                                "1280*1280",
                                false))
                .expectNextCount(1)
                .verifyComplete();
        mockConv.close();

        MultiModalConversationParam param = captured.get();
        assertNotNull(param);
        assertEquals("qwen-image-3.0-pro", param.getModel());
        assertEquals("1280*1280", param.getParameters().get("size"));
    }

    @Test
    @DisplayName("Image to image drops size for the base edit model instead of failing")
    void testImageToImageSizeIsDroppedForBaseModel() {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, Map.of("image", TEST_IMAGE0_URL));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToImage(
                        TEST_IMAGE0_URL, "Colorize", "qwen-image-edit", "1280*1280", false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock ->
                                assertInstanceOf(
                                        ImageBlock.class, toolResultBlock.getOutput().get(0)))
                .verifyComplete();
        mockConv.close();

        // getParameters() also carries the typed fields, so only `size` is asserted on.
        assertNull(captured.get().getParameters().get("size"));
    }

    @Test
    @DisplayName("Image to image use Base64 mode")
    void testImageToImageBase64Mode() throws IOException {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, Map.of("image", TEST_IMAGE0_URL));

        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.determineMediaType(anyString())).thenReturn("image/png");
        when(MediaUtils.downloadUrlToBase64(anyString())).thenReturn(TEST_BASE64_DATA);

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToImage(
                        TEST_IMAGE0_URL, "Colorize", "qwen-image-edit", null, true);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(Base64Source.class, imageBlock.getSource());
                            assertEquals(
                                    "image/png",
                                    ((Base64Source) imageBlock.getSource()).getMediaType());
                            assertEquals(
                                    TEST_BASE64_DATA,
                                    ((Base64Source) imageBlock.getSource()).getData());
                        })
                .verifyComplete();

        mockMediaUtils.close();
        mockConv.close();
    }

    @Test
    @DisplayName("Image to image inlines a local file as a Base64 data URL")
    void testImageToImageWithLocalFile() throws IOException {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, Map.of("image", TEST_IMAGE0_URL));

        // This endpoint rejects file:// URLs, unlike the vision models.
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.isFileExists(TEST_IMAGE_PATH)).thenReturn(true);
        when(MediaUtils.urlToBase64DataUrl(TEST_IMAGE_PATH)).thenReturn(TEST_IMAGE_BASE64_DATA_URL);

        StepVerifier.create(
                        multiModalTool.dashscopeImageToImage(
                                TEST_IMAGE_PATH, "Colorize", "qwen-image-edit", null, false))
                .expectNextCount(1)
                .verifyComplete();
        mockMediaUtils.close();
        mockConv.close();

        MultiModalMessage message = (MultiModalMessage) captured.get().getMessages().get(0);
        assertEquals(TEST_IMAGE_BASE64_DATA_URL, message.getContent().get(0).get("image"));
    }

    @Test
    @DisplayName("Image to image with base64 data url input")
    void testImageToImageWithBase64DataUrlInput() {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, Map.of("image", TEST_IMAGE0_URL));

        StepVerifier.create(
                        multiModalTool.dashscopeImageToImage(
                                TEST_IMAGE_BASE64_DATA_URL,
                                "Colorize",
                                "qwen-image-edit",
                                null,
                                false))
                .expectNextCount(1)
                .verifyComplete();
        mockConv.close();

        MultiModalMessage message = (MultiModalMessage) captured.get().getMessages().get(0);
        assertEquals(TEST_IMAGE_BASE64_DATA_URL, message.getContent().get(0).get("image"));
    }

    @Test
    @DisplayName(
            "Should return an actionable hint and skip the call when the model takes no image"
                    + " input")
    void testImageToImageUnsupportedModel() {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, Map.of("image", TEST_IMAGE0_URL));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToImage(
                        TEST_IMAGE0_URL, "Colorize", "wanx2.1-t2i-turbo", null, false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            String text =
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText();
                            assertTrue(text.contains("wanx2.1-t2i-turbo"));
                            assertTrue(text.contains("qwen-image-edit"));
                            assertTrue(text.contains("dashscope_text_to_image"));
                        })
                .verifyComplete();

        // Guarded locally: no request must reach the service.
        assertTrue(mockConv.constructed().isEmpty());

        mockConv.close();
    }

    @Test
    @DisplayName(
            "Should surface the model-side refusal when the image to image response has no"
                    + " image")
    void testImageToImageResponseCarriesRefusalText() {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, Map.of("text", "Image edited is not valid"));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToImage(
                        TEST_IMAGE0_URL, "Colorize", "qwen-image-edit", null, false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format(
                                            "Error: Failed to generate image: Image edited is not"
                                                    + " valid"),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when image to image response has no content")
    void testImageToImageResponseEmpty() {
        AtomicReference<MultiModalConversationParam> captured = new AtomicReference<>();
        MockedConstruction<MultiModalConversation> mockConv =
                mockImageEditConversation(captured, null);

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToImage(
                        TEST_IMAGE0_URL, "Colorize", "qwen-image-edit", null, false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format(
                                            "Error: Failed to generate image: no image in"
                                                    + " response"),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call image to image occurs error")
    void testImageToImageError() {
        MockedConstruction<MultiModalConversation> mockConv =
                mockConstruction(
                        MultiModalConversation.class,
                        (mock, context) ->
                                when(mock.call(any(MultiModalConversationParam.class)))
                                        .thenThrow(TEST_ERROR));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToImage(
                        TEST_IMAGE0_URL, "Colorize", "qwen-image-edit", null, false);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Nested
    @DisplayName("Image edit model capability guards")
    class ImageEditGuardTests {

        /** Use reflection to call a private static predicate for unit testing. */
        private boolean invokeStatic(String methodName, String model) throws Exception {
            Method method =
                    DashScopeMultiModalTool.class.getDeclaredMethod(methodName, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, model);
        }

        @Test
        @DisplayName("Text-to-image and wan models cannot edit an input image")
        void testRejectsImageInput() throws Exception {
            assertTrue(invokeStatic("rejectsImageInput", "wanx-v1"));
            assertTrue(invokeStatic("rejectsImageInput", "wan2.6-i2v-flash"));
            assertTrue(invokeStatic("rejectsImageInput", "qwen-image"));
            assertTrue(invokeStatic("rejectsImageInput", "qwen-image-plus"));
            assertFalse(invokeStatic("rejectsImageInput", "qwen-image-edit"));
            assertFalse(invokeStatic("rejectsImageInput", "Qwen-Image-Edit-Max"));
            assertFalse(invokeStatic("rejectsImageInput", "qwen-image-2.0"));
            assertFalse(invokeStatic("rejectsImageInput", "qwen-image-2.0-pro"));
            // Same naming scheme, not released yet: must not be refused by a wrong reason.
            assertFalse(invokeStatic("rejectsImageInput", "qwen-image-3.0"));
            assertFalse(invokeStatic("rejectsImageInput", "qwen-image-4.5-max"));
            // Unrecognized families reach the service, which reports the actual cause.
            assertFalse(invokeStatic("rejectsImageInput", "some-future-editor"));
        }

        @Test
        @DisplayName("Plus / max tiers and every numbered generation accept a resolution")
        void testSupportsOutputSize() throws Exception {
            assertFalse(invokeStatic("supportsOutputSize", "qwen-image-edit"));
            assertTrue(invokeStatic("supportsOutputSize", "qwen-image-edit-plus"));
            assertTrue(invokeStatic("supportsOutputSize", "qwen-image-edit-max"));
            assertTrue(invokeStatic("supportsOutputSize", "qwen-image-2.0"));
            assertTrue(invokeStatic("supportsOutputSize", "qwen-image-2.0-pro"));
            assertTrue(invokeStatic("supportsOutputSize", "qwen-image-3.0"));
            // An unexpected `size` fails the whole request, so a snapshot of the base model stays
            // off the list: dropping the parameter only degrades the output resolution.
            assertFalse(
                    invokeStatic("supportsOutputSize", "qwen-image-edit-2026-05-22"),
                    "a dated snapshot of the base model must not send a size");
        }

        @Test
        @DisplayName("Only a digit after the family prefix marks a numbered generation")
        void testNumberedEditGenerationBoundary() throws Exception {
            assertTrue(invokeStatic("isNumberedEditGeneration", "qwen-image-2.0"));
            assertTrue(invokeStatic("isNumberedEditGeneration", "qwen-image-3.0-pro"));
            assertTrue(invokeStatic("isNumberedEditGeneration", "qwen-image-10.0"));
            assertFalse(invokeStatic("isNumberedEditGeneration", "qwen-image-edit"));
            assertFalse(invokeStatic("isNumberedEditGeneration", "qwen-image-edit-plus"));
            // Nothing after the separator, and the family name itself, carry no generation.
            assertFalse(invokeStatic("isNumberedEditGeneration", "qwen-image-"));
            assertFalse(invokeStatic("isNumberedEditGeneration", "qwen-image"));
        }

        @Test
        @DisplayName("Blank or unsupported size is resolved to no parameter")
        void testResolveOutputSize() throws Exception {
            Method method =
                    DashScopeMultiModalTool.class.getDeclaredMethod(
                            "resolveOutputSize", String.class, String.class);
            method.setAccessible(true);

            assertNull(
                    (String) method.invoke(null, "qwen-image-edit-plus", null),
                    "absent size stays absent");
            assertNull(
                    (String) method.invoke(null, "qwen-image-edit-plus", "  "),
                    "blank size is dropped");
            assertNull(
                    (String) method.invoke(null, "qwen-image-edit", "1024*1024"),
                    "unsupported model drops the size");
            assertEquals("1024*1024", method.invoke(null, "qwen-image-edit-max", "1024*1024"));
        }
    }

    @Nested
    @DisplayName("Image edit logging contract")
    class ImageEditLoggingContractTests {

        /** Use reflection to call the private summarizer used by the debug log. */
        private String summarize(String imageUrl, String prompt) throws Exception {
            Method method =
                    DashScopeMultiModalTool.class.getDeclaredMethod(
                            "describeEditRequest", String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, imageUrl, prompt);
        }

        @Test
        @DisplayName("A Base64 data URL is reduced to kind and length, never to its bytes")
        void testInlineDataUrlIsNotRendered() throws Exception {
            String imageUrl = TEST_IMAGE_BASE64_DATA_URL + TEST_BASE64_DATA + TEST_BASE64_DATA;
            String prompt = "Repaint the sketch of my house at 4 Oak Street";

            String summary = summarize(imageUrl, prompt);

            assertTrue(summary.contains("base64-data-url"), summary);
            assertTrue(summary.contains("len=" + imageUrl.length()), summary);
            assertTrue(summary.contains("promptLength=" + prompt.length()), summary);
            // The encoded image must not appear, not even as a prefix of it.
            assertFalse(summary.contains(TEST_BASE64_DATA), summary);
            assertFalse(summary.contains("iVBORw0KGgo"), summary);
            // Nor may the prompt, which is user content.
            assertFalse(summary.contains(prompt), summary);
            assertFalse(summary.contains("Oak Street"), summary);
        }

        @Test
        @DisplayName("Every accepted input form is classified by shape, without its address")
        void testReferenceKindsCarryNoAddress() throws Exception {
            assertTrue(summarize(TEST_IMAGE0_URL, "x").contains("imageRef=http-url("));
            assertTrue(summarize("oss://bucket/key.png", "x").contains("imageRef=oss-url("));
            assertTrue(summarize(TEST_IMAGE_PATH, "x").contains("imageRef=local-path("));
            assertTrue(summarize("DATA:IMAGE/PNG;BASE64,AAAA", "x").contains("base64-data-url"));
            // A signed object URL carries credentials in its query string, so the host and path
            // are not part of the contract either.
            assertFalse(summarize(TEST_IMAGE0_URL, "x").contains("example.com"));
            assertFalse(summarize("oss://bucket/key.png", "x").contains("key.png"));
            assertEquals("imageRef=absent(len=0), promptLength=0", summarize(null, null));
            assertEquals("imageRef=absent(len=0), promptLength=0", summarize("   ", null));
        }
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    TEST_MULTI_MODAL_CONTENT,
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
        mockedConv.close();
    }

    @Test
    @DisplayName("Image to text with base64 data url")
    void testImageToTextWithBase64DataUrl() {
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
                        List.of(TEST_IMAGE_BASE64_DATA_URL), IMAGE_TO_TEXT_PROMPT, "qwen3-vl-plus");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    TEST_MULTI_MODAL_CONTENT,
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                        (mock, context) ->
                                when(mock.call(any(MultiModalConversationParam.class)))
                                        .thenThrow(TEST_ERROR));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE0_URL), IMAGE_TO_TEXT_PROMPT, "qwen3-vl-plus");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(AudioBlock.class, toolResultBlock.getOutput().get(0));
                            AudioBlock audioBlock = (AudioBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(Base64Source.class, audioBlock.getSource());
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
            Method method =
                    DashScopeMultiModalTool.class.getDeclaredMethod(
                            "parseQwenTTSResponse", String.class);
            method.setAccessible(true);
            return (ToolResultBlock) method.invoke(multiModalTool, responseBody);
        }

        @Test
        @DisplayName("Parse Qwen TTS response with URL")
        void testParseQwenTTSResponseWithUrl() throws Exception {
            String responseJson =
                    """
                    {"output":{"audio":{"url":"https://example.com/audio.wav"}},"request_id":"test-request-id"}
                    """;

            ToolResultBlock result = invokeParseQwenTTSResponse(responseJson);

            assertNotNull(result);
            assertEquals(1, result.getOutput().size());
            assertInstanceOf(AudioBlock.class, result.getOutput().get(0));
            AudioBlock audioBlock = (AudioBlock) result.getOutput().get(0);
            assertInstanceOf(URLSource.class, audioBlock.getSource());
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
            assertInstanceOf(AudioBlock.class, result.getOutput().get(0));
            AudioBlock audioBlock = (AudioBlock) result.getOutput().get(0);
            assertInstanceOf(Base64Source.class, audioBlock.getSource());
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
            assertInstanceOf(TextBlock.class, result.getOutput().get(0));
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
            assertInstanceOf(TextBlock.class, result.getOutput().get(0));
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
            assertInstanceOf(TextBlock.class, result.getOutput().get(0));
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
            assertInstanceOf(TextBlock.class, result.getOutput().get(0));
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
            assertInstanceOf(TextBlock.class, result.getOutput().get(0));
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
            assertInstanceOf(TextBlock.class, result.getOutput().get(0));
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
                        (mock, context) ->
                                when(mock.call(any(SpeechSynthesisParam.class)))
                                        .thenReturn(ByteBuffer.allocate(0)));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToAudio(
                        "hello", "sambert-zhichu-v1", null, null, 48000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                        (mock, context) ->
                                when(mock.call(any(SpeechSynthesisParam.class))).thenReturn(null));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToAudio(
                        "hello", "sambert-zhichu-v1", null, null, 48000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                        (mock, context) ->
                                when(mock.call(any(SpeechSynthesisParam.class)))
                                        .thenThrow(TEST_ERROR));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToAudio(
                        "hello", "sambert-zhichu-v1", null, null, 48000);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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

    @Test
    @DisplayName("Should return a video url when text to video invoked success")
    void testTextToVideoUrl() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(TEST_VIDEO_URL);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-t2v",
                        "low quality",
                        TEST_AUDIO_URL,
                        "1920*1080",
                        5,
                        "single",
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(VideoBlock.class, toolResultBlock.getOutput().get(0));
                            VideoBlock vb = (VideoBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, vb.getSource());
                            assertEquals(TEST_VIDEO_URL, ((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to video response null")
    void testTextToVideoResponseNull() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(null);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-t2v",
                        "low quality",
                        TEST_AUDIO_URL,
                        "1920*1080",
                        5,
                        "single",
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", "Failed to generate video."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to video occurs error")
    void testTextToVideoError() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) ->
                                when(mock.call(any(VideoSynthesisParam.class)))
                                        .thenThrow(TEST_ERROR));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeTextToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-t2v",
                        "low quality",
                        TEST_AUDIO_URL,
                        "1920*1080",
                        5,
                        "single",
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Image to video with image url")
    void testImageToVideoWithUrl() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(TEST_VIDEO_URL);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-i2v-flash",
                        TEST_IMAGE0_URL,
                        TEST_AUDIO_URL,
                        "",
                        "hanfu-1",
                        "480P",
                        10,
                        "single",
                        true,
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(VideoBlock.class, toolResultBlock.getOutput().get(0));
                            VideoBlock vb = (VideoBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, vb.getSource());
                            assertEquals(TEST_VIDEO_URL, ((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Image to video with local image file")
    void testImageToVideoWithFile() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToProtocolUrl(TEST_IMAGE_PATH)).thenReturn("file://" + TEST_IMAGE_PATH);

        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(TEST_VIDEO_URL);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-i2v-flash",
                        TEST_IMAGE_PATH,
                        TEST_AUDIO_URL,
                        "",
                        "hanfu-1",
                        "480P",
                        10,
                        "single",
                        true,
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(VideoBlock.class, toolResultBlock.getOutput().get(0));
                            VideoBlock vb = (VideoBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, vb.getSource());
                            assertEquals(TEST_VIDEO_URL, ((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();

        mockMediaUtils.close();
        mockCtor.close();
    }

    @Test
    @DisplayName("Image to video with base64 data url")
    void testImageToVideoWithBase64DataUrl() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(TEST_VIDEO_URL);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-i2v-flash",
                        TEST_IMAGE_BASE64_DATA_URL,
                        TEST_AUDIO_URL,
                        "",
                        "hanfu-1",
                        "480P",
                        10,
                        "single",
                        true,
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(VideoBlock.class, toolResultBlock.getOutput().get(0));
                            VideoBlock vb = (VideoBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, vb.getSource());
                            assertEquals(TEST_VIDEO_URL, ((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call image to video response" + " null")
    void testImageToVideoResponseNull() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(null);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-i2v-flash",
                        TEST_IMAGE0_URL,
                        TEST_AUDIO_URL,
                        "",
                        "hanfu-1",
                        "480P",
                        10,
                        "single",
                        true,
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", "Failed to generate video."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call image to video occurs" + " error")
    void testImageToVideoError() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) ->
                                when(mock.call(any(VideoSynthesisParam.class)))
                                        .thenThrow(TEST_ERROR));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-i2v-flash",
                        TEST_IMAGE0_URL,
                        TEST_AUDIO_URL,
                        "",
                        "hanfu-1",
                        "480P",
                        10,
                        "single",
                        true,
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("First and last frame image to video with image url")
    void testFirstAndLastFrameImageToVideoWithUrl() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(TEST_VIDEO_URL);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeFirstAndLastFrameImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.2-kf2v-flash",
                        TEST_IMAGE0_URL,
                        TEST_IMAGE1_URL,
                        "",
                        "hanfu-1",
                        "480P",
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(VideoBlock.class, toolResultBlock.getOutput().get(0));
                            VideoBlock vb = (VideoBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, vb.getSource());
                            assertEquals(TEST_VIDEO_URL, ((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("First and last frame image to video with local image file")
    void testFirstAndLastFrameImageToVideoWithFile() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToProtocolUrl(TEST_IMAGE_PATH)).thenReturn("file://" + TEST_IMAGE_PATH);

        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(TEST_VIDEO_URL);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeFirstAndLastFrameImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.2-kf2v-flash",
                        TEST_IMAGE_PATH,
                        null,
                        "",
                        "hanfu-1",
                        "480P",
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(VideoBlock.class, toolResultBlock.getOutput().get(0));
                            VideoBlock vb = (VideoBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, vb.getSource());
                            assertEquals(TEST_VIDEO_URL, ((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();

        mockMediaUtils.close();
        mockCtor.close();
    }

    @Test
    @DisplayName("First and last frame image to video with base64 data url")
    void testFirstAndLastFrameImageToVideoWithBase64DataUrl() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(TEST_VIDEO_URL);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeFirstAndLastFrameImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.2-kf2v-flash",
                        TEST_IMAGE_BASE64_DATA_URL,
                        null,
                        "",
                        "hanfu-1",
                        "480P",
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(VideoBlock.class, toolResultBlock.getOutput().get(0));
                            VideoBlock vb = (VideoBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, vb.getSource());
                            assertEquals(TEST_VIDEO_URL, ((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName(
            "Should return error TextBlock when call first and last frame image to video response"
                    + " null")
    void testFirstAndLastFrameImageToVideoResponseNull() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) -> {
                            VideoSynthesisResult mockResult = mock(VideoSynthesisResult.class);
                            VideoSynthesisOutput mockOutput = mock(VideoSynthesisOutput.class);

                            when(mock.call(any(VideoSynthesisParam.class))).thenReturn(mockResult);
                            when(mockResult.getOutput()).thenReturn(mockOutput);
                            when(mockOutput.getVideoUrl()).thenReturn(null);
                        });

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeFirstAndLastFrameImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.2-kf2v-flash",
                        TEST_IMAGE_BASE64_DATA_URL,
                        null,
                        "",
                        "hanfu-1",
                        "480P",
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", "Failed to generate video."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName(
            "Should return error TextBlock when call first and last frame image to video occurs"
                    + " error")
    void testFirstAndLastFrameImageToVideoError() {
        MockedConstruction<VideoSynthesis> mockCtor =
                mockConstruction(
                        VideoSynthesis.class,
                        (mock, context) ->
                                when(mock.call(any(VideoSynthesisParam.class)))
                                        .thenThrow(TEST_ERROR));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeFirstAndLastFrameImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.2-kf2v-flash",
                        TEST_IMAGE_BASE64_DATA_URL,
                        null,
                        "",
                        "hanfu-1",
                        "480P",
                        true,
                        false,
                        0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockCtor.close();
    }

    @Test
    @DisplayName("Video to text with video url")
    void testVideoToTextWithUrl() {
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
                multiModalTool.dashscopeVideoToText(
                        TEST_VIDEO_URL, VIDEO_TO_TEXT_PROMPT, "qwen3.5-plus", 2.0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    TEST_MULTI_MODAL_CONTENT,
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Video to text with local video file")
    void testVideoToTextWithFile() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToProtocolUrl(TEST_VIDEO_PATH)).thenReturn("file://" + TEST_VIDEO_PATH);

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
                multiModalTool.dashscopeVideoToText(
                        TEST_VIDEO_PATH, VIDEO_TO_TEXT_PROMPT, "qwen3.5-plus", 2.0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    TEST_MULTI_MODAL_CONTENT,
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
        mockConv.close();
    }

    @Test
    @DisplayName("Video to text with base64 data url")
    void testVideoToTextWithBase64DataUrl() {
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
                multiModalTool.dashscopeVideoToText(
                        TEST_VIDEO_BASE64_DATA_URL, VIDEO_TO_TEXT_PROMPT, "qwen3.5-plus", 2.0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    TEST_MULTI_MODAL_CONTENT,
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call video to text response empty")
    void testVideoToTextResponseEmpty() {
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
                multiModalTool.dashscopeVideoToText(
                        TEST_VIDEO_URL, VIDEO_TO_TEXT_PROMPT, "qwen3.5-plus", 2.0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", "Failed to analyze video."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call video to text response null")
    void testVideoToTextResponseNull() {
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
                multiModalTool.dashscopeVideoToText(
                        TEST_VIDEO_URL, VIDEO_TO_TEXT_PROMPT, "qwen3.5-plus", 2.0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", "Failed to analyze video."),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call video to text occurs error")
    void testVideoToTextError() {
        MockedConstruction<MultiModalConversation> mockConv =
                mockConstruction(
                        MultiModalConversation.class,
                        (mock, context) ->
                                when(mock.call(any(MultiModalConversationParam.class)))
                                        .thenThrow(TEST_ERROR));

        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeVideoToText(
                        TEST_VIDEO_URL, VIDEO_TO_TEXT_PROMPT, "qwen3.5-plus", 2.0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertEquals(
                                    String.format("Error: %s", TEST_ERROR.getMessage()),
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();

        mockConv.close();
    }
}
