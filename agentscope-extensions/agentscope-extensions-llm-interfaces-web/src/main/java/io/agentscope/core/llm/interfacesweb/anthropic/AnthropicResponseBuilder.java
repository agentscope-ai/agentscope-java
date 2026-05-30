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
package io.agentscope.core.llm.interfacesweb.anthropic;

import io.agentscope.core.llm.interfacesweb.common.ProtocolMessageUtils;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import java.util.Map;

/** Builds Anthropic Messages-compatible response payloads. */
public class AnthropicResponseBuilder {

    public AnthropicMessagesResponse buildResponse(
            AnthropicMessagesRequest request, Msg reply, String messageId) {
        AnthropicMessagesResponse response = baseResponse(request, messageId);
        response.setContent(ProtocolMessageUtils.contentParts(reply, true));
        response.setStopReason(stopReason(reply));
        response.setUsage(usage(reply));
        return response;
    }

    public Map<String, Object> buildError(Throwable error) {
        return Map.of(
                "type",
                "error",
                "error",
                Map.of(
                        "type",
                        "api_error",
                        "message",
                        error != null ? error.getMessage() : "Unknown error occurred"));
    }

    public AnthropicMessagesResponse baseResponse(
            AnthropicMessagesRequest request, String messageId) {
        AnthropicMessagesResponse response = new AnthropicMessagesResponse();
        response.setId(messageId);
        response.setModel(request != null ? request.getModel() : null);
        response.setContent(java.util.List.of());
        response.setStopReason(null);
        response.setUsage(new AnthropicUsage(0, 0));
        return response;
    }

    public String stopReason(Msg reply) {
        if (reply == null) {
            return "end_turn";
        }
        if (reply.getGenerateReason() == GenerateReason.MAX_ITERATIONS) {
            return "max_tokens";
        }
        if (reply.getGenerateReason() == GenerateReason.TOOL_SUSPENDED
                || reply.hasContentBlocks(io.agentscope.core.message.ToolUseBlock.class)) {
            return "tool_use";
        }
        return "end_turn";
    }

    private AnthropicUsage usage(Msg reply) {
        if (reply == null || reply.getChatUsage() == null) {
            return null;
        }
        ChatUsage usage = reply.getChatUsage();
        return new AnthropicUsage(usage.getInputTokens(), usage.getOutputTokens());
    }
}
