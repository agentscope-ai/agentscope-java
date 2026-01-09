/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model;

import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.session.LiveSession;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Interface for real-time bidirectional streaming conversation models.
 *
 * <p>This interface is parallel to the existing Model interface but uses a session-based pattern
 * instead of request-response pattern.
 *
 * <p>Design principles:
 *
 * <ul>
 *   <li>No supports*() methods - use LiveFormatter mechanism to adapt provider differences
 *   <li>Unsupported parameters are silently discarded by LiveFormatter
 *   <li>Users don't need to care about provider capability differences
 *   <li>Session creation is implicit - users don't need to manually create sessions
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * LiveModel model = DashScopeLiveModel.builder()
 *     .apiKey("sk-xxx")
 *     .modelName("qwen-omni-turbo-realtime")
 *     .build();
 *
 * model.connect(LiveConfig.builder()
 *         .voice("Cherry")
 *         .instructions("You are a friendly assistant")
 *         .build())
 *     .flatMapMany(session -> {
 *         // Send audio
 *         session.send(audioMsg).subscribe();
 *         // Receive events
 *         return session.receive();
 *     })
 *     .subscribe(event -> handleEvent(event));
 * }</pre>
 *
 * @see LiveSession
 * @see LiveConfig
 */
public interface LiveModel {

    /**
     * Establish a real-time conversation session connection.
     *
     * <p>This method will:
     *
     * <ol>
     *   <li>Establish WebSocket connection
     *   <li>Automatically send session configuration message (transparent to user)
     *   <li>Return LiveSession after session is ready
     * </ol>
     *
     * @param config session configuration
     * @param toolSchemas tool definition list (can be null), used to register tools
     * @return Mono of session object (emitted when session is ready)
     */
    Mono<LiveSession> connect(LiveConfig config, List<ToolSchema> toolSchemas);

    /**
     * Connect with default configuration (no tools).
     *
     * @return Mono of session object
     */
    default Mono<LiveSession> connect() {
        return connect(LiveConfig.defaults(), null);
    }

    /**
     * Connect with specified configuration (no tools).
     *
     * @param config session configuration
     * @return Mono of session object
     */
    default Mono<LiveSession> connect(LiveConfig config) {
        return connect(config, null);
    }

    /**
     * Get model name.
     *
     * @return model name
     */
    String getModelName();

    /**
     * Get provider name.
     *
     * @return provider name (dashscope/openai/gemini/doubao)
     */
    String getProviderName();

    /**
     * Check if native session recovery is supported.
     *
     * <p>Support status:
     *
     * <ul>
     *   <li>Gemini: supports sessionResumption (valid for 2 hours)
     *   <li>Doubao: supports dialog_id (last 20 turns)
     *   <li>DashScope/OpenAI: not supported
     * </ul>
     *
     * @return true if native session recovery is supported
     */
    default boolean supportsNativeRecovery() {
        return false;
    }
}
