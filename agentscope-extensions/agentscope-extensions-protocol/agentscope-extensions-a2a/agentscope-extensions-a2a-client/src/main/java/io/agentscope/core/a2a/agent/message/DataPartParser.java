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

package io.agentscope.core.a2a.agent.message;

import io.a2a.spec.DataPart;
import io.a2a.util.Utils;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for {@link DataPart} to {@link ContentBlock}.
 *
 * <p>According to the metadata, the parser will convert the {@link DataPart} to different {@link ContentBlock}:
 * <ul>
 *     <li>{@link MessageConstants#BLOCK_TYPE_METADATA_KEY} is {@link MessageConstants.BlockContent#TYPE_TOOL_USE}, parse to {@link ToolUseBlock}</li>
 *     <li>{@link MessageConstants#BLOCK_TYPE_METADATA_KEY} is {@link MessageConstants.BlockContent#TYPE_TOOL_RESULT}, parse to {@link ToolResultBlock}</li>
 *     <li>Without {@link MessageConstants#BLOCK_TYPE_METADATA_KEY}, parse to {@link TextBlock}</li>
 * </ul>
 */
public class DataPartParser implements PartParser<DataPart> {

    private static final Logger log = LoggerFactory.getLogger(DataPartParser.class);

    @Override
    public ContentBlock parse(DataPart part) {
        if (isCommonDataPart(part)) {
            return parseToTextBlock(part);
        }
        return parseToToolBlock(part);
    }

    private boolean isCommonDataPart(DataPart part) {
        if (null == part.getMetadata()) {
            return true;
        }
        return null == part.getMetadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY);
    }

    private ContentBlock parseToTextBlock(DataPart part) {
        String dataJsonString = Utils.toJsonString(part.getData());
        return TextBlock.builder().text(dataJsonString).build();
    }

    private ContentBlock parseToToolBlock(DataPart part) {
        // value has checked existed in isCommonDataPart().
        String blockType =
                part.getMetadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY).toString();
        return switch (blockType) {
            case MessageConstants.BlockContent.TYPE_TOOL_USE -> parseToToolUseBlock(part);
            case MessageConstants.BlockContent.TYPE_TOOL_RESULT -> parseToToolResultBlock(part);
            default -> null;
        };
    }

    private ContentBlock parseToToolUseBlock(DataPart part) {
        ToolUseBlock.Builder builder = ToolUseBlock.builder();
        builder.id(getToolCallId(part)).name(getToolName(part));
        builder.metadata(getOriginalMetadata(part));
        builder.input(part.getData());
        return builder.build();
    }

    private ContentBlock parseToToolResultBlock(DataPart part) {
        ToolResultBlock.Builder builder = ToolResultBlock.builder();
        builder.id(getToolCallId(part)).name(getToolName(part));
        builder.metadata(getOriginalMetadata(part));
        Object output = part.getData().get(MessageConstants.TOOL_RESULT_OUTPUT_METADATA_KEY);
        if (output instanceof String) {
            // Adapter Python Agentscope ToolResultBlock define, python tool result output spec is
            // `str | List[TextBlock | ImageBlock | AudioBlock | VideoBlock]`
            builder.output(TextBlock.builder().text(output.toString()).build());
        } else if (output instanceof List<?> rawList) {
            builder.output(toContentBlocks(rawList));
        } else {
            builder.output(List.of());
        }
        return builder.build();
    }

    /**
     * Converts the raw output list to content blocks.
     *
     * <p>After an A2A round trip the items arrive as JSON maps (e.g. {@code {"type": "text", ...}})
     * rather than {@link ContentBlock} instances, so each map is converted through Jackson's
     * polymorphic deserialization based on its {@code type} field. Plain strings are wrapped as
     * {@link TextBlock}s.
     *
     * <p>An item that cannot be converted (e.g. an unknown or missing {@code type}) does not fail
     * the whole tool result: it is kept as a {@link TextBlock} with its raw JSON, and a warning is
     * logged. This is intentional, so that one unexpected block from a remote agent does not
     * discard an otherwise valid result.
     */
    private List<ContentBlock> toContentBlocks(List<?> rawList) {
        List<ContentBlock> contentBlocks = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            if (item instanceof ContentBlock contentBlock) {
                contentBlocks.add(contentBlock);
            } else if (item instanceof CharSequence text) {
                contentBlocks.add(TextBlock.builder().text(text.toString()).build());
            } else {
                contentBlocks.add(convertOnFallBackToText(item));
            }
        }
        return contentBlocks;
    }

    private ContentBlock convertOnFallBackToText(Object item) {
        try {
            return JsonUtils.getJsonCodec().convertValue(item, ContentBlock.class);
        } catch (JsonException e) {
            Object type =
                    item instanceof Map<?, ?> map ? map.get("type") : item.getClass().getName();
            log.warn(
                    "Cannot convert tool result output item with type '{}' to ContentBlock,"
                            + " keeping it as raw JSON text: {}",
                    type,
                    e.getMessage());
            return TextBlock.builder().text(Utils.toJsonString(item)).build();
        }
    }

    private String getToolCallId(DataPart part) {
        Object toolCallId = part.getMetadata().get(MessageConstants.TOOL_CALL_ID_METADATA_KEY);
        return null != toolCallId ? toolCallId.toString() : null;
    }

    private String getToolName(DataPart part) {
        Object toolName = part.getMetadata().get(MessageConstants.TOOL_NAME_METADATA_KEY);
        return null != toolName ? toolName.toString() : null;
    }

    private Map<String, Object> getOriginalMetadata(DataPart part) {
        Map<String, Object> result = new HashMap<>(part.getMetadata());
        // Remove agentscope inner metadata.
        result.remove(MessageConstants.TOOL_CALL_ID_METADATA_KEY);
        result.remove(MessageConstants.TOOL_NAME_METADATA_KEY);
        result.remove(MessageConstants.BLOCK_TYPE_METADATA_KEY);
        return result;
    }
}
