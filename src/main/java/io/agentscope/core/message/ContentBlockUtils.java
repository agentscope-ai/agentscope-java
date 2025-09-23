/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.message;

/**
 * Utility class for handling ContentBlock operations.
 */
public class ContentBlockUtils {

    /**
     * Extract text content from a ContentBlock.
     * @param block the content block
     * @return text content or empty string if not available
     */
    public static String extractTextContent(ContentBlock block) {
        if (block == null) {
            return "";
        }

        switch (block.getType()) {
            case TEXT:
                return ((TextBlock) block).getText();
            case THINKING:
                return ((ThinkingBlock) block).getThinking();
            default:
                return "";
        }
    }

    /**
     * Check if a ContentBlock contains text content.
     * @param block the content block
     * @return true if the block contains text content
     */
    public static boolean hasTextContent(ContentBlock block) {
        if (block == null) {
            return false;
        }
        return block.getType() == ContentBlockType.TEXT
                || block.getType() == ContentBlockType.THINKING;
    }

    /**
     * Check if a ContentBlock contains media content.
     * @param block the content block
     * @return true if the block contains media content
     */
    public static boolean hasMediaContent(ContentBlock block) {
        if (block == null) {
            return false;
        }
        ContentBlockType type = block.getType();
        return type == ContentBlockType.IMAGE
                || type == ContentBlockType.AUDIO
                || type == ContentBlockType.VIDEO;
    }

    /**
     * Get media information from a ContentBlock.
     * @param block the content block
     * @return MediaInfo object or null if not media content
     */
    public static MediaInfo getMediaInfo(ContentBlock block) {
        if (block == null || !hasMediaContent(block)) {
            return null;
        }

        Source source = getMediaSource(block);
        if (source == null) {
            return null;
        }

        String mimeType = determineMimeType(block.getType(), source);

        if (source instanceof URLSource) {
            URLSource urlSource = (URLSource) source;
            return new MediaInfo(mimeType, urlSource.getUrl(), MediaInfo.SourceType.URL);
        } else if (source instanceof Base64Source) {
            Base64Source base64Source = (Base64Source) source;
            String dataUrl =
                    "data:" + base64Source.getMediaType() + ";base64," + base64Source.getData();
            return new MediaInfo(base64Source.getMediaType(), dataUrl, MediaInfo.SourceType.BASE64);
        }

        return null;
    }

    /**
     * Get media source from a ContentBlock.
     * @param block the content block
     * @return media source or null if not available
     */
    public static Source getMediaSource(ContentBlock block) {
        if (block == null) {
            return null;
        }

        switch (block.getType()) {
            case IMAGE:
                return ((ImageBlock) block).getSource();
            case AUDIO:
                return ((AudioBlock) block).getSource();
            case VIDEO:
                return ((VideoBlock) block).getSource();
            default:
                return null;
        }
    }

    /**
     * Determine MIME type based on content block type and source.
     * @param blockType the content block type
     * @param source the media source
     * @return appropriate MIME type
     */
    private static String determineMimeType(ContentBlockType blockType, Source source) {
        // If source is Base64Source, use its mediaType
        if (source instanceof Base64Source) {
            Base64Source base64Source = (Base64Source) source;
            String mediaType = base64Source.getMediaType();
            if (mediaType != null && !mediaType.isEmpty()) {
                return mediaType;
            }
        }

        // Default MIME types based on block type
        switch (blockType) {
            case IMAGE:
                return "image/jpeg"; // Default image type
            case AUDIO:
                return "audio/mpeg"; // Default audio type
            case VIDEO:
                return "video/mp4"; // Default video type
            default:
                return "application/octet-stream";
        }
    }

    /**
     * Create a text description of media content for text-only contexts.
     * @param block the content block
     * @return text description of the media
     */
    public static String createMediaDescription(ContentBlock block) {
        if (block == null || !hasMediaContent(block)) {
            return "";
        }

        Source source = getMediaSource(block);
        String sourceInfo = "";

        if (source instanceof URLSource) {
            sourceInfo = " from URL: " + ((URLSource) source).getUrl();
        } else if (source instanceof Base64Source) {
            Base64Source base64Source = (Base64Source) source;
            sourceInfo = " (base64 encoded, type: " + base64Source.getMediaType() + ")";
        }

        switch (block.getType()) {
            case IMAGE:
                return "[Image content" + sourceInfo + "]";
            case AUDIO:
                return "[Audio content" + sourceInfo + "]";
            case VIDEO:
                return "[Video content" + sourceInfo + "]";
            default:
                return "[Media content" + sourceInfo + "]";
        }
    }

    /**
     * Convert ContentBlock to a comprehensive text representation.
     * @param block the content block
     * @return text representation including both text and media descriptions
     */
    public static String toTextRepresentation(ContentBlock block) {
        if (block == null) {
            return "";
        }

        if (hasTextContent(block)) {
            return extractTextContent(block);
        } else if (hasMediaContent(block)) {
            return createMediaDescription(block);
        } else {
            return "";
        }
    }
}
