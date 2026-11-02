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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.a2a.server.events.EventQueue;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class AgentScopeInMemoryQueueManagerFactoryTest {
    private static final TaskStateProvider PROVIDER =
            new TaskStateProvider() {
                public boolean isTaskActive(String id) {
                    return true;
                }

                public boolean isTaskFinalized(String id) {
                    return false;
                }
            };

    @Test
    void preservesEventsForMultipleSubscribersBeyond5000() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    InMemoryQueueManager manager =
                            AgentScopeInMemoryQueueManagerFactory.create(PROVIDER);
                    EventQueue first = manager.createOrTap("task");
                    EventQueue second = manager.tap("task");
                    for (int i = 0; i < 10000; i++) {
                        TaskStatusUpdateEvent event =
                                new TaskStatusUpdateEvent(
                                        "task",
                                        new TaskStatus(TaskState.WORKING),
                                        "ctx",
                                        false,
                                        Map.of("seq", i));
                        first.enqueueEvent(event);
                        assertSame(event, first.dequeueEventItem(0).getEvent());
                        assertSame(event, second.dequeueEventItem(0).getEvent());
                        assertNull(manager.get("task").dequeueEventItem(0));
                    }
                });
    }

    @Test
    void closeRemovesQueueFromManager() {
        InMemoryQueueManager manager = AgentScopeInMemoryQueueManagerFactory.create(PROVIDER);
        manager.createOrTap("task");
        manager.close("task");
        assertNull(manager.get("task"));
    }

    @Test
    void preservesSlowSubscriberBackpressure() throws Exception {
        InMemoryQueueManager manager = AgentScopeInMemoryQueueManagerFactory.create(PROVIDER);
        EventQueue fast = manager.createOrTap("task");
        EventQueue slow = manager.tap("task");
        TaskStatusUpdateEvent event =
                new TaskStatusUpdateEvent(
                        "task", new TaskStatus(TaskState.WORKING), "ctx", false, Map.of());
        for (int i = 0; i < 1000; i++) {
            fast.enqueueEvent(event);
            assertNotNull(fast.dequeueEventItem(0));
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer =
                new Thread(
                        () -> {
                            try {
                                fast.enqueueEvent(event);
                            } catch (Throwable t) {
                                failure.set(t);
                            }
                        });
        producer.setDaemon(true);
        producer.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (producer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.WAITING, producer.getState());
            assertNotNull(slow.dequeueEventItem(0));
            producer.join(2000);
            assertFalse(producer.isAlive());
            assertNull(failure.get());
        } finally {
            producer.interrupt();
            producer.join(2000);
        }
    }
}
