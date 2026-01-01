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
package io.agentscope.core.formatter.anthropic;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.List;

/**
 * Formatter for Anthropic Messages API. Converts between AgentScope Msg objects and Anthropic SDK
 * types.
 *
 * <p>Important: Anthropic API has special requirements:
 *
 * <ul>
 *   <li>Only the first message can be a system message (handled via system parameter)
 *   <li>Tool results must be in separate user messages
 *   <li>Supports thinking blocks natively (extended thinking feature)
 * </ul>
 */
public class AnthropicChatFormatter extends AnthropicBaseFormatter {

    @Override
    public List<MessageParam> doFormat(List<Msg> msgs) {
        return messageConverter.convert(msgs);
    }

    @Override
    public ChatResponse parseResponse(Object response, Instant startTime) {
        if (response instanceof Message message) {
            return AnthropicResponseParser.parseMessage(message, startTime);
        } else {
            throw new IllegalArgumentException("Unsupported response type: " + response.getClass());
        }
    }
}
