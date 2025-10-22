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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpContentConverterTest {

    @Test
    void testConstructorThrowsException() {
        // Test that utility class cannot be instantiated
        var exception =
                assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> {
                            var constructor = McpContentConverter.class.getDeclaredConstructor();
                            constructor.setAccessible(true);
                            constructor.newInstance();
                        });
        // The InvocationTargetException should wrap UnsupportedOperationException
        assertTrue(exception.getCause() instanceof UnsupportedOperationException);
    }

    @Test
    void testConvertCallToolResult_Success() {
        // Create successful MCP result with text content
        McpSchema.TextContent textContent = new McpSchema.TextContent("Operation successful");
        McpSchema.CallToolResult mcpResult =
                new McpSchema.CallToolResult(List.of(textContent), false);

        ToolResultBlock result = McpContentConverter.convertCallToolResult(mcpResult);

        assertNotNull(result);
        ContentBlock output = result.getOutput();
        assertTrue(output instanceof TextBlock);
        String text = ((TextBlock) output).getText();
        assertEquals("Operation successful", text);
        assertFalse(text.startsWith("Error:"));
    }

    @Test
    void testConvertCallToolResult_Error() {
        // Create error MCP result
        McpSchema.TextContent errorContent = new McpSchema.TextContent("File not found");
        McpSchema.CallToolResult mcpResult =
                new McpSchema.CallToolResult(List.of(errorContent), true);

        ToolResultBlock result = McpContentConverter.convertCallToolResult(mcpResult);

        assertNotNull(result);
        ContentBlock output = result.getOutput();
        assertTrue(output instanceof TextBlock);
        String text = ((TextBlock) output).getText();
        assertTrue(text.startsWith("Error:"));
        assertTrue(text.contains("File not found"));
    }

    @Test
    void testConvertCallToolResult_NullResult() {
        ToolResultBlock result = McpContentConverter.convertCallToolResult(null);

        assertNotNull(result);
        ContentBlock output = result.getOutput();
        assertTrue(output instanceof TextBlock);
        String text = ((TextBlock) output).getText();
        assertTrue(text.startsWith("Error:"));
        assertTrue(text.contains("null result"));
    }

    @Test
    void testConvertCallToolResult_EmptyContent() {
        // Create result with empty content list
        McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(List.of(), false);

        ToolResultBlock result = McpContentConverter.convertCallToolResult(mcpResult);

        assertNotNull(result);
        ContentBlock output = result.getOutput();
        assertTrue(output instanceof TextBlock);
        String text = ((TextBlock) output).getText();
        assertEquals("", text);
        assertFalse(text.startsWith("Error:"));
    }

    @Test
    void testConvertCallToolResult_MultipleErrorMessages() {
        // Create error result with multiple text contents
        McpSchema.TextContent error1 = new McpSchema.TextContent("Error 1");
        McpSchema.TextContent error2 = new McpSchema.TextContent("Error 2");
        McpSchema.CallToolResult mcpResult =
                new McpSchema.CallToolResult(List.of(error1, error2), true);

        ToolResultBlock result = McpContentConverter.convertCallToolResult(mcpResult);

        assertNotNull(result);
        ContentBlock output = result.getOutput();
        assertTrue(output instanceof TextBlock);
        String text = ((TextBlock) output).getText();
        assertTrue(text.startsWith("Error:"));
        assertTrue(text.contains("Error 1"));
        assertTrue(text.contains("Error 2"));
    }

    @Test
    void testConvertContentList_MixedContent() {
        // Create a list with multiple content types
        McpSchema.TextContent textContent = new McpSchema.TextContent("Hello");
        McpSchema.ImageContent imageContent =
                new McpSchema.ImageContent(null, "base64data==", "image/png");

        List<McpSchema.Content> contents = List.of(textContent, imageContent);

        List<ContentBlock> blocks = McpContentConverter.convertContentList(contents);

        assertNotNull(blocks);
        assertEquals(2, blocks.size());

        // First block should be TextBlock with "Hello"
        assertTrue(blocks.get(0) instanceof TextBlock);
        assertEquals("Hello", ((TextBlock) blocks.get(0)).getText());

        // Second block should be ImageBlock
        assertTrue(blocks.get(1) instanceof ImageBlock);
    }

    @Test
    void testConvertContentList_NullOrEmpty() {
        // Test null list
        List<ContentBlock> nullResult = McpContentConverter.convertContentList(null);
        assertNotNull(nullResult);
        assertEquals(1, nullResult.size());
        assertTrue(nullResult.get(0) instanceof TextBlock);
        assertEquals("", ((TextBlock) nullResult.get(0)).getText());

        // Test empty list
        List<ContentBlock> emptyResult = McpContentConverter.convertContentList(List.of());
        assertNotNull(emptyResult);
        assertEquals(1, emptyResult.size());
        assertTrue(emptyResult.get(0) instanceof TextBlock);
        assertEquals("", ((TextBlock) emptyResult.get(0)).getText());
    }

    @Test
    void testConvertContentList_WithNullElements() {
        // Create list with null element (should be filtered out)
        List<McpSchema.Content> contents = new ArrayList<>();
        contents.add(new McpSchema.TextContent("Valid text"));
        contents.add(null);

        List<ContentBlock> blocks = McpContentConverter.convertContentList(contents);

        assertNotNull(blocks);
        // Only valid content should be included
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0) instanceof TextBlock);
    }

    @Test
    void testConvertContent_TextContent() {
        McpSchema.TextContent textContent = new McpSchema.TextContent("Test message");

        ContentBlock block = McpContentConverter.convertContent(textContent);

        assertNotNull(block);
        assertTrue(block instanceof TextBlock);
        assertEquals("Test message", ((TextBlock) block).getText());
    }

    @Test
    void testConvertContent_TextContent_EmptyText() {
        McpSchema.TextContent textContent = new McpSchema.TextContent("");

        ContentBlock block = McpContentConverter.convertContent(textContent);

        assertNotNull(block);
        assertTrue(block instanceof TextBlock);
        assertEquals("", ((TextBlock) block).getText());
    }

    @Test
    void testConvertContent_TextContent_NullText() {
        McpSchema.TextContent textContent = new McpSchema.TextContent(null);

        ContentBlock block = McpContentConverter.convertContent(textContent);

        assertNotNull(block);
        assertTrue(block instanceof TextBlock);
        assertEquals("", ((TextBlock) block).getText());
    }

    @Test
    void testConvertContent_ImageContent() {
        McpSchema.ImageContent imageContent =
                new McpSchema.ImageContent(null, "SGVsbG8gV29ybGQ=", "image/jpeg");

        ContentBlock block = McpContentConverter.convertContent(imageContent);

        assertNotNull(block);
        assertTrue(block instanceof ImageBlock);

        ImageBlock imageBlock = (ImageBlock) block;
        assertNotNull(imageBlock.getSource());
    }

    @Test
    void testConvertContent_ImageContent_EmptyData() {
        McpSchema.ImageContent imageContent = new McpSchema.ImageContent(null, "", "image/png");

        ContentBlock block = McpContentConverter.convertContent(imageContent);

        // Empty image data should return null (filtered out)
        assertNull(block);
    }

    @Test
    void testConvertContent_ImageContent_NullData() {
        McpSchema.ImageContent imageContent = new McpSchema.ImageContent(null, null, "image/png");

        ContentBlock block = McpContentConverter.convertContent(imageContent);

        assertNull(block);
    }

    @Test
    void testConvertContent_AudioContent() {
        McpSchema.AudioContent audioContent =
                new McpSchema.AudioContent(null, "audiodata", "audio/mp3");

        ContentBlock block = McpContentConverter.convertContent(audioContent);

        assertNotNull(block);
        assertTrue(block instanceof TextBlock);
        String text = ((TextBlock) block).getText();
        assertTrue(text.contains("Audio content"));
        assertTrue(text.contains("audio/mp3"));
    }

    @Test
    void testConvertContent_EmbeddedResource_TextResource() {
        McpSchema.TextResourceContents textResource =
                new McpSchema.TextResourceContents(
                        "file:///test.txt", "text/plain", "File content");
        McpSchema.EmbeddedResource embeddedResource =
                new McpSchema.EmbeddedResource(null, textResource);

        ContentBlock block = McpContentConverter.convertContent(embeddedResource);

        assertNotNull(block);
        assertTrue(block instanceof TextBlock);
        assertEquals("File content", ((TextBlock) block).getText());
    }

    @Test
    void testConvertContent_EmbeddedResource_BlobResource_Image() {
        McpSchema.BlobResourceContents blobResource =
                new McpSchema.BlobResourceContents("file:///image.png", "image/png", "blobdata123");
        McpSchema.EmbeddedResource embeddedResource =
                new McpSchema.EmbeddedResource(null, blobResource);

        ContentBlock block = McpContentConverter.convertContent(embeddedResource);

        assertNotNull(block);
        assertTrue(block instanceof ImageBlock);
    }

    @Test
    void testConvertContent_EmbeddedResource_BlobResource_NonImage() {
        McpSchema.BlobResourceContents blobResource =
                new McpSchema.BlobResourceContents(
                        "file:///data.bin", "application/octet-stream", "binarydata");
        McpSchema.EmbeddedResource embeddedResource =
                new McpSchema.EmbeddedResource(null, blobResource);

        ContentBlock block = McpContentConverter.convertContent(embeddedResource);

        assertNotNull(block);
        assertTrue(block instanceof TextBlock);
        String text = ((TextBlock) block).getText();
        assertTrue(text.contains("Binary resource"));
        assertTrue(text.contains("application/octet-stream"));
        assertTrue(text.contains("file:///data.bin"));
    }

    @Test
    void testConvertContent_EmbeddedResource_BlobResource_NullMimeType() {
        McpSchema.BlobResourceContents blobResource =
                new McpSchema.BlobResourceContents("file:///data.bin", null, "binarydata");
        McpSchema.EmbeddedResource embeddedResource =
                new McpSchema.EmbeddedResource(null, blobResource);

        ContentBlock block = McpContentConverter.convertContent(embeddedResource);

        assertNotNull(block);
        assertTrue(block instanceof TextBlock);
    }

    @Test
    void testConvertContent_NullContent() {
        ContentBlock block = McpContentConverter.convertContent(null);
        assertNull(block);
    }

    // Note: Cannot test unsupported Content type because Content is a sealed class
    // The MCP SDK only allows specific Content implementations (Text, Image, Audio,
    // EmbeddedResource)

    @Test
    void testExtractErrorMessage_EmptyContent() {
        // Test indirect through convertCallToolResult with empty error content
        List<McpSchema.Content> emptyList = List.of();
        McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(emptyList, true);

        ToolResultBlock result = McpContentConverter.convertCallToolResult(mcpResult);

        assertNotNull(result);
        ContentBlock output = result.getOutput();
        assertTrue(output instanceof TextBlock);
        String text = ((TextBlock) output).getText();
        assertTrue(text.startsWith("Error:"));
        // Empty error content should result in "Unknown error"
        assertTrue(text.contains("Unknown error"));
    }

    @Test
    void testExtractErrorMessage_NullContent() {
        // Test indirect through convertCallToolResult with null content
        List<McpSchema.Content> nullList = null;
        McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(nullList, true);

        ToolResultBlock result = McpContentConverter.convertCallToolResult(mcpResult);

        assertNotNull(result);
        ContentBlock output = result.getOutput();
        assertTrue(output instanceof TextBlock);
        String text = ((TextBlock) output).getText();
        assertTrue(text.startsWith("Error:"));
        assertTrue(text.contains("Unknown error"));
    }

    @Test
    void testComplexScenario_MultipleContentTypes() {
        // Create a complex result with multiple content types
        McpSchema.TextContent text1 = new McpSchema.TextContent("Header text");
        McpSchema.ImageContent image = new McpSchema.ImageContent(null, "imagedata", "image/png");
        McpSchema.TextContent text2 = new McpSchema.TextContent("Footer text");
        McpSchema.AudioContent audio = new McpSchema.AudioContent(null, "audiodata", "audio/wav");

        List<McpSchema.Content> contents = List.of(text1, image, text2, audio);
        McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(contents, false);

        ToolResultBlock result = McpContentConverter.convertCallToolResult(mcpResult);

        assertNotNull(result);
        ContentBlock output = result.getOutput();
        assertNotNull(output);
        // When multiple blocks are aggregated, the result is a TextBlock with combined text
        // The aggregation logic is in ToolResultBlock.fromContentBlocks()
        assertTrue(output instanceof TextBlock);
        String text = ((TextBlock) output).getText();
        assertFalse(text.startsWith("Error:"));
        // The aggregated text should contain elements from the original blocks
        assertTrue(text.contains("Header text") || text.contains("ImageBlock"));
    }

    // Note: Cannot test unknown ResourceContents type because it's a sealed class
    // The MCP SDK only allows TextResourceContents and BlobResourceContents implementations

    @Test
    void testContentList_AllNullElements() {
        // Create list with all null elements
        List<McpSchema.Content> contents = new ArrayList<>();
        contents.add(null);
        contents.add(null);
        contents.add(null);

        List<ContentBlock> blocks = McpContentConverter.convertContentList(contents);

        assertNotNull(blocks);
        // All nulls filtered out, should return empty text block
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0) instanceof TextBlock);
        assertEquals("", ((TextBlock) blocks.get(0)).getText());
    }

    @Test
    void testImageContent_WithDifferentMimeTypes() {
        // Test various image mime types
        String[] mimeTypes = {"image/png", "image/jpeg", "image/gif", "image/webp"};

        for (String mimeType : mimeTypes) {
            McpSchema.ImageContent imageContent =
                    new McpSchema.ImageContent(null, "imagedata", mimeType);

            ContentBlock block = McpContentConverter.convertContent(imageContent);

            assertNotNull(block, "Failed for mime type: " + mimeType);
            assertTrue(block instanceof ImageBlock, "Failed for mime type: " + mimeType);
        }
    }

    @Test
    void testBlobResource_ImageMimeTypeVariations() {
        // Test blob resources with various image mime types
        String[] imageMimeTypes = {
            "image/png", "image/jpeg", "image/gif", "image/svg+xml", "image/bmp"
        };

        for (String mimeType : imageMimeTypes) {
            McpSchema.BlobResourceContents blobResource =
                    new McpSchema.BlobResourceContents("file:///test", mimeType, "data");
            McpSchema.EmbeddedResource embeddedResource =
                    new McpSchema.EmbeddedResource(null, blobResource);

            ContentBlock block = McpContentConverter.convertContent(embeddedResource);

            assertNotNull(block, "Failed for mime type: " + mimeType);
            assertTrue(block instanceof ImageBlock, "Failed for mime type: " + mimeType);
        }
    }
}
