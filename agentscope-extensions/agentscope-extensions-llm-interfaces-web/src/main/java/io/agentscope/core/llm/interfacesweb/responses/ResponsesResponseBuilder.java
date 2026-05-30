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
package io.agentscope.core.llm.interfacesweb.responses;

import io.agentscope.core.llm.interfacesweb.common.ProtocolJsonUtils;
import io.agentscope.core.llm.interfacesweb.common.ProtocolMessageUtils;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds stateless OpenAI Responses-compatible response payloads. */
public class ResponsesResponseBuilder {

    public ResponsesResponse buildResponse(ResponsesRequest request, Msg reply, String responseId) {
        ResponsesResponse response = baseResponse(request, responseId, "completed");
        List<ResponsesOutputItem> output = outputItems(reply, responseId);
        response.setOutput(output);
        response.setOutputText(ProtocolMessageUtils.textContent(reply));
        response.setUsage(usage(reply));

        if (reply != null && reply.getGenerateReason() == GenerateReason.MAX_ITERATIONS) {
            response.setStatus("incomplete");
        }
        return response;
    }

    public ResponsesResponse buildErrorResponse(
            ResponsesRequest request, Throwable error, String responseId) {
        ResponsesResponse response = baseResponse(request, responseId, "failed");
        String message = error != null ? error.getMessage() : "Unknown error occurred";
        response.setError(Map.of("type", "server_error", "message", message));
        response.setOutput(List.of());
        response.setOutputText("");
        return response;
    }

    public ResponsesResponse baseResponse(
            ResponsesRequest request, String responseId, String status) {
        ResponsesResponse response = new ResponsesResponse();
        response.setId(responseId);
        response.setCreatedAt(Instant.now().getEpochSecond());
        response.setModel(request != null ? request.getModel() : null);
        response.setStatus(status);
        return response;
    }

    public List<ResponsesOutputItem> outputItems(Msg reply, String responseId) {
        List<ResponsesOutputItem> items = new ArrayList<>();
        if (reply == null || reply.getContent() == null) {
            return items;
        }

        String text = ProtocolMessageUtils.textContent(reply);
        if (!text.isEmpty()) {
            ResponsesOutputItem message = new ResponsesOutputItem();
            message.setId("msg_" + responseId);
            message.setType("message");
            message.setStatus("completed");
            message.setRole("assistant");
            message.setContent(List.of(new ResponsesOutputContent("output_text", text)));
            items.add(message);
        }

        for (ContentBlock block : reply.getContent()) {
            if (block instanceof ToolUseBlock toolUseBlock) {
                items.add(toolUseItem(toolUseBlock));
            } else if (!(block instanceof TextBlock)) {
                String itemText =
                        ProtocolJsonUtils.toJson(ProtocolMessageUtils.contentParts(reply, false));
                if (!itemText.equals("[]") && text.isEmpty()) {
                    ResponsesOutputItem message = new ResponsesOutputItem();
                    message.setId("msg_" + responseId);
                    message.setType("message");
                    message.setStatus("completed");
                    message.setRole("assistant");
                    message.setContent(
                            List.of(new ResponsesOutputContent("output_text", itemText)));
                    items.add(message);
                    break;
                }
            }
        }
        return items;
    }

    public ResponsesOutputItem toolUseItem(ToolUseBlock block) {
        ResponsesOutputItem item = new ResponsesOutputItem();
        item.setId(block.getId());
        item.setType("function_call");
        item.setStatus("completed");
        item.setCallId(block.getId());
        item.setName(block.getName());
        String arguments =
                block.getContent() != null && !block.getContent().isBlank()
                        ? block.getContent()
                        : ProtocolJsonUtils.toJson(block.getInput());
        item.setArguments(arguments);
        return item;
    }

    private ResponsesUsage usage(Msg reply) {
        if (reply == null || reply.getChatUsage() == null) {
            return null;
        }
        ChatUsage usage = reply.getChatUsage();
        return new ResponsesUsage(usage.getInputTokens(), usage.getOutputTokens());
    }
}
