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
package io.agentscope.extensions.model.openai.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAIDataBlockConverterTest {

    @Test
    void testDataBlockImageUrlNotDropped() {
        var formatter = new OpenAIChatFormatter();

        Msg msg =
                new UserMessage(
                        List.of(
                                TextBlock.builder().text("analyze this").build(),
                                DataBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/photo.png")
                                                        .build())
                                        .build()));

        List<OpenAIMessage> result = formatter.format(List.of(msg));
        assertEquals(1, result.size());

        Object content = result.get(0).getContent();
        assertTrue(content instanceof List<?>);

        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) content;

        boolean hasText =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "text".equals(p.getType())
                                                && "analyze this".equals(p.getText()));
        boolean hasImage =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "image_url".equals(p.getType())
                                                && p.getImageUrl() != null
                                                && "https://example.com/photo.png"
                                                        .equals(p.getImageUrl().getUrl()));

        assertTrue(hasText, "text block should be present");
        assertTrue(hasImage, "image_url from DataBlock should be present");
    }

    @Test
    void testDataBlockBase64Image() {
        var formatter = new OpenAIChatFormatter();

        Msg msg =
                new UserMessage(
                        List.of(
                                DataBlock.builder()
                                        .source(new Base64Source("image/png", "iVBORw0KGgo="))
                                        .build()));

        List<OpenAIMessage> result = formatter.format(List.of(msg));
        assertEquals(1, result.size());

        Object content = result.get(0).getContent();
        assertTrue(content instanceof List<?>);

        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) content;

        boolean hasImage =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "image_url".equals(p.getType())
                                                && p.getImageUrl() != null
                                                && p.getImageUrl()
                                                        .getUrl()
                                                        .startsWith("data:image/png;base64"));

        assertTrue(hasImage, "base64 image from DataBlock should be present");
    }

    @Test
    void testDataBlockVideoUrl() {
        var formatter = new OpenAIChatFormatter();

        Msg msg =
                new UserMessage(
                        List.of(
                                DataBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/movie.mp4")
                                                        .build())
                                        .build()));

        List<OpenAIMessage> result = formatter.format(List.of(msg));
        assertEquals(1, result.size());

        Object content = result.get(0).getContent();
        assertTrue(content instanceof List<?>);

        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) content;

        boolean hasVideo =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "video_url".equals(p.getType())
                                                && p.getVideoUrl() != null
                                                && "https://example.com/movie.mp4"
                                                        .equals(p.getVideoUrl().getUrl()));
        assertTrue(hasVideo, "video_url from DataBlock should be present");
    }

    @Test
    void testDataBlockAudioUrlFallback() {
        var formatter = new OpenAIChatFormatter();

        Msg msg =
                new UserMessage(
                        List.of(
                                DataBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/song.mp3")
                                                        .build())
                                        .build()));

        List<OpenAIMessage> result = formatter.format(List.of(msg));
        assertEquals(1, result.size());

        Object content = result.get(0).getContent();
        assertTrue(content instanceof List<?>);

        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) content;

        boolean hasAudioText =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "text".equals(p.getType())
                                                && p.getText() != null
                                                && p.getText()
                                                        .contains("https://example.com/song.mp3"));
        assertTrue(hasAudioText, "URL-based audio should produce a text placeholder");
    }

    @Test
    void testDataBlockBase64Audio() {
        var formatter = new OpenAIChatFormatter();

        Msg msg =
                new UserMessage(
                        List.of(
                                DataBlock.builder()
                                        .source(new Base64Source("audio/wav", "base64audiodata"))
                                        .build()));

        List<OpenAIMessage> result = formatter.format(List.of(msg));
        assertEquals(1, result.size());

        Object content = result.get(0).getContent();
        assertTrue(content instanceof List<?>);

        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) content;

        boolean hasAudio =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "input_audio".equals(p.getType())
                                                && p.getInputAudio() != null
                                                && "base64audiodata"
                                                        .equals(p.getInputAudio().getData()));
        assertTrue(hasAudio, "Base64 audio from DataBlock should produce input_audio part");
    }

    @Test
    void testDataBlockUnknownMimeType() {
        var formatter = new OpenAIChatFormatter();

        Msg msg =
                new UserMessage(
                        List.of(
                                DataBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/file.unknown")
                                                        .build())
                                        .build()));

        List<OpenAIMessage> result = formatter.format(List.of(msg));
        assertEquals(1, result.size());

        Object content = result.get(0).getContent();
        assertTrue(content instanceof List<?>);

        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) content;

        boolean hasFallback =
                parts.stream()
                        .anyMatch(
                                p ->
                                        "text".equals(p.getType())
                                                && p.getText() != null
                                                && p.getText()
                                                        .startsWith("[Data - unrecognized type:"));
        assertTrue(hasFallback, "unknown MIME should produce a text placeholder");
    }
}
