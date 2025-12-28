package io.agentscope.spring.boot.chat.builder;

import io.agentscope.core.message.Msg;
import io.agentscope.spring.boot.chat.api.ChatChoice;
import io.agentscope.spring.boot.chat.api.ChatCompletionsRequest;
import io.agentscope.spring.boot.chat.api.ChatCompletionsResponse;
import io.agentscope.spring.boot.chat.api.ChatMessage;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Service for building Chat Completions API responses.
 *
 * <p>This builder handles the construction of response objects for both successful and error
 * scenarios.
 *
 * <p>This component is automatically discovered by Spring Boot's component scanning.
 */
@Component
public class ChatCompletionsResponseBuilder {

    /**
     * Build a successful chat completion response.
     *
     * @param request The original request
     * @param reply The agent's reply message
     * @param requestId The request ID for tracking
     * @return The chat completion response
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
     * @return An error response
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
     * @return The text content, or empty string if not found
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
