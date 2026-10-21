/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SessionToolResultContentTest {
    @Test
    void preservesTextAndImageBlocksAndTheirOrder() {
        var blocks =
                SessionToolResultContent.blocks(
                        Map.of(
                                "content",
                                List.of(
                                        Map.of("type", "text", "text", "First"),
                                        Map.of(
                                                "type",
                                                "image",
                                                "source",
                                                Map.of(
                                                        "type",
                                                        "base64",
                                                        "media_type",
                                                        "image/png",
                                                        "data",
                                                        "eA==")),
                                        Map.of("type", "text", "text", "Last"))));
        assertEquals(3, blocks.size());
        assertEquals("First", assertInstanceOf(TextBlock.class, blocks.get(0)).getText());
        assertInstanceOf(ImageBlock.class, blocks.get(1));
        assertEquals("Last", assertInstanceOf(TextBlock.class, blocks.get(2)).getText());
    }

    @Test
    void acceptsEmptyAndLegacyResultsButRejectsAmbiguousOrUnsupportedContent() {
        assertEquals(List.of(), SessionToolResultContent.blocks(Map.of("content", List.of())));
        assertEquals(
                "legacy",
                assertInstanceOf(
                                TextBlock.class,
                                SessionToolResultContent.blocks(Map.of("output", "legacy")).get(0))
                        .getText());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        SessionToolResultContent.blocks(
                                Map.of("output", "legacy", "content", List.of())));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        SessionToolResultContent.blocks(
                                Map.of(
                                        "content",
                                        List.of(
                                                Map.of(
                                                        "type",
                                                        "thinking",
                                                        "thinking",
                                                        "private")))));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        SessionToolResultContent.blocks(
                                Map.of("content", List.of(Map.of("type", "text")))));
    }
}
