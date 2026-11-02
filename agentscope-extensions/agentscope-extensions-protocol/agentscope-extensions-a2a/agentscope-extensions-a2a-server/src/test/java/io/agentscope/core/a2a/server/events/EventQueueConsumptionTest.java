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

package io.agentscope.core.a2a.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.a2a.server.events.EventQueue;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.events.QueueManager;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EventQueueConsumptionTest {
    private static final int CAPACITY = 1000;

    @Test
    void childConsumptionAloneDoesNotReleaseMainQueueCapacity() throws Exception {
        QueuePair queues = newQueues("child-only");
        assertEquals(CAPACITY, queues.main().getQueueSize());
        for (int i = 0; i < CAPACITY; i++) {
            queues.child().enqueueEvent(event(i));
            assertNotNull(queues.child().dequeueEventItem(0));
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queues.child().enqueueEvent(event(CAPACITY));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        producer.setDaemon(true);
        producer.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (producer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.WAITING, producer.getState(), "producer should wait for capacity");
            assertNotNull(queues.main().dequeueEventItem(0));
            producer.join(2000);
            assertFalse(producer.isAlive(), "dequeueing one main event should release a permit");
            assertNull(failure.get());
            assertNotNull(queues.child().dequeueEventItem(0));
        } finally {
            if (producer.isAlive()) {
                producer.interrupt();
                producer.join(2000);
            }
        }
    }

    @Test
    void consumingBothQueuesAllowsMoreThanCapacity() throws Exception {
        QueuePair queues = newQueues("both-consumers");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < 5000; i++) {
                queues.child().enqueueEvent(event(i));
                assertNotNull(queues.child().dequeueEventItem(0));
                assertNotNull(queues.main().dequeueEventItem(0));
            }
        });
    }

    private static QueuePair newQueues(String taskId) {
        TaskStateProvider provider = new TaskStateProvider() {
            @Override public boolean isTaskActive(String ignored) { return true; }
            @Override public boolean isTaskFinalized(String ignored) { return false; }
        };
        QueueManager manager = new InMemoryQueueManager(provider);
        EventQueue child = manager.createOrTap(taskId);
        EventQueue main = manager.get(taskId);
        assertNotNull(child);
        assertNotNull(main);
        return new QueuePair(main, child);
    }

    private static TaskStatusUpdateEvent event(int sequence) {
        return new TaskStatusUpdateEvent("task", new TaskStatus(TaskState.WORKING), "context", false, Map.of("sequence", sequence));
    }

    private record QueuePair(EventQueue main, EventQueue child) {}
}
