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
import io.a2a.spec.Part;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.message.ToolResultBlock;
import java.util.Map;

/**
 * Parser for {@link ToolResultBlock} to {@link DataPart}.
 */
public class ToolResultBlockParser implements ContentBlockParser<ToolResultBlock> {

    @Override
    public Part<?> parse(ToolResultBlock contentBlock) {
        Map<String, Object> metadata =
                MessageConvertUtil.buildTypeMetadata(
                        MessageConstants.BlockContent.TYPE_TOOL_RESULT);
        metadata.put(MessageConstants.TOOL_NAME_METADATA_KEY, contentBlock.getName());
        metadata.put(MessageConstants.TOOL_CALL_ID_METADATA_KEY, contentBlock.getId());
        metadata.putAll(contentBlock.getMetadata());
        Map<String, Object> output =
                Map.of(MessageConstants.TOOL_RESULT_OUTPUT_METADATA_KEY, contentBlock.getOutput());
        return new DataPart(output, metadata);
    }
}
