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
package io.agentscope.core.formatter.openai;

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;

/**
 * Formatter for OpenAI Chat Completion HTTP API.
 * Converts between AgentScope Msg objects and OpenAI DTO types.
 *
 * <p>This formatter is used with the HTTP-based OpenAI client instead of the SDK.
 */
public class OpenAIChatFormatter extends OpenAIBaseFormatter {

    public OpenAIChatFormatter() {
        super();
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            boolean hasMedia = hasMediaContent(msg);
            OpenAIMessage openAIMsg = messageConverter.convertToMessage(msg, hasMedia);
            if (openAIMsg != null) {
                result.add(openAIMsg);
            }
        }
        return result;
    }
}
