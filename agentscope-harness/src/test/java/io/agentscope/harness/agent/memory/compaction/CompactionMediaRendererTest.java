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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactionMediaRendererTest {

    private final CompactionMediaRenderer renderer = new CompactionMediaRenderer();

    @Test
    void textBlockRendersText() {
        String rendered =
                renderer.renderBlock(
                        TextBlock.builder().text("hello world").build(),
                        new CompactionMediaRenderer.RenderContext("m1", 0));
        assertTrue(rendered.contains("hello world"));
    }

    @Test
    void imageBlockBase64RendersPlaceholderWithoutRawData() {
        String data =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
        ImageBlock block =
                ImageBlock.builder()
                        .source(Base64Source.builder().mediaType("image/png").data(data).build())
                        .build();
        String rendered =
                renderer.renderBlock(block, new CompactionMediaRenderer.RenderContext("m1", 0));
        assertTrue(rendered.startsWith("[attachment"));
        assertTrue(rendered.contains("type=image/png"));
        assertTrue(rendered.contains("disposition=described"));
        assertTrue(rendered.contains("kind=image"));
        assertTrue(rendered.contains("sha256="));
        assertTrue(rendered.contains("bytes="));
        assertFalse(rendered.contains(data));
    }

    @Test
    void imageBlockUrlRendersPlaceholderWithoutOriginalUrl() {
        String url = "https://example.com/signed?token=secret";
        ImageBlock block =
                ImageBlock.builder()
                        .source(URLSource.builder().url(url).mimeType("image/jpeg").build())
                        .build();
        String rendered =
                renderer.renderBlock(block, new CompactionMediaRenderer.RenderContext("m1", 0));
        assertTrue(rendered.contains("type=image/jpeg"));
        assertTrue(rendered.contains("protocol=https"));
        assertTrue(rendered.contains("sha256="));
        assertFalse(rendered.contains(url));
        assertFalse(rendered.contains("secret"));
    }

    @Test
    void dataBlockRendersStableIdAndName() {
        String data = "cGRmLWRhdGE=";
        DataBlock block =
                DataBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data(data)
                                        .build())
                        .id("doc-123")
                        .name("report.pdf")
                        .build();
        String rendered =
                renderer.renderBlock(block, new CompactionMediaRenderer.RenderContext(null, 0));
        assertTrue(rendered.contains("id=doc-123"));
        assertTrue(rendered.contains("name=report.pdf"));
        assertTrue(rendered.contains("type=application/pdf"));
        assertTrue(rendered.contains("kind=data"));
        assertTrue(rendered.contains("sha256="));
        assertTrue(rendered.contains("bytes="));
        assertFalse(rendered.contains(data));
    }

    @Test
    void audioAndVideoBlocksRenderPlaceholders() {
        String audioData = "YXVkaW8tc2VjcmV0LXBheWxvYWQ=";
        String videoData = "dmlkZW8tc2VjcmV0LXBheWxvYWQ=";
        AudioBlock audio =
                AudioBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("audio/mp3")
                                        .data(audioData)
                                        .build())
                        .build();
        VideoBlock video =
                VideoBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("video/mp4")
                                        .data(videoData)
                                        .build())
                        .build();
        String renderedAudio =
                renderer.renderBlock(audio, new CompactionMediaRenderer.RenderContext("m1", 0));
        String renderedVideo =
                renderer.renderBlock(video, new CompactionMediaRenderer.RenderContext("m1", 0));
        assertTrue(renderedAudio.contains("kind=audio"));
        assertTrue(renderedAudio.contains("type=audio/mp3"));
        assertTrue(renderedAudio.contains("id=m1#0"));
        assertFalse(renderedAudio.contains(audioData));
        assertTrue(renderedVideo.contains("kind=video"));
        assertTrue(renderedVideo.contains("type=video/mp4"));
        assertFalse(renderedVideo.contains(videoData));
    }

    @Test
    void audioVideoAndDataBlocksRenderUrlPlaceholdersWithoutOriginalUrl() {
        String audioUrl = "https://example.com/audio.mp3?sig=audio-secret";
        String videoUrl = "http://example.com/video.mp4?sig=video-secret";
        String dataUrl = "file:///var/data/report.bin?h=data-secret";
        AudioBlock audio =
                AudioBlock.builder()
                        .source(URLSource.builder().url(audioUrl).mimeType("audio/mp3").build())
                        .build();
        VideoBlock video =
                VideoBlock.builder()
                        .source(URLSource.builder().url(videoUrl).mimeType("video/mp4").build())
                        .build();
        DataBlock data =
                DataBlock.builder()
                        .source(
                                URLSource.builder()
                                        .url(dataUrl)
                                        .mimeType("application/pdf")
                                        .build())
                        .id("doc-9")
                        .name("report.pdf")
                        .build();
        String renderedAudio =
                renderer.renderBlock(audio, new CompactionMediaRenderer.RenderContext("m2", 1));
        String renderedVideo =
                renderer.renderBlock(video, new CompactionMediaRenderer.RenderContext("m2", 2));
        String renderedData =
                renderer.renderBlock(data, new CompactionMediaRenderer.RenderContext("m2", 3));
        assertTrue(renderedAudio.contains("kind=audio"));
        assertTrue(renderedAudio.contains("protocol=https"));
        assertFalse(renderedAudio.contains(audioUrl));
        assertFalse(renderedAudio.contains("audio-secret"));
        assertTrue(renderedVideo.contains("kind=video"));
        assertTrue(renderedVideo.contains("protocol=http"));
        assertFalse(renderedVideo.contains(videoUrl));
        assertFalse(renderedVideo.contains("video-secret"));
        assertTrue(renderedData.contains("kind=data"));
        assertTrue(renderedData.contains("protocol=file"));
        assertFalse(renderedData.contains(dataUrl));
        assertFalse(renderedData.contains("data-secret"));
    }

    @Test
    void nestedAudioVideoAndDataRenderRecursivelyInToolResults() {
        String audioData = "YXVkaW8tbmVzdGVkLXBheWxvYWQ=";
        String videoData = "dmlkZW8tbmVzdGVkLXBheWxvYWQ=";
        String dataUrl = "https://example.com/nested.bin?tok=nested-secret";
        ToolResultBlock block =
                ToolResultBlock.builder()
                        .id("call_1")
                        .name("read_media")
                        .output(
                                List.of(
                                        TextBlock.builder().text("payloads:").build(),
                                        AudioBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("audio/wav")
                                                                .data(audioData)
                                                                .build())
                                                .build(),
                                        VideoBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("video/mp4")
                                                                .data(videoData)
                                                                .build())
                                                .build(),
                                        DataBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(dataUrl)
                                                                .mimeType("application/pdf")
                                                                .build())
                                                .id("doc-77")
                                                .name("nested.pdf")
                                                .build()))
                        .build();
        String rendered =
                renderer.renderBlock(block, new CompactionMediaRenderer.RenderContext("m1", 0));
        assertTrue(rendered.contains("[tool_result: read_media]"));
        assertTrue(rendered.contains("kind=audio"));
        assertTrue(rendered.contains("kind=video"));
        assertTrue(rendered.contains("kind=data"));
        assertTrue(rendered.contains("id=doc-77"));
        assertFalse(rendered.contains(audioData));
        assertFalse(rendered.contains(videoData));
        assertFalse(rendered.contains(dataUrl));
        assertFalse(rendered.contains("nested-secret"));
    }

    @Test
    void toolUseBlockRendersName() {
        ToolUseBlock block = ToolUseBlock.builder().id("call_1").name("read_file").build();
        String rendered =
                renderer.renderBlock(block, new CompactionMediaRenderer.RenderContext("m1", 0));
        assertTrue(rendered.contains("[tool_call: read_file]"));
    }

    @Test
    void toolResultBlockRendersNestedMediaRecursively() {
        String imageData =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        ToolResultBlock block =
                ToolResultBlock.builder()
                        .id("call_1")
                        .name("read_file")
                        .output(
                                List.of(
                                        TextBlock.builder().text("Here is the image:").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(imageData)
                                                                .build())
                                                .build()))
                        .build();
        String rendered =
                renderer.renderBlock(block, new CompactionMediaRenderer.RenderContext("m1", 0));
        assertTrue(rendered.contains("[tool_result: read_file]"));
        assertTrue(rendered.contains("Here is the image"));
        assertTrue(rendered.contains("kind=image"));
        assertFalse(rendered.contains(imageData));
    }

    @Test
    void placeholderEscapesSpecialCharacters() {
        DataBlock block =
                DataBlock.builder()
                        .source(
                                URLSource.builder()
                                        .url("https://example.com")
                                        .mimeType("text/plain")
                                        .build())
                        .id("id with=and [special]\"chars\\n")
                        .name("name")
                        .build();
        String rendered =
                renderer.renderBlock(block, new CompactionMediaRenderer.RenderContext(null, 0));
        assertTrue(rendered.contains("id=id\\ with\\=and\\ \\[special\\]\\\"chars\\\\n"));
        assertFalse(rendered.contains("id with"));
    }

    @Test
    void messageRenderingIncludesRolePrefixAndMediaPlaceholder() {
        String data =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .id("msg-1")
                        .content(
                                List.of(
                                        TextBlock.builder().text("look at this").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(data)
                                                                .build())
                                                .build()))
                        .build();
        String rendered = renderer.renderMessage(msg);
        assertTrue(rendered.startsWith("Human:"));
        assertTrue(rendered.contains("look at this"));
        assertTrue(rendered.contains("[attachment"));
        assertTrue(rendered.contains("id=msg-1#1"));
        assertFalse(rendered.contains(data));
    }
}
