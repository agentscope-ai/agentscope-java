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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of MemoryStorage.
 *
 * <p>This implementation stores messages in memory using a thread-safe CopyOnWriteArrayList.
 * It is the default storage implementation used by AutoContextMemory when no custom storage is
 * configured.
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>Thread-safe: Uses CopyOnWriteArrayList for concurrent access</li>
 *   <li>Fast: In-memory operations provide low latency</li>
 *   <li>Volatile: Messages are lost when the application restarts</li>
 *   <li>Defensive copying: getMessages() returns a copy to prevent external modifications</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Development and testing environments</li>
 *   <li>Short-lived sessions where persistence is not required</li>
 *   <li>Scenarios where external storage is not available or needed</li>
 *   <li>Default storage for AutoContextMemory</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This implementation is thread-safe and can be safely accessed from
 * multiple threads concurrently.
 */
public class InMemoryStorage implements MemoryStorage {

    /** Thread-safe list storing all messages. */
    private final List<Msg> messages = new CopyOnWriteArrayList<>();

    /**
     * Creates a new empty InMemoryStorage instance.
     */
    public InMemoryStorage() {}

    /**
     * Creates a new InMemoryStorage instance with initial messages.
     *
     * @param initMsgs the initial messages to add to storage (can be null or empty)
     */
    public InMemoryStorage(List<Msg> initMsgs) {
        if (initMsgs != null) {
            this.messages.addAll(initMsgs);
        }
    }

    /**
     * Adds a message to the storage.
     *
     * <p>The message is appended to the end of the storage list.
     *
     * @param message the message to add (must not be null)
     */
    @Override
    public void addMessage(Msg message) {
        messages.add(message);
    }

    /**
     * Retrieves all messages from the storage.
     *
     * <p>Returns a defensive copy of all messages to prevent external modifications from
     * affecting the internal storage state.
     *
     * @return a new list containing all messages (may be empty but never null)
     */
    @Override
    public List<Msg> getMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * Deletes a message at the specified index.
     *
     * <p>If the index is out of bounds (negative or >= size), this operation is a no-op.
     * This provides safe cleanup even with concurrent modifications.
     *
     * @param index the index of the message to delete (0-based)
     */
    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < messages.size()) {
            messages.remove(index);
        }
    }

    /**
     * Clears all messages from the storage.
     *
     * <p>This operation removes all stored messages. The storage will be empty after this call.
     * This action is irreversible unless messages have been persisted elsewhere.
     */
    @Override
    public void clear() {
        messages.clear();
    }
}
