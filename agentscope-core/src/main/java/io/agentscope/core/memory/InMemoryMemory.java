/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory implementation of Memory with state persistence support.
 *
 * This implementation stores messages in memory using thread-safe collections
 * and provides state serialization/deserialization for session management.
 */
public class InMemoryMemory implements Memory {

    private final List<Msg> messages = new CopyOnWriteArrayList<>();

    /** Key prefix for storage. */
    private static final String KEY_PREFIX = "memory";

    /**
     * Constructor for InMemoryMemory.
     */
    public InMemoryMemory() {}

    // ==================== StateModule Implementation ====================

    /**
     * Save memory state to the session.
     *
     * <p>Passes the full message list to the session, including the case where the list is empty.
     * The Session implementation is responsible for incremental storage (e.g., JsonSession appends
     * only new items based on file line count).
     *
     * @param session the session to save state to
     * @param sessionKey the session identifier
     */
    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        // Always save, even when empty, to ensure cleared state is persisted
        session.save(sessionKey, KEY_PREFIX + "_messages", new ArrayList<>(messages));
    }

    /**
     * Load memory state from the session.
     *
     * @param session the session to load state from
     * @param sessionKey the session identifier
     */
    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        List<Msg> loaded = session.getList(sessionKey, KEY_PREFIX + "_messages", Msg.class);
        messages.clear();
        messages.addAll(loaded);
    }

    // ==================== Memory Interface Implementation ====================

    /**
     * Adds a message to the in-memory message list.
     *
     * <p>This method is thread-safe due to the use of CopyOnWriteArrayList.
     *
     * @param message The message to add to memory
     */
    @Override
    public void addMessage(Msg message) {
        messages.add(message);
    }

    /**
     * Retrieves all non-null messages from memory.
     *
     * <p>This method filters out any null entries and returns a new list copy. Thread-safe for
     * concurrent reads.
     *
     * @return A new list containing all non-null messages
     */
    @Override
    public List<Msg> getMessages() {
        return messages.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * Deletes a message at the specified index.
     *
     * <p>This method is thread-safe due to the use of CopyOnWriteArrayList. If the index is out
     * of bounds, this operation is a no-op (no exception thrown).
     *
     * @param index The index of the message to delete (0-based)
     */
    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < messages.size()) {
            messages.remove(index);
        }
    }

    /**
     * Clears all messages from memory.
     *
     * <p>This method is thread-safe due to the use of CopyOnWriteArrayList.
     */
    @Override
    public void clear() {
        messages.clear();
    }
}
