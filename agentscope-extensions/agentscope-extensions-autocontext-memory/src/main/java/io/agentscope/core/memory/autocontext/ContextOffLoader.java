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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * Interface for offloading and reloading context messages to external storage.
 *
 * <p>Provides a storage abstraction for AutoContextMemory to store large message content
 * externally and retrieve it by UUID when needed. This helps reduce memory usage and
 * context window size.
 *
 * <p>Each offloaded context is identified by a unique UUID, which can be used to retrieve
 * the original content via {@link ContextOffloadTool}.
 */
interface ContextOffLoader {

    /**
     * Offloads messages to external storage with the specified UUID.
     *
     * @param uuid the unique identifier for this offloaded context (must not be null)
     * @param messages the messages to offload (must not be null)
     */
    void offload(String uuid, List<Msg> messages);

    /**
     * Reloads messages from storage by UUID.
     *
     * @param uuid the unique identifier of the offloaded context to retrieve
     * @return the list of messages that were offloaded, or an empty list if not found
     */
    List<Msg> reload(String uuid);

    /**
     * Clears messages from storage by UUID.
     *
     * @param uuid the unique identifier of the offloaded context to clear
     */
    void clear(String uuid);
}
