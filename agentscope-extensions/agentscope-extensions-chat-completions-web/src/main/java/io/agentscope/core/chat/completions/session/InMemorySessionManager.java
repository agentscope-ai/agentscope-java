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
package io.agentscope.core.chat.completions.session;

import io.agentscope.core.ReActAgent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Very simple in-memory {@link ChatCompletionsSessionManager} implementation.
 *
 * <p>This is intended for demos and small applications only. For production use, implement a
 * distributed session manager based on Redis or a database.
 */
public class InMemorySessionManager implements ChatCompletionsSessionManager {

    private static final Logger log = LoggerFactory.getLogger(InMemorySessionManager.class);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    @Override
    public ReActAgent getOrCreateAgent(String sessionId, Supplier<ReActAgent> agentSupplier) {
        try {
            String key =
                    (sessionId == null || sessionId.isBlank())
                            ? UUID.randomUUID().toString()
                            : sessionId;

            pruneExpired();

            Entry existing = sessions.get(key);
            if (existing != null && !existing.isExpired()) {
                sessions.put(key, existing.touch());
                log.debug("Reusing existing agent for session: {}", key);
                return existing.agent();
            }

            ReActAgent agent = agentSupplier.get();
            if (agent == null) {
                log.error(
                        "Failed to create ReActAgent: agentSupplier returned null for session: {}",
                        key);
                throw new IllegalStateException(
                        "Failed to create ReActAgent: agentSupplier returned null");
            }

            sessions.put(key, new Entry(agent, Instant.now(), DEFAULT_TTL));
            log.debug("Created new agent for session: {}", key);
            return agent;
        } catch (IllegalStateException e) {
            // Re-throw IllegalStateException as-is
            throw e;
        } catch (Exception e) {
            log.error("Error getting or creating agent for session: {}", sessionId, e);
            throw new RuntimeException("Failed to get or create agent", e);
        }
    }

    private void pruneExpired() {
        Instant now = Instant.now();
        int beforeSize = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        int afterSize = sessions.size();
        if (beforeSize != afterSize) {
            log.debug(
                    "Pruned {} expired sessions (before: {}, after: {})",
                    beforeSize - afterSize,
                    beforeSize,
                    afterSize);
        }
    }

    private record Entry(ReActAgent agent, Instant createdAt, Duration ttl) {

        Instant expiresAt() {
            return createdAt.plus(ttl);
        }

        boolean isExpired() {
            return expiresAt().isBefore(Instant.now());
        }

        Entry touch() {
            return new Entry(agent, Instant.now(), ttl);
        }
    }
}
