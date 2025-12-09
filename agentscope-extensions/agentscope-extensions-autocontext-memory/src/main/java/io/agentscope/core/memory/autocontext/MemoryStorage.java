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
 * Interface for storing and managing conversation messages in AutoContextMemory.
 *
 * <p>MemoryStorage provides a pluggable storage abstraction for AutoContextMemory, allowing
 * different storage strategies to be used for message persistence. This interface is used by
 * AutoContextMemory to manage two types of storage:
 * <ul>
 *   <li><b>Working Storage:</b> Stores compressed and offloaded messages used in actual
 *       conversations</li>
 *   <li><b>Original Storage:</b> Stores complete, uncompressed message history in its original
 *       form (append-only)</li>
 * </ul>
 *
 * <p><b>Implementations:</b>
 * <ul>
 *   <li>{@link InMemoryStorage} - In-memory storage using CopyOnWriteArrayList (default)</li>
 *   <li>Custom implementations - Can provide file-based, database-backed, or other storage
 *       strategies</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Implementations should be thread-safe as they may be accessed
 * concurrently by AutoContextMemory operations.
 */
public interface MemoryStorage {

    /**
     * Adds a message to the storage.
     *
     * <p>The message is appended to the storage. For original storage, this maintains an
     * append-only log of all messages. For working storage, this may contain compressed or
     * summarized messages.
     *
     * @param message the message to add (must not be null)
     */
    void addMessage(Msg message);

    /**
     * Retrieves all messages from the storage.
     *
     * <p>Returns a copy of all messages to prevent external modifications from affecting the
     * internal storage state.
     *
     * @return a list of all messages (may be empty but never null)
     */
    List<Msg> getMessages();

    /**
     * Deletes a message at the specified index.
     *
     * <p>If the index is out of bounds (negative or >= size), this operation should be a no-op
     * rather than throwing an exception. This provides safe cleanup even with concurrent
     * modifications.
     *
     * @param index the index of the message to delete (0-based)
     */
    void deleteMessage(int index);

    /**
     * Clears all messages from the storage.
     *
     * <p>This operation removes all stored messages. Use with caution as this action is typically
     * irreversible unless messages have been persisted elsewhere.
     */
    void clear();
}
