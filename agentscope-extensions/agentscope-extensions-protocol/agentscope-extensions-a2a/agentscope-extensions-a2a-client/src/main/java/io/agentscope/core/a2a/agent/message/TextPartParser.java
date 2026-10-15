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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import org.a2aproject.sdk.spec.TextPart;

/**
 * Parser for {@link TextPart} to {@link TextBlock} or
 * {@link ThinkingBlock}.
 */
public class TextPartParser implements PartParser<TextPart> {

    @Override
    public ContentBlock parse(TextPart part) {
        String blockType = getMetadataValue(part, MessageConstants.BLOCK_TYPE_METADATA_KEY);
        if (MessageConstants.BlockContent.TYPE_THINKING.equals(blockType)) {
            return ThinkingBlock.builder().thinking(part.text()).build();
        }
        if (MessageConstants.BlockContent.TYPE_HINT.equals(blockType)) {
            return new HintBlock(
                    getMetadataValue(part, MessageConstants.HINT_ID_METADATA_KEY),
                    part.text(),
                    getMetadataValue(part, MessageConstants.HINT_SOURCE_METADATA_KEY));
        }
        return TextBlock.builder().text(part.text()).build();
    }

    private String getMetadataValue(TextPart part, String key) {
        if (part.metadata() == null) {
            return null;
        }
        Object value = part.metadata().get(key);
        return value == null ? null : value.toString();
    }
}
