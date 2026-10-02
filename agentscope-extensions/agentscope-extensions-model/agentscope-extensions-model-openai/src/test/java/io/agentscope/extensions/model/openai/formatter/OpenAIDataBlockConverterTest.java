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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenAIDataBlockConverterTest {

    private OpenAIMessageConverter converter;

    @BeforeEach
    void setUp() {
        Function<Msg, String> textExtractor =
                msg -> {
                    StringBuilder sb = new StringBuilder();
                    for (ContentBlock block : msg.getContent()) {
                        if (block instanceof TextBlock tb) {
                            sb.append(tb.getText());
                        }
                    }
                    return sb.toString();
                };

        Function<List<ContentBlock>, String> toolResultConverter =
                blocks -> {
                    StringBuilder sb = new StringBuilder();
                    for (ContentBlock block : blocks) {
                        if (block instanceof TextBlock tb) {
                            sb.append(tb.getText());
                        }
                    }
                    return sb.toString();
                };

        converter = new OpenAIMessageConverter(textExtractor, toolResultConverter);
    }

    @Test
    @DisplayName("DataBlock with image URL should produce imageUrl content part")
    void testDataBlockImageUrl() {
        URLSource source = URLSource.builder().url("https://example.com/photo.jpg").build();
        DataBlock dataBlock = DataBlock.builder().source(source).build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(dataBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
        assertEquals(1, parts.size());
        assertNotNull(parts.get(0).getImageUrl());
        assertEquals("https://example.com/photo.jpg", parts.get(0).getImageUrl().getUrl());
    }

    @Test
    @DisplayName("DataBlock with base64 image should produce imageUrl content part with data URL")
    void testDataBlockBase64Image() {
        Base64Source source =
                Base64Source.builder().data("iVBORw0KGgo=").mediaType("image/png").build();
        DataBlock dataBlock = DataBlock.builder().source(source).build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(dataBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
        assertEquals(1, parts.size());
        assertNotNull(parts.get(0).getImageUrl());
        assertTrue(parts.get(0).getImageUrl().getUrl().startsWith("data:image/png;base64,"));
    }

    @Test
    @DisplayName("DataBlock with video URL should produce videoUrl content part")
    void testDataBlockVideoUrl() {
        URLSource source = URLSource.builder().url("https://example.com/clip.mp4").build();
        DataBlock dataBlock = DataBlock.builder().source(source).build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(dataBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
        assertEquals(1, parts.size());
        assertNotNull(parts.get(0).getVideoUrl());
    }

    @Test
    @DisplayName("DataBlock with base64 audio should produce inputAudio content part")
    void testDataBlockBase64Audio() {
        Base64Source source =
                Base64Source.builder().data("ZmFrZSBhdWRpbyBkYXRh").mediaType("audio/mp3").build();
        DataBlock dataBlock = DataBlock.builder().source(source).build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(dataBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
        assertEquals(1, parts.size());
        assertNotNull(parts.get(0).getInputAudio());
    }

    @Test
    @DisplayName("DataBlock with audio URL should produce text fallback")
    void testDataBlockAudioUrlFallback() {
        URLSource source = URLSource.builder().url("https://example.com/sound.mp3").build();
        DataBlock dataBlock = DataBlock.builder().source(source).build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(dataBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
        assertEquals(1, parts.size());
        assertTrue(parts.get(0).getText().contains("Audio URL:"));
    }

    @Test
    @DisplayName("DataBlock with extension-less URL and mimeType hint should route correctly")
    void testDataBlockMimeTypeHint() {
        URLSource source =
                URLSource.builder()
                        .url("https://cdn.example.com/media/abc123")
                        .mimeType("image/jpeg")
                        .build();
        DataBlock dataBlock = DataBlock.builder().source(source).build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(dataBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
        assertEquals(1, parts.size());
        assertNotNull(parts.get(0).getImageUrl());
    }

    @Test
    @DisplayName("DataBlock with unresolvable MIME type should produce error text fallback")
    void testDataBlockUnresolvableMimeType() {
        URLSource source = URLSource.builder().url("https://cdn.example.com/media/abc123").build();
        DataBlock dataBlock = DataBlock.builder().source(source).build();

        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(dataBlock)).build();

        OpenAIMessage result = converter.convertToMessage(msg, true);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<OpenAIContentPart> parts = (List<OpenAIContentPart>) result.getContent();
        assertEquals(1, parts.size());
        assertTrue(parts.get(0).getText().contains("processing failed"));
    }

    @Test
    @DisplayName("MediaUtils.resolveMimeType should resolve Base64Source mediaType")
    void testResolveMimeTypeBase64() {
        Base64Source source = Base64Source.builder().data("data").mediaType("image/jpeg").build();
        assertEquals("image/jpeg", MediaUtils.resolveMimeType(source));
    }

    @Test
    @DisplayName("MediaUtils.resolveMimeType should resolve URLSource with extension")
    void testResolveMimeTypeUrlExtension() {
        URLSource source = URLSource.builder().url("https://example.com/photo.png").build();
        assertEquals("image/png", MediaUtils.resolveMimeType(source));
    }

    @Test
    @DisplayName("MediaUtils.resolveMimeType should prefer URLSource mimeType hint over extension")
    void testResolveMimeTypeHintOverExtension() {
        URLSource source =
                URLSource.builder()
                        .url("https://example.com/file.mp3")
                        .mimeType("image/png")
                        .build();
        assertEquals("image/png", MediaUtils.resolveMimeType(source));
    }

    @Test
    @DisplayName("MediaUtils.resolveMimeType should throw for extension-less URL without hint")
    void testResolveMimeTypeNoExtensionNoHint() {
        URLSource source = URLSource.builder().url("https://cdn.example.com/media/abc123").build();
        assertThrows(IllegalArgumentException.class, () -> MediaUtils.resolveMimeType(source));
    }
}
