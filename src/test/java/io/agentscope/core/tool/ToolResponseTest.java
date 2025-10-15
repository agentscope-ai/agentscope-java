/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for ToolResponse structure and serialization.
 *
 * <p>These tests verify ToolResponse creation, validation, and serialization/deserialization.
 *
 * <p>Tagged as "unit" - tests response structure.
 */
@Tag("unit")
@DisplayName("ToolResponse Tests")
class ToolResponseTest {

    @Test
    @DisplayName("Should create valid success response")
    void testSuccessResponse() {
        // Create success response using utility
        ToolResponse response = ToolTestUtils.createSuccessResponse("Operation successful");

        assertNotNull(response, "Response should not be null");
        assertTrue(ToolTestUtils.isValidToolResponse(response), "Response should be valid");

        // Verify content
        assertNotNull(response.getContent(), "Content should not be null");
        assertFalse(response.getContent().isEmpty(), "Content should not be empty");

        // Verify it's not an error response
        assertFalse(ToolTestUtils.isErrorResponse(response), "Should not be error response");

        // Extract and verify content
        String content = ToolTestUtils.extractContent(response);
        assertNotNull(content, "Should extract content");
        assertTrue(content.contains("successful"), "Content should contain success message");
    }

    @Test
    @DisplayName("Should create valid error response")
    void testErrorResponse() {
        // Create error response using utility
        ToolResponse response = ToolTestUtils.createErrorResponse("Operation failed");

        assertNotNull(response, "Response should not be null");
        assertTrue(ToolTestUtils.isValidToolResponse(response), "Response should be valid");

        // Verify it's an error response
        assertTrue(ToolTestUtils.isErrorResponse(response), "Should be error response");

        // Verify metadata contains error flag
        assertNotNull(response.getMetadata(), "Metadata should not be null");
        assertTrue(
                response.getMetadata().containsKey("error"), "Metadata should contain error flag");

        // Extract and verify content
        String content = ToolTestUtils.extractContent(response);
        assertNotNull(content, "Should extract content");
        assertTrue(content.toLowerCase().contains("error"), "Content should contain error message");
    }

    @Test
    @DisplayName("Should handle response with metadata")
    void testResponseWithMetadata() {
        // Create response with metadata
        TextBlock textBlock = TextBlock.builder().text("Test content").build();
        Map<String, Object> metadata =
                Map.of("timestamp", System.currentTimeMillis(), "source", "test_tool");

        ToolResponse response =
                new ToolResponse(List.of(textBlock), metadata, false, true, false, null);

        assertNotNull(response, "Response should not be null");
        assertNotNull(response.getMetadata(), "Metadata should not be null");
        assertTrue(response.getMetadata().containsKey("timestamp"), "Should have timestamp");
        assertTrue(response.getMetadata().containsKey("source"), "Should have source");
        assertEquals("test_tool", response.getMetadata().get("source"), "Source should match");
    }

    @Test
    @DisplayName("Should handle response attributes correctly")
    void testResponseAttributes() {
        // Create response with various attributes
        TextBlock textBlock = TextBlock.builder().text("Stream chunk").build();

        // Test streaming response
        ToolResponse streamResponse =
                new ToolResponse(List.of(textBlock), null, true, false, false, "stream_1");

        assertTrue(streamResponse.isStream(), "Should be stream response");
        assertFalse(streamResponse.isLast(), "Should not be last chunk");
        assertFalse(streamResponse.isInterrupted(), "Should not be interrupted");
        assertNotNull(streamResponse.getId(), "Should have ID");

        // Test final response
        ToolResponse finalResponse =
                new ToolResponse(List.of(textBlock), null, false, true, false, "final_1");

        assertFalse(finalResponse.isStream(), "Should not be stream response");
        assertTrue(finalResponse.isLast(), "Should be last/final response");
        assertNotNull(finalResponse.getId(), "Should have ID");

        // Test interrupted response
        ToolResponse interruptedResponse =
                new ToolResponse(List.of(textBlock), null, false, true, true, "interrupted_1");

        assertTrue(interruptedResponse.isInterrupted(), "Should be interrupted");
    }
}
