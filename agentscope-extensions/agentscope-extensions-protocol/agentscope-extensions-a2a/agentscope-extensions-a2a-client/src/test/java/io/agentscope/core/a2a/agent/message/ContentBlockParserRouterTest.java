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
package io.agentscope.core.a2a.agent.message;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.a2a.spec.DataPart;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ContentBlockParserRouter.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Parsing different content block types</li>
 *   <li>Handling null inputs</li>
 *   <li>Handling unsupported content block types</li>
 * </ul>
 */
@DisplayName("ContentBlockParserRouter Tests")
class ContentBlockParserRouterTest {

    private ContentBlockParserRouter router;

    @BeforeEach
    void setUp() {
        router = new ContentBlockParserRouter();
    }

    @Test
    @DisplayName("Should parse TextBlock")
    void testParseTextBlock() {
        TextBlock block = TextBlock.builder().text("Hello world").build();

        Part<?> result = router.parse(block);

        assertNotNull(result);
        assertInstanceOf(TextPart.class, result);
    }

    @Test
    @DisplayName("Should parse ThinkingBlock")
    void testParseThinkingBlock() {
        ThinkingBlock block = ThinkingBlock.builder().thinking("Thinking process").build();

        Part<?> result = router.parse(block);

        assertNotNull(result);
        assertInstanceOf(TextPart.class, result);
    }

    @Test
    @DisplayName("Should parse ImageBlock")
    void testParseImageBlock() {
        ImageBlock block = mock(ImageBlock.class);
        Base64Source source = mock(Base64Source.class);
        when(source.getMediaType()).thenReturn("image/png");
        when(source.getData()).thenReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        when(block.getSource()).thenReturn(source);

        Part<?> result = router.parse(block);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        assertInstanceOf(FileWithBytes.class, ((FilePart) result).getFile());
    }

    @Test
    @DisplayName("Should parse AudioBlock")
    void testParseAudioBlock() {
        AudioBlock block = mock(AudioBlock.class);
        Base64Source source = mock(Base64Source.class);
        when(source.getMediaType()).thenReturn("audio/mpeg");
        when(source.getData()).thenReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        when(block.getSource()).thenReturn(source);

        Part<?> result = router.parse(block);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        assertInstanceOf(FileWithBytes.class, ((FilePart) result).getFile());
    }

    @Test
    @DisplayName("Should parse VideoBlock")
    void testParseVideoBlock() {
        VideoBlock block = mock(VideoBlock.class);
        Base64Source source = mock(Base64Source.class);
        when(source.getMediaType()).thenReturn("video/mp4");
        when(source.getData()).thenReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        when(block.getSource()).thenReturn(source);

        Part<?> result = router.parse(block);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        assertInstanceOf(FileWithBytes.class, ((FilePart) result).getFile());
    }

    @Test
    @DisplayName("Should parse ToolUseBlock")
    void testParseToolUseBlock() {
        ToolUseBlock block = ToolUseBlock.builder().name("calculator").id("123").build();

        Part<?> result = router.parse(block);

        assertNotNull(result);
        assertInstanceOf(DataPart.class, result);
    }

    @Test
    @DisplayName("Should parse ToolResultBlock")
    void testParseToolResultBlock() {
        ToolResultBlock block = ToolResultBlock.builder().name("calculator").id("123").build();

        Part<?> result = router.parse(block);

        assertNotNull(result);
        assertInstanceOf(DataPart.class, result);
    }

    @Test
    @DisplayName("Should return null for null input")
    void testParseNullInput() {
        Part<?> result = router.parse(null);

        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for unsupported content block types")
    void testParseUnsupportedContentBlock() {
        ContentBlock block = mock(ContentBlock.class);

        Part<?> result = router.parse(block);

        assertNull(result);
    }
}
