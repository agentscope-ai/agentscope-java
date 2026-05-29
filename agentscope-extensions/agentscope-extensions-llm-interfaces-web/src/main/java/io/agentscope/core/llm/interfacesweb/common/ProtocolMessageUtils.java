/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.llm.interfacesweb.common;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Helpers for converting AgentScope messages to wire-protocol payloads. */
public final class ProtocolMessageUtils {

    private ProtocolMessageUtils() {}

    public static Msg textMessage(MsgRole role, String text) {
        return Msg.builder()
                .role(role)
                .content(TextBlock.builder().text(text != null ? text : "").build())
                .build();
    }

    public static Msg message(MsgRole role, List<ContentBlock> content) {
        return Msg.builder().role(role).content(content.toArray(new ContentBlock[0])).build();
    }

    public static String textContent(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        return msg.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
    }

    public static List<Map<String, Object>> contentParts(Msg msg, boolean anthropicShape) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (msg == null || msg.getContent() == null) {
            return parts;
        }
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock) {
                parts.add(Map.of("type", "text", "text", textBlock.getText()));
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                parts.add(Map.of("type", "thinking", "thinking", thinkingBlock.getThinking()));
            } else if (block instanceof ImageBlock imageBlock) {
                parts.add(imagePart(imageBlock, anthropicShape));
            } else if (block instanceof ToolUseBlock toolUseBlock) {
                parts.add(toolUsePart(toolUseBlock));
            } else if (block instanceof ToolResultBlock toolResultBlock) {
                parts.add(toolResultPart(toolResultBlock));
            }
        }
        return parts;
    }

    public static Map<String, Object> toolUsePart(ToolUseBlock block) {
        return Map.of(
                "type",
                "tool_use",
                "id",
                block.getId(),
                "name",
                block.getName(),
                "input",
                block.getInput() != null ? block.getInput() : Map.of());
    }

    public static Map<String, Object> toolResultPart(ToolResultBlock block) {
        return Map.of(
                "type",
                "tool_result",
                "tool_use_id",
                block.getId(),
                "content",
                toolResultText(block));
    }

    public static String toolResultText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "";
        }
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
    }

    private static Map<String, Object> imagePart(ImageBlock block, boolean anthropicShape) {
        if (block.getSource() instanceof URLSource urlSource) {
            if (anthropicShape) {
                return Map.of(
                        "type",
                        "image",
                        "source",
                        Map.of("type", "url", "url", urlSource.getUrl()));
            }
            return Map.of("type", "input_image", "image_url", urlSource.getUrl());
        }
        if (block.getSource() instanceof Base64Source base64Source) {
            if (anthropicShape) {
                return Map.of(
                        "type",
                        "image",
                        "source",
                        Map.of(
                                "type",
                                "base64",
                                "media_type",
                                base64Source.getMediaType(),
                                "data",
                                base64Source.getData()));
            }
            return Map.of(
                    "type",
                    "input_image",
                    "image_url",
                    "data:" + base64Source.getMediaType() + ";base64," + base64Source.getData());
        }
        return Map.of("type", "text", "text", "[Unsupported image source]");
    }
}
