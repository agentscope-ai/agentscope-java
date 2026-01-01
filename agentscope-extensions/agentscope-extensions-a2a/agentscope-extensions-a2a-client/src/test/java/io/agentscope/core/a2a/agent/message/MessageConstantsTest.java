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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for MessageConstants.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Block content type constants</li>
 *   <li>Tool metadata key constants</li>
 * </ul>
 */
@DisplayName("MessageConstants Tests")
class MessageConstantsTest {

    @Test
    @DisplayName("Should have correct block content type constants")
    void testBlockContentTypeConstants() {
        assertEquals("text", MessageConstants.BlockContent.TYPE_TEXT);
        assertEquals("thinking", MessageConstants.BlockContent.TYPE_THINKING);
        assertEquals("image", MessageConstants.BlockContent.TYPE_IMAGE);
        assertEquals("audio", MessageConstants.BlockContent.TYPE_AUDIO);
        assertEquals("video", MessageConstants.BlockContent.TYPE_VIDEO);
        assertEquals("tool_use", MessageConstants.BlockContent.TYPE_TOOL_USE);
        assertEquals("tool_result", MessageConstants.BlockContent.TYPE_TOOL_RESULT);
    }

    @Test
    @DisplayName("Should have correct tool metadata key constants")
    void testToolMetadataKeyConstants() {
        assertNotNull(MessageConstants.TOOL_NAME_METADATA_KEY);
        assertNotNull(MessageConstants.TOOL_CALL_ID_METADATA_KEY);
        assertNotNull(MessageConstants.TOOL_RESULT_OUTPUT_METADATA_KEY);
    }
}
