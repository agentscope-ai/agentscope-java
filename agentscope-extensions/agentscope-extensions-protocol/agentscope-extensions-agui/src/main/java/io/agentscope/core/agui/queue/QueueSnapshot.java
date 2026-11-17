/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.agui.queue;

import java.util.List;

/**
 * Point-in-time view of a thread queue.
 *
 * @param items queued messages in acceptance order
 * @param active whether the thread currently has an active run
 * @param awaitingConfirmation whether the thread has an unresolved interrupt
 */
public record QueueSnapshot(List<Item> items, boolean active, boolean awaitingConfirmation) {

    /**
     * A single queued message.
     *
     * @param messageId the message identifier
     * @param position the one-based queue position
     * @param state the current state
     */
    public record Item(String messageId, int position, String state) {}
}
