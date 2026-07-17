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

import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.DataPart;

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

    @Override
    public ContentBlock parse(DataPart part) {
        if (isCommonDataPart(part)) {
            return parseToTextBlock(part);
        }
        return parseToToolBlock(part);
    }

    private boolean isCommonDataPart(DataPart part) {
        if (null == part.metadata()) {
            return true;
        }
        return null == part.metadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY);
    }

    private ContentBlock parseToTextBlock(DataPart part) {
        String dataJsonString = String.valueOf(part.data());
        return TextBlock.builder().text(dataJsonString).build();
    }

    private ContentBlock parseToToolBlock(DataPart part) {
        // value has checked existed in isCommonDataPart().
        String blockType = part.metadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY).toString();
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
        Object input = part.data();
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> safeInput = new HashMap<>();
            map.forEach((key, value) -> safeInput.put(String.valueOf(key), value));
            builder.input(safeInput);
        } else {
            builder.input(Map.of());
        }
        return builder.build();
    }

    private ContentBlock parseToToolResultBlock(DataPart part) {
        ToolResultBlock.Builder builder = ToolResultBlock.builder();
        builder.id(getToolCallId(part)).name(getToolName(part));
        builder.metadata(getOriginalMetadata(part));
        builder.state(getToolResultState(part));
        Object data = part.data();
        Object output =
                data instanceof Map<?, ?> map
                        ? map.get(MessageConstants.TOOL_RESULT_OUTPUT_METADATA_KEY)
                        : null;
        List<?> values;
        if (output == null) {
            values = List.of();
        } else if (output instanceof List<?> list) {
            values = list;
        } else {
            values = List.of(output);
        }
        List<ContentBlock> outputBlocks = new ArrayList<>();
        for (Object value : values) {
            ContentBlock block = MessageConvertUtil.contentBlockFromProtobufSafeValue(value);
            if (block != null) {
                outputBlocks.add(block);
            }
        }
        builder.output(outputBlocks);
        return builder.build();
    }

    private ToolResultState getToolResultState(DataPart part) {
        Object state = part.metadata().get(MessageConstants.TOOL_RESULT_STATE_METADATA_KEY);
        if (state == null) {
            return ToolResultState.RUNNING;
        }
        String value = state.toString();
        for (ToolResultState candidate : ToolResultState.values()) {
            if (candidate.getValue().equalsIgnoreCase(value)
                    || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return ToolResultState.RUNNING;
    }

    private String getToolCallId(DataPart part) {
        Object toolCallId = part.metadata().get(MessageConstants.TOOL_CALL_ID_METADATA_KEY);
        return null != toolCallId ? toolCallId.toString() : null;
    }

    private String getToolName(DataPart part) {
        Object toolName = part.metadata().get(MessageConstants.TOOL_NAME_METADATA_KEY);
        return null != toolName ? toolName.toString() : null;
    }

    private Map<String, Object> getOriginalMetadata(DataPart part) {
        Map<String, Object> result =
                new HashMap<>(MessageConvertUtil.stripSensitiveMetadata(part.metadata()));
        // Remove agentscope inner metadata.
        result.remove(MessageConstants.TOOL_CALL_ID_METADATA_KEY);
        result.remove(MessageConstants.TOOL_NAME_METADATA_KEY);
        result.remove(MessageConstants.BLOCK_TYPE_METADATA_KEY);
        result.remove(MessageConstants.TOOL_RESULT_STATE_METADATA_KEY);
        return result;
    }
}
