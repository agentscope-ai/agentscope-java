/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.chat.completions.builder;

import io.agentscope.core.chat.completions.model.ChatChoice;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.message.Msg;
import java.time.Instant;
import java.util.List;

/**
 * Service for building Chat Completions API responses.
 *
 * <p>This builder handles the construction of response objects for both successful and error
 * scenarios.
 */
public class ChatCompletionsResponseBuilder {

    /**
     * Build a successful chat completion response.
     *
     * @param request The original request
     * @param reply The agent's reply message
     * @param requestId The request ID for tracking
     * @return A {@link ChatCompletionsResponse} containing the agent's reply with finish reason
     *     "stop"
     */
    public ChatCompletionsResponse buildResponse(
            ChatCompletionsRequest request, Msg reply, String requestId) {
        ChatCompletionsResponse response = new ChatCompletionsResponse();
        response.setId(requestId);
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(request.getModel());

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);

        String text = extractTextContent(reply);
        ChatMessage message = new ChatMessage("assistant", text);
        choice.setMessage(message);
        choice.setFinishReason("stop");

        response.setChoices(List.of(choice));
        return response;
    }

    /**
     * Build an error response.
     *
     * @param request The original request
     * @param error The error that occurred
     * @param requestId The request ID for tracking
     * @return A {@link ChatCompletionsResponse} containing the error message with finish reason
     *     "error"
     */
    public ChatCompletionsResponse buildErrorResponse(
            ChatCompletionsRequest request, Throwable error, String requestId) {
        ChatCompletionsResponse response = new ChatCompletionsResponse();
        response.setId(requestId);
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(request.getModel());

        ChatChoice choice = new ChatChoice();
        choice.setIndex(0);
        String errorMessage = error != null ? error.getMessage() : "Unknown error occurred";
        ChatMessage message = new ChatMessage("assistant", "Error: " + errorMessage);
        choice.setMessage(message);
        choice.setFinishReason("error");

        response.setChoices(List.of(choice));
        return response;
    }

    /**
     * Extract text content from a Msg safely.
     *
     * @param msg The message to extract text from
     * @return The text content as a {@link String}, or an empty string if the message is null or
     *     contains no text
     */
    public String extractTextContent(Msg msg) {
        if (msg == null) {
            return "";
        }
        // Use safe helper method if available
        String text = msg.getTextContent();
        return text != null ? text : "";
    }
}
