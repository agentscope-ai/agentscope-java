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
import io.agentscope.core.state.StateModule;
import java.util.List;

/**
 * Interface for memory components that store and manage conversation history.
 *
 * <p>Memory extends StateModule to provide state persistence capabilities, allowing conversation
 * history to be saved and restored through sessions. Different memory implementations can provide
 * various storage strategies such as in-memory, database-backed, or window-based storage.
 */
public interface Memory extends StateModule {

    /**
     * Adds a message to the memory.
     *
     * @param message The message to store in memory
     */
    void addMessage(Msg message);

    /**
     * Retrieves all messages stored in memory.
     *
     * @return A list of all messages (may be empty but never null)
     */
    List<Msg> getMessages();

    /**
     * Deletes a message at the specified index.
     *
     * <p>If the index is out of bounds (negative or >= size), this operation should be a no-op
     * rather than throwing an exception. This provides safe cleanup even with concurrent modifications.
     *
     * @param index The index of the message to delete (0-based)
     */
    void deleteMessage(int index);

    /**
     * Clears all messages from memory.
     *
     * <p>This operation removes all stored conversation history. Use with caution as this action
     * is typically irreversible unless state has been persisted.
     */
    void clear();
}
