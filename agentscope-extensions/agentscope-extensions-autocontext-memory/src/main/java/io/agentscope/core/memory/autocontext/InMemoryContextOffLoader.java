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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory implementation of ContextOffLoader.
 *
 * <p>Stores offloaded context messages in memory using a HashMap. This is the default
 * implementation used when no external storage is configured. Messages are stored
 * temporarily in memory and will be lost when the application restarts.
 *
 * <p>This implementation is suitable for:
 * <ul>
 *   <li>Development and testing environments</li>
 *   <li>Short-lived sessions where persistence is not required</li>
 *   <li>Scenarios where external storage is not available</li>
 * </ul>
 */
public class InMemoryContextOffLoader implements ContextOffLoader {

    /** Map storing offloaded messages by UUID. */
    private final Map<String, List<Msg>> messagesMap = new HashMap<>();

    /**
     * Offloads messages to in-memory storage.
     *
     * <p>Stores the messages in a HashMap keyed by UUID. If a context with the same UUID
     * already exists, it will be replaced.
     *
     * @param uuid the unique identifier for this offloaded context
     * @param messages the messages to offload
     */
    @Override
    public void offload(String uuid, List<Msg> messages) {
        messagesMap.put(uuid, messages);
    }

    /**
     * Reloads messages from in-memory storage.
     *
     * <p>Returns a defensive copy of the messages to prevent external modifications.
     *
     * @param uuid the unique identifier of the offloaded context to retrieve
     * @return a copy of the messages that were offloaded, or an empty list if not found
     */
    @Override
    public List<Msg> reload(String uuid) {
        if (!messagesMap.containsKey(uuid)) {
            return List.of();
        }
        return new ArrayList<>(messagesMap.get(uuid));
    }

    /**
     * Clears messages from in-memory storage.
     *
     * <p>Removes the offloaded context from the HashMap. If the UUID is not found,
     * this operation is a no-op.
     *
     * @param uuid the unique identifier of the offloaded context to clear
     */
    @Override
    public void clear(String uuid) {
        messagesMap.remove(uuid);
    }
}
