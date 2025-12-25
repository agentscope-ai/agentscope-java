/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.tool.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * E2E tests for {@link OpenAIMultiModalTool}.
 *
 * <p>Tests text to image(s), edit image, create image variation, images to text, text to audio, and audio to text.
 *
 * <p>Tagged as "e2e" - these tests make real API calls and may incur costs.
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class OpenAIMultiModalToolE2ETest {

    private static final String TEXT_TO_IMAGE_PROMPT = "A small dog.";
    private static final String EDIT_IMAGE_PROMPT = "Change the background color to red.";
    private static final String IMAGE_TO_TEXT_PROMPT = "Describe the image.";
    private static final String TEXT_TO_AUDIO_PROMPT = "Hello world.";
    private static final String TEST_IMAGE_URL = "https://cdn.openai.com/API/docs/images/otter.png";
    private static final String TEST_IMAGE_PATH = "src/test/resources/dog.png";
    private static final String TEST_AUDIO_URL = "https://cdn.openai.com/API/docs/audio/alloy.wav";
    private static final String TEST_AUDIO_PATH =
            "src/test/resources/hello_world_male_16k_16bit_mono.wav";
    private OpenAIMultiModalTool multiModalTool;

    @BeforeEach
    void setUp() {
        multiModalTool = new OpenAIMultiModalTool(System.getenv("OPENAI_API_KEY"));
    }

    @Test
    @DisplayName("Text to image with url mode")
    void testTextToImageUrlMode() {
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
                            assertNotNull(((URLSource) imageBlock.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Text to image with base64 mode")
    void testTextToImageBase64Mode() {
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
                            assertNotNull(((Base64Source) imageBlock.getSource()).getMediaType());
                            assertNotNull(((Base64Source) imageBlock.getSource()).getData());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Text to image response multiple urls")
    void testTextToImageMultiUrls() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiTextToImage(
                        TEXT_TO_IMAGE_PROMPT, "gpt-4o", 2, "256x256", "auto", "vivid", "url");

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
                            assertNotNull(((URLSource) image0Block.getSource()).getUrl());
                            ImageBlock image1Block =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertTrue(image1Block.getSource() instanceof URLSource);
                            assertNotNull(((URLSource) image1Block.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Edit image with url mode")
    void testEditImageUrlMode() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiEditImage(
                        TEST_IMAGE_URL, EDIT_IMAGE_PROMPT, "dall-e-2", null, 1, "256x256", "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock.getSource() instanceof URLSource);
                            assertNotNull(((URLSource) imageBlock.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Edit image with base64 mode")
    void testEditImageBase64Mode() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiEditImage(
                        TEST_IMAGE_URL,
                        EDIT_IMAGE_PROMPT,
                        "dall-e-2",
                        null,
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
                            assertNotNull(((Base64Source) imageBlock.getSource()).getData());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Edit image response multiple urls")
    void testEditImageResponseMultiUrls() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiEditImage(
                        TEST_IMAGE_URL, EDIT_IMAGE_PROMPT, "dall-e-2", null, 2, "256x256", "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(2, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock0 =
                                    (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock0.getSource() instanceof URLSource);
                            assertNotNull(((URLSource) imageBlock0.getSource()).getUrl());
                            assertTrue(toolResultBlock.getOutput().get(1) instanceof ImageBlock);
                            ImageBlock imageBlock1 =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertTrue(imageBlock1.getSource() instanceof URLSource);
                            assertNotNull(((URLSource) imageBlock1.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Create image variation with url mode")
    void testCreateImageVariationUrlMode() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiCreateImageVariation(
                        TEST_IMAGE_URL, "dall-e-2", 1, "256x256", "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock.getSource() instanceof URLSource);
                            assertNotNull(((URLSource) imageBlock.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Create image variation with base64 mode")
    void testCreateImageVariationBase64Mode() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiCreateImageVariation(
                        TEST_IMAGE_URL, "dall-e-2", 1, "256x256", "b64_json");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock.getSource() instanceof Base64Source);
                            assertNotNull(((Base64Source) imageBlock.getSource()).getData());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Create image variation response multiple urls")
    void testCreateImageVariationResponseMultiUrls() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiCreateImageVariation(
                        TEST_IMAGE_URL, "dall-e-2", 2, "256x256", "url");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(2, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof ImageBlock);
                            ImageBlock imageBlock0 =
                                    (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertTrue(imageBlock0.getSource() instanceof URLSource);
                            assertNotNull(((URLSource) imageBlock0.getSource()).getUrl());
                            assertTrue(toolResultBlock.getOutput().get(1) instanceof ImageBlock);
                            ImageBlock imageBlock1 =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertTrue(imageBlock1.getSource() instanceof URLSource);
                            assertNotNull(((URLSource) imageBlock1.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Image to text with web url")
    void testImageToTextWithUrl() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiImageToText(
                        List.of(TEST_IMAGE_URL), IMAGE_TO_TEXT_PROMPT, "gpt-4o");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Image to text with local file")
    void testImageToTextWithFile() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiImageToText(
                        List.of(TEST_IMAGE_PATH), IMAGE_TO_TEXT_PROMPT, "gpt-4o");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Image to text with web url and local file")
    void testImageToTextWithUrlAndFile() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiImageToText(
                        List.of(TEST_IMAGE_URL, TEST_IMAGE_PATH),
                        "Describe these two images",
                        "gpt-4o");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Text to audio")
    void testTextToAudio() {
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
                            assertNotNull(((Base64Source) audioBlock.getSource()).getData());
                            assertEquals(
                                    "audio/mp3",
                                    ((Base64Source) audioBlock.getSource()).getMediaType());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Audio to text with url")
    void testAudioToTextWithUrl() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiAudioToText(TEST_AUDIO_URL, "whisper-1", "en", 0.2f);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Audio to text with local file")
    void testAudioToTextWithFile() {
        Mono<ToolResultBlock> result =
                multiModalTool.openaiAudioToText(TEST_AUDIO_PATH, "whisper-1", "en", 0.2f);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertTrue(toolResultBlock.getOutput().get(0) instanceof TextBlock);
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }
}
