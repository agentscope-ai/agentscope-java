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
package io.agentscope.core.responses.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.responses.converter.ResponsesValidationException;
import io.agentscope.core.responses.model.ResponsesError;
import io.agentscope.core.responses.model.ResponsesErrorResponse;
import io.agentscope.core.responses.model.ResponsesOutputItem;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesResponse;
import io.agentscope.core.responses.model.ResponsesTextConfig;
import io.agentscope.core.responses.model.ResponsesUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds Responses API response objects from AgentScope messages. */
public class ResponsesResponseBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Build a completed non-streaming Responses object from the final assistant message.
     *
     * @param request Original Responses request
     * @param reply Final AgentScope assistant message
     * @param responseId Response ID to expose to the client
     * @return Completed Responses API response
     */
    public ResponsesResponse buildResponse(ResponsesRequest request, Msg reply, String responseId) {
        ResponsesResponse response = baseResponse(request, responseId, "completed");
        List<ResponsesOutputItem> output = outputItems(reply);
        response.setOutput(output);
        response.setOutputText(outputText(output));
        response.setUsage(usage(reply));
        return response;
    }

    /**
     * Build a completed Responses object for {@code text.format.type=json_schema}.
     *
     * <p>AgentScope stores structured output separately from text content, while the Responses API
     * returns the structured payload as an output text part. This method bridges that shape.
     *
     * @param request Original Responses request
     * @param reply Final AgentScope assistant message containing structured data
     * @param responseId Response ID to expose to the client
     * @return Completed or failed Responses API response
     */
    public ResponsesResponse buildStructuredResponse(
            ResponsesRequest request, Msg reply, String responseId) {
        ResponsesResponse response = baseResponse(request, responseId, "completed");
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(reply.getStructuredData(false));
        } catch (Exception e) {
            return buildFailedResponse(
                    request,
                    ResponsesError.invalidRequest(
                            "Structured output was requested but no structured data was returned",
                            "text.format.schema",
                            "invalid_response"),
                    responseId);
        }
        List<ResponsesOutputItem> output =
                List.of(ResponsesOutputItem.message(messageId(responseId), json));
        response.setOutput(output);
        response.setOutputText(json);
        response.setUsage(usage(reply));
        return response;
    }

    /**
     * Build the terminal {@code response.completed} payload for streaming requests.
     *
     * @param request Original Responses request
     * @param responseId Response ID shared by the stream
     * @param output Output items accumulated from streamed events
     * @param outputText Concatenated assistant text
     * @param reply Terminal AgentScope message, used for token usage if present
     * @return Completed Responses API response
     */
    public ResponsesResponse buildStreamingCompletedResponse(
            ResponsesRequest request,
            String responseId,
            List<ResponsesOutputItem> output,
            String outputText,
            Msg reply) {
        ResponsesResponse response = baseResponse(request, responseId, "completed");
        response.setOutput(output != null ? output : List.of());
        response.setOutputText(outputText != null ? outputText : "");
        response.setUsage(usage(reply));
        return response;
    }

    /**
     * Build a failed response from an arbitrary runtime error.
     *
     * @param request Original Responses request
     * @param error Runtime error
     * @param responseId Response ID to expose to the client
     * @return Failed Responses API response
     */
    public ResponsesResponse buildFailedResponse(
            ResponsesRequest request, Throwable error, String responseId) {
        String message = error != null ? error.getMessage() : "Unknown error occurred";
        return buildFailedResponse(
                request, ResponsesError.invalidRequest(message, null, "runtime_error"), responseId);
    }

    /**
     * Build a failed response from a pre-shaped Responses error payload.
     *
     * @param request Original Responses request
     * @param error Responses API error metadata
     * @param responseId Response ID to expose to the client
     * @return Failed Responses API response
     */
    public ResponsesResponse buildFailedResponse(
            ResponsesRequest request, ResponsesError error, String responseId) {
        ResponsesResponse response = baseResponse(request, responseId, "failed");
        response.setError(error);
        response.setOutput(List.of());
        response.setOutputText("");
        return response;
    }

    /**
     * Build the standard HTTP error wrapper for validation failures.
     *
     * @param error Validation exception with Responses-style metadata
     * @return Error wrapper suitable for a 4xx response body
     */
    public ResponsesErrorResponse buildErrorResponse(ResponsesValidationException error) {
        return new ResponsesErrorResponse(
                ResponsesError.invalidRequest(
                        error.getMessage(), error.getParam(), error.getCode()));
    }

    /**
     * Build the standard HTTP error wrapper for generic invalid requests.
     *
     * @param error Runtime error
     * @return Error wrapper suitable for a 400 response body
     */
    public ResponsesErrorResponse buildInvalidRequestError(Throwable error) {
        String message = error != null ? error.getMessage() : "Invalid request";
        return new ResponsesErrorResponse(
                ResponsesError.invalidRequest(message, null, "invalid_request"));
    }

    /**
     * Create a base response object with request metadata copied into the public response shape.
     *
     * @param request Original Responses request
     * @param responseId Response ID to expose to the client
     * @param status Responses lifecycle status
     * @return Base Responses API response
     */
    public ResponsesResponse baseResponse(
            ResponsesRequest request, String responseId, String status) {
        ResponsesResponse response = new ResponsesResponse();
        response.setId(responseId);
        response.setObject("response");
        response.setCreatedAt(Instant.now().getEpochSecond());
        response.setStatus(status);
        response.setModel(request != null ? request.getModel() : null);
        response.setInstructions(request != null ? request.getInstructions() : null);
        response.setStore(request == null || !Boolean.FALSE.equals(request.getStore()));
        response.setPreviousResponseId(request != null ? request.getPreviousResponseId() : null);
        response.setConversation(request != null ? request.getConversation() : null);
        response.setBackground(request != null ? request.getBackground() : null);
        response.setMetadata(request != null ? request.getMetadata() : null);
        response.setText(textConfig(request));
        response.setToolChoice(
                request != null && request.getToolChoice() != null
                        ? request.getToolChoice()
                        : "auto");
        response.setTools(request != null ? request.getTools() : null);
        return response;
    }

    private ResponsesTextConfig textConfig(ResponsesRequest request) {
        if (request != null && request.getText() != null) {
            return request.getText();
        }
        ResponsesTextConfig config = new ResponsesTextConfig();
        ResponsesTextConfig.Format format = new ResponsesTextConfig.Format();
        format.setType("text");
        config.setFormat(format);
        return config;
    }

    private List<ResponsesOutputItem> outputItems(Msg reply) {
        if (reply == null || reply.getContent() == null) {
            return List.of(ResponsesOutputItem.message("msg_empty", ""));
        }

        // A single AgentScope assistant message can contain both visible text and tool-use blocks.
        // Responses represents those as separate output items, so keep tool calls as siblings.
        List<ResponsesOutputItem> items = new ArrayList<>();
        for (ContentBlock block : reply.getContent()) {
            if (block instanceof ToolUseBlock toolUseBlock) {
                items.add(
                        ResponsesOutputItem.functionCall(
                                functionCallId(toolUseBlock.getId()),
                                toolUseBlock.getId(),
                                toolUseBlock.getName(),
                                argumentsJson(toolUseBlock)));
            }
        }

        String text = text(reply);
        if (!text.isEmpty() || items.isEmpty()) {
            items.add(0, ResponsesOutputItem.message(messageId(reply.getId()), text));
        }
        return items;
    }

    private String text(Msg reply) {
        if (reply == null || reply.getContent() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : reply.getContent()) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                builder.append(textBlock.getText());
            }
        }
        return builder.toString();
    }

    private String outputText(List<ResponsesOutputItem> output) {
        StringBuilder builder = new StringBuilder();
        for (ResponsesOutputItem item : output) {
            if (!"message".equals(item.getType()) || item.getContent() == null) {
                continue;
            }
            item.getContent().stream()
                    .filter(part -> "output_text".equals(part.getType()))
                    .map(part -> part.getText() != null ? part.getText() : "")
                    .forEach(builder::append);
        }
        return builder.toString();
    }

    private ResponsesUsage usage(Msg reply) {
        if (reply == null) {
            return null;
        }
        ChatUsage usage = reply.getChatUsage();
        if (usage == null) {
            return null;
        }
        return new ResponsesUsage(
                usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
    }

    private String argumentsJson(ToolUseBlock block) {
        // Providers differ on where they put tool arguments. Prefer the raw content string when it
        // exists because it often preserves exactly what the model streamed.
        if (block.getContent() != null && !block.getContent().isBlank()) {
            return compactJson(block.getContent());
        }
        Map<String, Object> input = block.getInput();
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String compactJson(String json) {
        try {
            return OBJECT_MAPPER.writeValueAsString(OBJECT_MAPPER.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    private String messageId(String seed) {
        return seed != null && seed.startsWith("msg_") ? seed : "msg_" + normalize(seed);
    }

    private String functionCallId(String seed) {
        return seed != null && seed.startsWith("fc_") ? seed : "fc_" + normalize(seed);
    }

    private String normalize(String seed) {
        return seed == null || seed.isBlank() ? java.util.UUID.randomUUID().toString() : seed;
    }
}
