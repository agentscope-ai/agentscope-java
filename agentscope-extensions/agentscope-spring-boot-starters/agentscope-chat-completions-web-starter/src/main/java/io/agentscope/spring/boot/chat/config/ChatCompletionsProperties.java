package io.agentscope.spring.boot.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Chat Completions Web Starter.
 *
 * <p>Example configuration:
 *
 * <pre>{@code
 * agentscope:
 *   chat-completions:
 *     enabled: true
 *     base-path: /v1/chat/completions
 * }</pre>
 */
@ConfigurationProperties(prefix = "agentscope.chat-completions")
public class ChatCompletionsProperties {

    /** Whether the chat completions HTTP API is enabled. */
    private boolean enabled = true;

    /** Base path for the chat completions endpoint. */
    private String basePath = "/v1/chat/completions";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
