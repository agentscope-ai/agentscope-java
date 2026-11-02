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

package io.agentscope.core.a2a.server.request;

import io.a2a.server.events.EventQueue;
import io.a2a.server.events.EventQueueClosedException;
import io.a2a.server.events.EventQueueFactory;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.tasks.TaskStateProvider;

import java.util.concurrent.atomic.AtomicReference;

/** Creates SDK 0.3.3 queues whose main-queue copies are drained after subscriber delivery. */
final class AgentScopeInMemoryQueueManagerFactory {

    private AgentScopeInMemoryQueueManagerFactory() {}

    static InMemoryQueueManager create(TaskStateProvider provider) {
        AtomicReference<InMemoryQueueManager> managerReference = new AtomicReference<>();
        EventQueueFactory factory =
                taskId -> {
                    AtomicReference<EventQueue> queueReference = new AtomicReference<>();
                    return new EventQueue.EventQueueBuilder() {
                        @Override
                        public EventQueue build() {
                            EventQueue queue = super.build();
                            queueReference.set(queue);
                            return queue;
                        }
                    }.taskId(taskId)
                            .taskStateProvider(provider)
                            .addOnCloseCallback(
                                    () -> managerReference.get().getCleanupCallback(taskId).run())
                            .hook(
                                    item -> {
                                        // SDK 0.3.3 invokes this hook after copying the event to
                                        // every child.
                                        // DefaultRequestHandler consumes child queues, leaving this
                                        // duplicate unread.
                                        try {
                                            queueReference.get().dequeueEventItem(0);
                                        } catch (EventQueueClosedException ignored) {
                                            // A concurrent close may already have emptied the
                                            // queue.
                                        }
                                    });
                };
        InMemoryQueueManager manager = new InMemoryQueueManager(factory, provider);
        managerReference.set(manager);
        return manager;
    }
}
