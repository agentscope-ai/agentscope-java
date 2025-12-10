/*
 * Copyright 1999-2025 Alibaba Group Holding Ltd.
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

package io.agentscope.core.a2a.agent.message;

import io.a2a.spec.TextPart;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;

/**
 * Parser for {@link TextPart} to {@link TextBlock} or
 * {@link ThinkingBlock}.
 *
 * @author xiweng.yy
 */
public class TextPartParser implements PartParser<TextPart> {

    @Override
    public ContentBlock parse(TextPart part) {
        if (isThinkingBlock(part)) {
            ThinkingBlock.builder().thinking(part.getText()).build();
        }
        return TextBlock.builder().text(part.getText()).build();
    }

    private boolean isThinkingBlock(TextPart part) {
        if (null == part.getMetadata()) {
            return true;
        }
        if (part.getMetadata().isEmpty()
                || !part.getMetadata().containsKey(MessageConvertUtil.BLOCK_TYPE_METADATA_KEY)) {
            return true;
        }
        return MessageConstants.BlockContent.TYPE_THINKING.equals(
                part.getMetadata().get(MessageConvertUtil.BLOCK_TYPE_METADATA_KEY));
    }
}
