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

package io.agentscope.core.mcp.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextContentTest {

    @Test
    void testTextContentWithText() {
        TextContent content = new TextContent("Hello, World!", "text");
        assertEquals("Hello, World!", content.text());
        assertEquals("text", content.type());
    }

    @Test
    void testTextContentWithEmptyText() {
        TextContent content = new TextContent("", "text");
        assertEquals("", content.text());
    }

    @Test
    void testTextContentWithDifferentType() {
        TextContent content = new TextContent("Some content", "markdown");
        assertEquals("Some content", content.text());
        assertEquals("markdown", content.type());
    }
}
