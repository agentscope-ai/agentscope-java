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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.e2e.E2ETestCondition;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.io.IOException;
import java.nio.file.Paths;
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
    private static final String TEXT_TO_VIDEO_PROMPT = "A small dog is running in moonlight.";
    private static final String IMAGE_TO_VIDEO_PROMPT = "A tiger is running in moonlight.";
    private static final String FIRST_AND_LAST_FRAME_IMAGE_TO_VIDEO_PROMPT =
            "A black kitten looks curiously into the sky.";
    private static final String VIDEO_TO_TEXT_PROMPT = "Describe the video.";
    private static final String TEST_IMAGE_URL =
            "https://dashscope.oss-cn-beijing.aliyuncs.com/images/tiger.png";
    private static final String TEST_IMAGE_PATH =
            Paths.get("src", "test", "resources", "dog.png").toString();
    private static final String TEST_AUDIO_URL =
            "https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_male2.wav";
    private static final String TEST_AUDIO_PATH =
            Paths.get("src", "test", "resources", "hello_world_male_16k_16bit_mono.wav").toString();
    private static final String TEST_VIDEO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4";
    private static final String TEST_VIDEO_PATH =
            Paths.get("src", "test", "resources", "test_video.mp4").toString();
    private static final String TEST_FIRST_FRAME_URL =
            "https://wanx.alicdn.com/material/20250318/first_frame.png";
    private static final String TEST_LAST_FRAME_URL =
            "https://wanx.alicdn.com/material/20250318/last_frame.png";

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
                            assertInstanceOf(ImageBlock.class, toolResultBlock.getOutput().get(0));
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, imageBlock.getSource());
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
                            assertInstanceOf(ImageBlock.class, toolResultBlock.getOutput().get(0));
                            ImageBlock imageBlock = (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(Base64Source.class, imageBlock.getSource());
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
                            assertInstanceOf(ImageBlock.class, toolResultBlock.getOutput().get(0));
                            assertInstanceOf(ImageBlock.class, toolResultBlock.getOutput().get(1));
                            ImageBlock image0Block =
                                    (ImageBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(URLSource.class, image0Block.getSource());
                            assertNotNull(((URLSource) image0Block.getSource()).getUrl());
                            ImageBlock image1Block =
                                    (ImageBlock) toolResultBlock.getOutput().get(1);
                            assertInstanceOf(URLSource.class, image1Block.getSource());
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Image to text with base64 data url")
    void testImageToTextWithBase64DataUrl() throws IOException {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToText(
                        List.of(MediaUtils.urlToBase64DataUrl(TEST_IMAGE_URL)),
                        IMAGE_TO_TEXT_PROMPT,
                        "qwen3-vl-plus");

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(AudioBlock.class, toolResultBlock.getOutput().get(0));
                            AudioBlock audioBlock = (AudioBlock) toolResultBlock.getOutput().get(0);
                            assertInstanceOf(Base64Source.class, audioBlock.getSource());
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
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
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Text to video response url")
    void testTextToVideo() {
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
                            assertNotNull(((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Image to video with image url")
    void testImageToVideoWithUrl() {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToVideo(
                        IMAGE_TO_VIDEO_PROMPT,
                        "wan2.6-i2v-flash",
                        TEST_IMAGE_URL,
                        TEST_AUDIO_URL,
                        "low quality",
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
                            assertNotNull(((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Image to video with local image file")
    void testImageToVideoWithFile() {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-i2v-flash",
                        TEST_IMAGE_PATH,
                        TEST_AUDIO_URL,
                        "low quality",
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
                            assertNotNull(((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Image to video with base64 data url")
    void testImageToVideoWithBase64DataUrl() throws IOException {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.6-i2v-flash",
                        MediaUtils.urlToBase64DataUrl(TEST_IMAGE_PATH),
                        TEST_AUDIO_URL,
                        "low quality",
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
                            assertNotNull(((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("First and last frame image to video with image url")
    void testFirstAndLastFrameImageToVideoWithUrl() {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeFirstAndLastFrameImageToVideo(
                        FIRST_AND_LAST_FRAME_IMAGE_TO_VIDEO_PROMPT,
                        "wan2.2-kf2v-flash",
                        TEST_FIRST_FRAME_URL,
                        TEST_LAST_FRAME_URL,
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
                            assertNotNull(((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("First and last frame image to video with local image file")
    void testFirstAndLastFrameImageToVideoWithFile() throws IOException {
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
                            assertNotNull(((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("First and last frame image to video with base64 data url")
    void testFirstAndLastFrameImageToVideoWithBase64DataUrl() throws IOException {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeFirstAndLastFrameImageToVideo(
                        TEXT_TO_VIDEO_PROMPT,
                        "wan2.2-kf2v-flash",
                        MediaUtils.urlToBase64DataUrl(TEST_FIRST_FRAME_URL),
                        MediaUtils.urlToBase64DataUrl(TEST_LAST_FRAME_URL),
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
                            assertNotNull(((URLSource) vb.getSource()).getUrl());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Video to text with video url")
    void testVideoToTextWithUrl() {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeVideoToText(
                        TEST_VIDEO_URL, VIDEO_TO_TEXT_PROMPT, "qwen3.5-plus", 2.0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Video to text with local video file")
    void testVideoToTextWithFile() {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeVideoToText(
                        TEST_VIDEO_PATH, VIDEO_TO_TEXT_PROMPT, "qwen3.5-plus", 2.0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Video to text with base64 data url")
    void testVideoToTextWithBase64DataUrl() throws IOException {
        Mono<ToolResultBlock> result =
                multiModalTool.dashscopeVideoToText(
                        MediaUtils.urlToBase64DataUrl(TEST_VIDEO_URL),
                        VIDEO_TO_TEXT_PROMPT,
                        "qwen3.5-plus",
                        2.0);

        StepVerifier.create(result)
                .assertNext(
                        toolResultBlock -> {
                            assertNotNull(toolResultBlock);
                            assertEquals(1, toolResultBlock.getOutput().size());
                            assertInstanceOf(TextBlock.class, toolResultBlock.getOutput().get(0));
                            assertNotNull(
                                    ((TextBlock) toolResultBlock.getOutput().get(0)).getText());
                        })
                .verifyComplete();
    }
}
