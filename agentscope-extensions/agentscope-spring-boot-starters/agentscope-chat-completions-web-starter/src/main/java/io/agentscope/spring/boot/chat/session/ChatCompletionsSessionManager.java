package io.agentscope.spring.boot.chat.session;

import io.agentscope.core.ReActAgent;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Simple SPI for managing session-scoped ReActAgent instances.
 *
 * <p>MVP implementation is in-memory only and not intended for production use.
 */
public interface ChatCompletionsSessionManager {

    /**
     * Get or create a ReActAgent for the given session id.
     *
     * @param sessionId session identifier; may be null to indicate a stateless request
     * @param agentProvider provider used to lazily create new ReActAgent instances
     * @return a ReActAgent instance (new or existing)
     */
    ReActAgent getOrCreateAgent(String sessionId, ObjectProvider<ReActAgent> agentProvider);
}
