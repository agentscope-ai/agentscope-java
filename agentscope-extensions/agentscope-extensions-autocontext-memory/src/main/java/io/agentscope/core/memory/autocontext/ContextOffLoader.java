/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * Interface for offloading and reloading context messages to external storage.
 *
 * <p>ContextOffLoader provides a pluggable storage abstraction for AutoContextMemory,
 * allowing large message content to be stored externally and retrieved when needed.
 * This helps reduce memory usage and context window size by offloading less frequently
 * accessed content.
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Storing large tool invocation histories that have been compressed</li>
 *   <li>Offloading large message payloads that exceed size thresholds</li>
 *   <li>Providing a mechanism to retrieve original content when needed via UUID</li>
 * </ul>
 *
 * <p><b>Implementations:</b>
 * <ul>
 *   <li>{@link InMemoryContextOffLoader} - In-memory storage (default, volatile)</li>
 *   <li>{@link LocalFileContextOffLoader} - File-based storage (persistent)</li>
 *   <li>Custom implementations - Can provide database-backed or cloud storage</li>
 * </ul>
 *
 * <p><b>UUID Management:</b> Each offloaded context is identified by a unique UUID.
 * The UUID is included in compressed context hints, allowing agents to retrieve the
 * original content using the {@link ContextOffloadTool}.
 */
public interface ContextOffLoader {

    /**
     * Offloads messages to external storage with the specified UUID.
     *
     * <p>The messages are stored and can be retrieved later using the same UUID.
     * If a context with the same UUID already exists, it should be replaced.
     *
     * @param uuid the unique identifier for this offloaded context (must not be null)
     * @param messages the messages to offload (must not be null)
     */
    void offload(String uuid, List<Msg> messages);

    /**
     * Reloads messages from storage by UUID.
     *
     * <p>Returns the messages that were previously offloaded with the given UUID.
     * If the UUID is not found, returns an empty list.
     *
     * @param uuid the unique identifier of the offloaded context to retrieve
     * @return the list of messages that were offloaded, or an empty list if not found
     */
    List<Msg> reload(String uuid);

    /**
     * Clears messages from storage by UUID.
     *
     * <p>Removes the offloaded context identified by the UUID. If the UUID is not found,
     * this operation should be a no-op.
     *
     * @param uuid the unique identifier of the offloaded context to clear
     */
    void clear(String uuid);
}
