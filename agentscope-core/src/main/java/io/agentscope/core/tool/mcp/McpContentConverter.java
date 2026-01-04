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
package io.agentscope.core.tool.mcp;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for converting between MCP content types and AgentScope content blocks.
 * This converter handles the translation of MCP protocol data structures to AgentScope's
 * internal representation.
 *
 * <p>Supported conversions:
 * <ul>
 *   <li>MCP CallToolResult → AgentScope ToolResultBlock</li>
 *   <li>MCP TextContent → AgentScope TextBlock</li>
 *   <li>MCP ImageContent → AgentScope ImageBlock</li>
 *   <li>MCP AudioContent → AgentScope AudioBlock (future)</li>
 * </ul>
 */
public class McpContentConverter {

    /**
     * Private constructor to prevent instantiation.
     */
    private McpContentConverter() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Converts an MCP CallToolResult to an AgentScope ToolResultBlock.
     *
     * @param mcpResult the MCP tool call result
     * @return the converted ToolResultBlock
     */
    public static ToolResultBlock convertCallToolResult(McpSchema.CallToolResult mcpResult) {
        if (mcpResult == null) {
            return ToolResultBlock.error("MCP tool returned null result");
        }

        // Check if the result is an error
        if (Boolean.TRUE.equals(mcpResult.isError())) {
            String errorMsg = extractErrorMessage(mcpResult.content());
            return ToolResultBlock.error(errorMsg);
        }

        // Convert successful result
        List<ContentBlock> contentBlocks = convertContentList(mcpResult.content());

        return ToolResultBlock.of(contentBlocks);
    }

    /**
     * Converts a list of MCP Content to AgentScope ContentBlocks.
     *
     * @param mcpContents the list of MCP content
     * @return list of converted ContentBlocks
     */
    public static List<ContentBlock> convertContentList(List<McpSchema.Content> mcpContents) {
        if (mcpContents == null || mcpContents.isEmpty()) {
            return List.of(TextBlock.builder().text("").build());
        }

        List<ContentBlock> blocks = new ArrayList<>();

        for (McpSchema.Content content : mcpContents) {
            ContentBlock block = convertContent(content);
            if (block != null) {
                blocks.add(block);
            }
        }

        return blocks.isEmpty() ? List.of(TextBlock.builder().text("").build()) : blocks;
    }

    /**
     * Converts a single MCP Content to an AgentScope ContentBlock.
     *
     * @param content the MCP content
     * @return the converted ContentBlock, or null if conversion is not supported
     */
    public static ContentBlock convertContent(McpSchema.Content content) {
        if (content == null) {
            return null;
        }

        if (content instanceof McpSchema.TextContent textContent) {
            return convertTextContent(textContent);
        } else if (content instanceof McpSchema.ImageContent imageContent) {
            return convertImageContent(imageContent);
        } else if (content instanceof McpSchema.AudioContent audioContent) {
            // Audio content not yet supported in AgentScope ContentBlock
            return TextBlock.builder()
                    .text("[Audio content: " + audioContent.mimeType() + "]")
                    .build();
        } else if (content instanceof McpSchema.EmbeddedResource embeddedResource) {
            return convertEmbeddedResource(embeddedResource);
        }

        return TextBlock.builder()
                .text("[Unsupported content type: " + content.getClass().getSimpleName() + "]")
                .build();
    }

    /**
     * Converts MCP TextContent to AgentScope TextBlock.
     *
     * @param textContent the MCP text content
     * @return the converted TextBlock
     */
    private static TextBlock convertTextContent(McpSchema.TextContent textContent) {
        return TextBlock.builder()
                .text(textContent.text() != null ? textContent.text() : "")
                .build();
    }

    /**
     * Converts MCP ImageContent to AgentScope ImageBlock.
     *
     * @param imageContent the MCP image content
     * @return the converted ImageBlock
     */
    private static ImageBlock convertImageContent(McpSchema.ImageContent imageContent) {
        String base64Data = imageContent.data();
        String mimeType = imageContent.mimeType();

        if (base64Data == null || base64Data.isEmpty()) {
            return null;
        }

        Base64Source source = new Base64Source(mimeType, base64Data);
        return new ImageBlock(source);
    }

    /**
     * Converts MCP EmbeddedResource to AgentScope ContentBlock.
     *
     * @param embeddedResource the MCP embedded resource
     * @return the converted ContentBlock
     */
    private static ContentBlock convertEmbeddedResource(
            McpSchema.EmbeddedResource embeddedResource) {
        McpSchema.ResourceContents resource = embeddedResource.resource();

        if (resource instanceof McpSchema.TextResourceContents textResource) {
            return TextBlock.builder().text(textResource.text()).build();
        } else if (resource instanceof McpSchema.BlobResourceContents blobResource) {
            // Convert blob to image if mime type indicates image
            String mimeType = blobResource.mimeType();
            if (mimeType != null && mimeType.startsWith("image/")) {
                Base64Source source = new Base64Source(mimeType, blobResource.blob());
                return new ImageBlock(source);
            } else {
                return TextBlock.builder()
                        .text(
                                "[Binary resource: "
                                        + mimeType
                                        + ", URI: "
                                        + blobResource.uri()
                                        + "]")
                        .build();
            }
        }

        return TextBlock.builder().text("[Unknown resource type]").build();
    }

    /**
     * Extracts error message from MCP content list.
     *
     * @param contents the list of MCP content
     * @return the extracted error message
     */
    private static String extractErrorMessage(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "Unknown error";
        }

        return contents.stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }
}
