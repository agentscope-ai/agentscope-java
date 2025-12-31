/*
 * Copyright 2024-2025 the original author or authors.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.http.HttpResponse;
import com.openai.models.audio.speech.SpeechCreateParams;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.images.Image;
import com.openai.models.images.ImageCreateVariationParams;
import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import com.openai.services.blocking.AudioService;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.ImageService;
import com.openai.services.blocking.audio.SpeechService;
import com.openai.services.blocking.audio.TranscriptionService;
import com.openai.services.blocking.chat.ChatCompletionService;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link OpenAIMultiModalTool}.
 *
 * <p>Tests text to image(s), edit image, create image variation, images to text, text to audio, and audio to text.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@ExtendWith(MockitoExtension.class)
class OpenAIMultiModalToolTest {

    private static final String TEST_API_KEY = "test_api_key";
    private static final String TEXT_TO_IMAGE_PROMPT = "A small dog.";
    private static final String EDIT_IMAGE_PROMPT = "Change the background color to red.";
    private static final String IMAGE_TO_TEXT_PROMPT = "Describe the image.";
    private static final String TEXT_TO_AUDIO_PROMPT = "Hello world.";
    private static final String TEST_IMAGE0_URL = "https://example.com/image0.png";
    private static final String TEST_IMAGE1_URL = "https://example.com/image1.png";
    private static final String TEST_AUDIO_URL = "https://example.com/audio.wav";
    private static final String TEST_BASE64_DATA_URL = "data:image/png;base64,aGVsbG8=";
    // base64 of "hello"
    private static final String TEST_BASE64_DATA = "aGVsbG8=";
    private static final String TEST_MULTI_MODAL_CONTENT = "This is a small dog.";
    private static final RuntimeException TEST_ERROR = new RuntimeException("Test error");

    @InjectMocks
    private OpenAIMultiModalTool multiModalTool = new OpenAIMultiModalTool(TEST_API_KEY);

    @Mock private OpenAIClient mockClient;

    @Test
    @DisplayName("Text to image with url mode")
    void testTextToImageUrlMode() {
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);
        Image mockImage = mock(Image.class);

        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.generate(any(ImageGenerateParams.class)))
                .thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.of(List.of(mockImage)));
        when(mockImage.url()).thenReturn(Optional.of(TEST_IMAGE0_URL));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "gpt-4o", 1, "256x256", "auto", "vivid", "url");

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
    }

    @Test
    @DisplayName("Text to image with base64 mode")
    void testTextToImageBase64Mode() {
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);
        Image mockImage = mock(Image.class);

        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.generate(any(ImageGenerateParams.class)))
                .thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.of(List.of(mockImage)));
        when(mockImage.b64Json()).thenReturn(Optional.of(TEST_BASE64_DATA));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "gpt-4o", 1, "256x256", "auto", "vivid", "b64_json");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock.getSource() instanceof Base64Source);
                            assertEquals(
                                    TEST_BASE64_DATA,
                                    ((Base64Source) imageBlock.getSource()).getData());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Text to image response multiple urls")
    void testTextToImageMultiUrls() {
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);
        Image mockImage0 = mock(Image.class);
        Image mockImage1 = mock(Image.class);

        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.generate(any(ImageGenerateParams.class)))
                .thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.of(List.of(mockImage0, mockImage1)));
        when(mockImage0.url()).thenReturn(Optional.of(TEST_IMAGE0_URL));
        when(mockImage1.url()).thenReturn(Optional.of(TEST_IMAGE1_URL));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "gpt-4o", 2, "256x256", "auto", "vivid", "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(2, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock0 =
                                    (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock0.getSource() instanceof URLSource);
                            assertEquals(
                                    TEST_IMAGE0_URL,
                                    ((URLSource) imageBlock0.getSource()).getUrl());
                            assertTrue(toolResultBlock.getOutput().get(1) instanceof ImageBlock);
                            ImageBlock imageBlock1 =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertTrue(imageBlock1.getSource() instanceof URLSource);
                            assertEquals(
                                    TEST_IMAGE1_URL,
                                    ((URLSource) imageBlock1.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to image response empty")
    void testTextToImageResponseEmpty() {
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);

        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.generate(any(ImageGenerateParams.class)))
                .thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.empty());

        Mono<ToolResultBlock> result =
                multiModalTool.openaiTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "gpt-4o", 1, "256x256", "auto", "vivid", "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: Failed to generate images.", textBlock.getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to image occurs error")
    void testTextToImageError() {
        when(mockClient.images()).thenThrow(TEST_ERROR);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "gpt-4o", 1, "256x256", "auto", "vivid", "b64_json");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: " + TEST_ERROR.getMessage(), textBlock.getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Edit image with url mode")
    void testEditImageUrlMode() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);
        Image mockImage = mock(Image.class);

        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.edit(any(ImageEditParams.class))).thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.of(List.of(mockImage)));
        when(mockImage.url()).thenReturn(Optional.of(TEST_IMAGE0_URL));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiEditImage(
                        TEST_IMAGE0_URL,
                        EDIT_IMAGE_PROMPT,
                        "dall-e-2",
                        TEST_IMAGE1_URL,
                        1,
                        "256x256",
                        "url");

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

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Edit image with base64 mode")
    void testEditImageBase64Mode() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);
        Image mockImage = mock(Image.class);

        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.edit(any(ImageEditParams.class))).thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.of(List.of(mockImage)));
        when(mockImage.b64Json()).thenReturn(Optional.of(TEST_BASE64_DATA));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiEditImage(
                        TEST_IMAGE0_URL,
                        EDIT_IMAGE_PROMPT,
                        "dall-e-2",
                        TEST_IMAGE1_URL,
                        1,
                        "256x256",
                        "b64_json");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock.getSource() instanceof Base64Source);
                            assertEquals(
                                    TEST_BASE64_DATA,
                                    ((Base64Source) imageBlock.getSource()).getData());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Edit image response multiple urls")
    void testEditImageResponseMultiUrls() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);
        Image mockImage0 = mock(Image.class);
        Image mockImage1 = mock(Image.class);

        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.edit(any(ImageEditParams.class))).thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.of(List.of(mockImage0, mockImage1)));
        when(mockImage0.url()).thenReturn(Optional.of(TEST_IMAGE0_URL));
        when(mockImage1.url()).thenReturn(Optional.of(TEST_IMAGE1_URL));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiEditImage(
                        TEST_IMAGE0_URL,
                        EDIT_IMAGE_PROMPT,
                        "dall-e-2",
                        TEST_IMAGE1_URL,
                        2,
                        "256x256",
                        "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(2, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock0 =
                                    (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock0.getSource() instanceof URLSource);
                            assertEquals(
                                    TEST_IMAGE0_URL,
                                    ((URLSource) imageBlock0.getSource()).getUrl());
                            assertTrue(toolResultBlock.getOutput().get(1) instanceof ImageBlock);
                            ImageBlock imageBlock1 =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertTrue(imageBlock1.getSource() instanceof URLSource);
                            assertEquals(
                                    TEST_IMAGE1_URL,
                                    ((URLSource) imageBlock1.getSource()).getUrl());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call edit image response empty")
    void testEditImageResponseEmpty() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);

        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.edit(any(ImageEditParams.class))).thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.empty());

        Mono<ToolResultBlock> result =
                multiModalTool.openaiEditImage(
                        TEST_IMAGE0_URL,
                        EDIT_IMAGE_PROMPT,
                        "dall-e-2",
                        TEST_IMAGE1_URL,
                        1,
                        "256x256",
                        "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: Failed to edit image.", textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call edit image occurs error")
    void testEditImageError() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenThrow(TEST_ERROR);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiEditImage(
                        TEST_IMAGE0_URL,
                        EDIT_IMAGE_PROMPT,
                        "dall-e-2",
                        TEST_IMAGE1_URL,
                        1,
                        "256x256",
                        "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: " + TEST_ERROR.getMessage(), textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Create image variation with url mode")
    void testCreateImageVariationUrlMode() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);
        Image mockImage = mock(Image.class);

        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.createVariation(any(ImageCreateVariationParams.class)))
                .thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.of(List.of(mockImage)));
        when(mockImage.url()).thenReturn(Optional.of(TEST_IMAGE0_URL));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiCreateImageVariation(
                        TEST_IMAGE0_URL, "dall-e-2", 1, "256x256", "url");

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

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Create image variation with base64 mode")
    void testCreateImageVariationBase64Mode() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);
        Image mockImage = mock(Image.class);

        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.createVariation(any(ImageCreateVariationParams.class)))
                .thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.of(List.of(mockImage)));
        when(mockImage.b64Json()).thenReturn(Optional.of(TEST_BASE64_DATA_URL));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiCreateImageVariation(
                        TEST_IMAGE0_URL, "dall-e-2", 1, "256x256", "b64_json");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock.getSource() instanceof Base64Source);
                            assertEquals(
                                    TEST_BASE64_DATA_URL,
                                    ((Base64Source) imageBlock.getSource()).getData());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Create image variation response multiple urls")
    void testCreateImageVariationResponseMultiUrls() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);
        Image mockImage0 = mock(Image.class);
        Image mockImage1 = mock(Image.class);

        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.createVariation(any(ImageCreateVariationParams.class)))
                .thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.of(List.of(mockImage0, mockImage1)));
        when(mockImage0.url()).thenReturn(Optional.of(TEST_IMAGE0_URL));
        when(mockImage1.url()).thenReturn(Optional.of(TEST_IMAGE1_URL));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiCreateImageVariation(
                        TEST_IMAGE0_URL, "dall-e-2", 2, "256x256", "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(2, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock0 =
                                    (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock0.getSource() instanceof URLSource);
                            assertEquals(
                                    TEST_IMAGE0_URL,
                                    ((URLSource) imageBlock0.getSource()).getUrl());
                            assertTrue(toolResultBlock.getOutput().get(1) instanceof ImageBlock);
                            ImageBlock imageBlock1 =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertTrue(imageBlock1.getSource() instanceof URLSource);
                            assertEquals(
                                    TEST_IMAGE1_URL,
                                    ((URLSource) imageBlock1.getSource()).getUrl());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when create image variation response is empty")
    void testCreateImageVariationResponseEmpty() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ImageService mockImageService = mock(ImageService.class);
        ImagesResponse mockImagesResponse = mock(ImagesResponse.class);

        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenReturn(mockImageService);
        when(mockImageService.createVariation(any(ImageCreateVariationParams.class)))
                .thenReturn(mockImagesResponse);
        when(mockImagesResponse.data()).thenReturn(Optional.empty());

        Mono<ToolResultBlock> result =
                multiModalTool.openaiCreateImageVariation(
                        TEST_IMAGE0_URL, "dall-e-2", 1, "256x256", "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals(
                                    "Error: Failed to create image variation.",
                                    textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when create image variation occurs error")
    void testCreateImageVariationError() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToRgbaImageInputStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(mockClient.images()).thenThrow(TEST_ERROR);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiCreateImageVariation(
                        TEST_IMAGE0_URL, "dall-e-2", 1, "256x256", "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: " + TEST_ERROR.getMessage(), textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Image to text with web url")
    void testImageToTextWithUrl() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ChatService mockChatService = mock(ChatService.class);
        ChatCompletionService mockChatCompletionService = mock(ChatCompletionService.class);
        ChatCompletion mockChatCompletion = mock(ChatCompletion.class);
        ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
        ChatCompletionMessage mockChatCompletionMessage = mock(ChatCompletionMessage.class);

        when(MediaUtils.urlToBase64DataUrl(TEST_IMAGE0_URL)).thenReturn(TEST_BASE64_DATA_URL);
        when(mockClient.chat()).thenReturn(mockChatService);
        when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(mockChatCompletion);
        when(mockChatCompletion.choices()).thenReturn(List.of(mockChoice));
        when(mockChoice.message()).thenReturn(mockChatCompletionMessage);
        when(mockChatCompletionMessage.content()).thenReturn(Optional.of(TEST_MULTI_MODAL_CONTENT));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiImageToText(
                        List.of(TEST_IMAGE0_URL), IMAGE_TO_TEXT_PROMPT, "gpt-4o");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals(TEST_MULTI_MODAL_CONTENT, textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Image to text with multiple web urls")
    void testImageToTextWithMultiUrls() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ChatService mockChatService = mock(ChatService.class);
        ChatCompletionService mockChatCompletionService = mock(ChatCompletionService.class);
        ChatCompletion mockChatCompletion = mock(ChatCompletion.class);
        List<ChatCompletion.Choice> choices =
                List.of(
                        ChatCompletion.Choice.builder()
                                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                                .message(
                                        ChatCompletionMessage.builder()
                                                .content(TEST_MULTI_MODAL_CONTENT)
                                                .refusal((String) null)
                                                .build())
                                .index(0)
                                .logprobs((ChatCompletion.Choice.Logprobs) null)
                                .build());

        when(MediaUtils.urlToBase64DataUrl(anyString())).thenReturn(TEST_BASE64_DATA_URL);
        when(mockClient.chat()).thenReturn(mockChatService);
        when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(mockChatCompletion);
        when(mockChatCompletion.choices()).thenReturn(choices);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiImageToText(
                        List.of(TEST_IMAGE0_URL, TEST_IMAGE1_URL), IMAGE_TO_TEXT_PROMPT, "gpt-4o");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals(TEST_MULTI_MODAL_CONTENT, textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call image to text response empty")
    void testImageToTextResponseEmpty() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        ChatService mockChatService = mock(ChatService.class);
        ChatCompletionService mockChatCompletionService = mock(ChatCompletionService.class);
        ChatCompletion mockChatCompletion = mock(ChatCompletion.class);

        when(MediaUtils.urlToBase64DataUrl(TEST_IMAGE0_URL)).thenReturn(TEST_BASE64_DATA_URL);
        when(mockClient.chat()).thenReturn(mockChatService);
        when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(mockChatCompletion);
        when(mockChatCompletion.choices()).thenReturn(List.of());

        Mono<ToolResultBlock> result =
                multiModalTool.openaiImageToText(
                        List.of(TEST_IMAGE0_URL), IMAGE_TO_TEXT_PROMPT, "gpt-4o");
        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: Failed to generate text.", textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call image to text occurs error")
    void testImageToTextError() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);

        when(MediaUtils.urlToBase64DataUrl(TEST_IMAGE0_URL)).thenReturn(TEST_BASE64_DATA_URL);
        when(mockClient.chat()).thenThrow(TEST_ERROR);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiImageToText(
                        List.of(TEST_IMAGE0_URL), IMAGE_TO_TEXT_PROMPT, "gpt-4o");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: " + TEST_ERROR.getMessage(), textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Text to audio with mp3 format")
    void testTextToAudioWithMp3Format() {
        AudioService mockAudioService = mock(AudioService.class);
        SpeechService mockSpeechService = mock(SpeechService.class);
        HttpResponse mockHttpResponse = mock(HttpResponse.class);

        when(mockClient.audio()).thenReturn(mockAudioService);
        when(mockAudioService.speech()).thenReturn(mockSpeechService);
        when(mockSpeechService.create(any(SpeechCreateParams.class))).thenReturn(mockHttpResponse);
        when(mockHttpResponse.body())
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        Mono<ToolResultBlock> result =
                multiModalTool.openaiTextToAudio(
                        TEXT_TO_AUDIO_PROMPT, "tts-1", "alloy", 1.0f, "mp3");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof AudioBlock);
                            AudioBlock audioBlock = (AudioBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(audioBlock.getSource() instanceof Base64Source);
                            Base64Source base64Source = (Base64Source) audioBlock.getSource();
                            assertEquals(TEST_BASE64_DATA, base64Source.getData());
                            assertEquals("audio/mp3", base64Source.getMediaType());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to audio response empty")
    void testTextToAudioResponseEmpty() {
        AudioService mockAudioService = mock(AudioService.class);
        SpeechService mockSpeechService = mock(SpeechService.class);
        HttpResponse mockHttpResponse = mock(HttpResponse.class);

        when(mockClient.audio()).thenReturn(mockAudioService);
        when(mockAudioService.speech()).thenReturn(mockSpeechService);
        when(mockSpeechService.create(any(SpeechCreateParams.class))).thenReturn(mockHttpResponse);
        when(mockHttpResponse.body()).thenReturn(null);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiTextToAudio(
                        TEXT_TO_AUDIO_PROMPT, "tts-1", "alloy", 1.0f, "mp3");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: Failed to generate audio.", textBlock.getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error TextBlock when call text to audio occurs error")
    void testTextToAudioError() {
        when(mockClient.audio()).thenThrow(TEST_ERROR);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiTextToAudio(
                        TEXT_TO_AUDIO_PROMPT, "tts-1", "alloy", 1.0f, "mp3");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: " + TEST_ERROR.getMessage(), textBlock.getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Audio to text with audio url")
    void testAudioToTextWithUrl() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToInputStream(TEST_AUDIO_URL))
                .thenReturn(new ByteArrayInputStream(new byte[0]));

        AudioService mockAudioService = mock(AudioService.class);
        TranscriptionService mockTranscriptionService = mock(TranscriptionService.class);
        TranscriptionCreateResponse mockTranscriptionCreateResponse =
                mock(TranscriptionCreateResponse.class);
        Transcription mockTranscription = mock(Transcription.class);

        when(mockClient.audio()).thenReturn(mockAudioService);
        when(mockAudioService.transcriptions()).thenReturn(mockTranscriptionService);
        when(mockTranscriptionService.create(any(TranscriptionCreateParams.class)))
                .thenReturn(mockTranscriptionCreateResponse);
        when(mockTranscriptionCreateResponse.asTranscription()).thenReturn(mockTranscription);
        when(mockTranscription.text()).thenReturn(TEST_MULTI_MODAL_CONTENT);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiAudioToText(TEST_AUDIO_URL, "whisper-1", "en", 0.2f);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals(TEST_MULTI_MODAL_CONTENT, textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call audio to text response empty")
    void testAudioToTextResponseEmpty() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToInputStream(TEST_AUDIO_URL))
                .thenReturn(new ByteArrayInputStream(new byte[0]));

        AudioService mockAudioService = mock(AudioService.class);
        TranscriptionService mockTranscriptionService = mock(TranscriptionService.class);
        TranscriptionCreateResponse mockTranscriptionCreateResponse =
                mock(TranscriptionCreateResponse.class);
        Transcription mockTranscription = mock(Transcription.class);

        when(mockClient.audio()).thenReturn(mockAudioService);
        when(mockAudioService.transcriptions()).thenReturn(mockTranscriptionService);
        when(mockTranscriptionService.create(any(TranscriptionCreateParams.class)))
                .thenReturn(mockTranscriptionCreateResponse);
        when(mockTranscriptionCreateResponse.asTranscription()).thenReturn(mockTranscription);
        when(mockTranscription.text()).thenReturn(null);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiAudioToText(TEST_AUDIO_URL, "whisper-1", "en", 0.2f);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: Failed to transcribe audio.", textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }

    @Test
    @DisplayName("Should return error TextBlock when call audio to text occurs error")
    void testAudioToTextError() throws IOException {
        MockedStatic<MediaUtils> mockMediaUtils = mockStatic(MediaUtils.class);
        when(MediaUtils.urlToInputStream(TEST_AUDIO_URL))
                .thenReturn(new ByteArrayInputStream(new byte[0]));

        when(mockClient.audio()).thenThrow(TEST_ERROR);

        Mono<ToolResultBlock> result =
                multiModalTool.openaiAudioToText(TEST_AUDIO_URL, "whisper-1", "en", 0.2f);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            TextBlock textBlock = (TextBlock) toolResultBlock.getOutput().get(0);
                            assertEquals("Error: " + TEST_ERROR.getMessage(), textBlock.getText());
                        })
                .verifyComplete();

        mockMediaUtils.close();
    }
}
