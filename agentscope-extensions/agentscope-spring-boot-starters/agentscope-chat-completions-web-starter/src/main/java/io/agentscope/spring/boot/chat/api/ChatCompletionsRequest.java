package io.agentscope.spring.boot.chat.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Request payload for the Chat Completions HTTP API. */
public class ChatCompletionsRequest {

    /** Optional model name override; if null, uses the default configured model. */
    private String model;

    /** Conversation history, in order. The last user message is typically the question. */
    @NotEmpty(message = "messages cannot be empty")
    @Valid
    private List<ChatMessage> messages;

    /** Optional session identifier for stateful conversations. */
    private String sessionId;

    /** Whether to stream responses via Server-Sent Events (SSE). */
    private Boolean stream;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }
}
