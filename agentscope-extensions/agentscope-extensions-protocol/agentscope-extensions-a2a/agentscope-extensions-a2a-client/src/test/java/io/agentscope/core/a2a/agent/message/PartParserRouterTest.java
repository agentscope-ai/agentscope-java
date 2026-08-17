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
import io.a2a.spec.FileContent;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithUri;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.VideoBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PartParserRouter.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Parsing different part types</li>
 *   <li>Handling null inputs</li>
 * </ul>
 */
@DisplayName("PartParserRouter Tests")
class PartParserRouterTest {

    private PartParserRouter router;

    @BeforeEach
    void setUp() {
        router = new PartParserRouter();
    }

    @Test
    @DisplayName("Should parse TextPart")
    void testParseTextPart() {
        TextPart part = new TextPart("Hello world");

        ContentBlock result = router.parse(part);

        assertNotNull(result);
        assertInstanceOf(TextBlock.class, result);
    }

    @Test
    @DisplayName("Should parse FilePart")
    void testParseFilePart() {
        FilePart part = mock(FilePart.class);
        when(part.getKind()).thenReturn(Part.Kind.FILE);
        FileContent file = new FileWithUri("video", "test.mp4", "https://exmaple.com/test.mp4");
        when(part.getFile()).thenReturn(file);

        ContentBlock result = router.parse(part);

        assertNotNull(result);
        assertInstanceOf(VideoBlock.class, result);
    }

    @Test
    @DisplayName("Should parse DataPart")
    void testParseDataPart() {
        DataPart part = mock(DataPart.class);
        when(part.getKind()).thenReturn(Part.Kind.DATA);

        ContentBlock result = router.parse(part);

        assertNotNull(result);
        assertInstanceOf(TextBlock.class, result);
    }

    @Test
    @DisplayName("Should return null for null input")
    void testParseNullInput() {
        ContentBlock result = router.parse(null);

        assertNull(result);
    }
}
