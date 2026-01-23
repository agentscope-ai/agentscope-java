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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.e2e.E2ETestCondition;
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
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * E2E tests for {@link DashScopeMultiModalTool}.
 *
 * <p>Tests text to image(s), image(s) to text, text to audio, and audio to text.
 *
 * <p>Tagged as "e2e" - these tests make real API calls and may incur costs.
 */
@Tag("e2e")
@ExtendWith(E2ETestCondition.class)
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class DashScopeMultiModalToolE2ETest {

    private static final String TEXT_TO_IMAGE_PROMPT = "A small dog.";
    private static final String IMAGE_TO_TEXT_PROMPT = "Describe the image.";
    private static final String TEST_IMAGE_URL =
            "https://dashscope.oss-cn-beijing.aliyuncs.com/images/tiger.png";
    private static final String TEST_IMAGE_PATH = "src/test/resources/dog.png";
    private static final String TEST_AUDIO_URL =
            "https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male2.wav";
    private static final String TEST_AUDIO_PATH =
            "src/test/resources/hello_world_male_16k_16bit_mono.wav";
    private DashScopeMultiModalTool multiModalTool;

    @BeforeEach
    void setUp() {
        multiModalTool = new DashScopeMultiModalTool(System.getenv("DASHSCOPE_API_KEY"));
    }

    @Test
    @DisplayName("Text to image response with url mode")
    void testTextToImageUrlMode() {
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
                            assertNotNull(((URLSource) imageBlock.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Text to image with base64 mode")
    void testTextToImageBase64Mode() {
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
                            assertNotNull(((Base64Source) imageBlock.getSource()).getMediaType());
                            assertNotNull(((Base64Source) imageBlock.getSource()).getData());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Text to image response multiple urls")
    void testTextToImageResponseMultiUrls() {
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
                            assertNotNull(((URLSource) image0Block.getSource()).getUrl());
                            ImageBlock image1Block =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertTrue(image1Block.getSource() instanceof URLSource);
                            assertNotNull(((URLSource) image1Block.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Image to text with web url")
    void testImageToTextWithUrl() {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE_URL), IMAGE_TO_TEXT_PROMPT, "qwen3-vl-plus");

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
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE_PATH), IMAGE_TO_TEXT_PROMPT, "qwen3-vl-plus");

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
                multiModalTool.dashscopeImageToText(
                        List.of(TEST_IMAGE_URL, TEST_IMAGE_PATH),
                        "Describe these two images",
                        "qwen3-vl-plus");

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
                            assertNotNull(((Base64Source) audioBlock.getSource()).getData());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Audio to text with url")
    void testAudioToTextWithUrl() {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeAudioToText(
                        TEST_AUDIO_URL, "paraformer-realtime-v2", 16000);

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
                multiModalTool.dashscopeAudioToText(
                        TEST_AUDIO_PATH, "paraformer-realtime-v2", 16000);

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
