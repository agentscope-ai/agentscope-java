package io.agentscope.spring.boot.chat.api;

/**
 * Simplified chat message representation for the Chat Completions API.
 *
 * <p>This DTO intentionally mirrors the common shape used by OpenAI / Spring AI:
 *
 * <pre>{@code
 * {
 *   "role": "user",
 *   "content": "Hello"
 * }
 * }</pre>
 */
public class ChatMessage {

    private String role;

    private String content;

    public ChatMessage() {}

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
