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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class MemoryOperationSchedulerTest {

    @Test
    void operationsWithSameIsolationKeyRunInSubmissionOrder() throws Exception {
        MemoryOperationScheduler scheduler = new MemoryOperationScheduler();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> order = new CopyOnWriteArrayList<>();

        assertTrue(
                scheduler.submit(
                        "SESSION:s1",
                        () ->
                                Mono.fromRunnable(
                                        () -> {
                                            order.add(1);
                                            firstStarted.countDown();
                                            await(releaseFirst);
                                        })));
        assertTrue(scheduler.submit("SESSION:s1", () -> Mono.fromRunnable(() -> order.add(2))));

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(1), order, "second operation must wait for the first");
        releaseFirst.countDown();
        scheduler.close();

        assertEquals(List.of(1, 2), order);
        assertFalse(scheduler.submit("SESSION:s1", Mono::empty));
    }

    @Test
    void differentIsolationKeysCanRunConcurrently() throws Exception {
        MemoryOperationScheduler scheduler = new MemoryOperationScheduler();
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        scheduler.submit("USER:a", () -> blockingTask(bothStarted, release));
        scheduler.submit("USER:b", () -> blockingTask(bothStarted, release));

        assertTrue(
                bothStarted.await(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS),
                "independent isolation keys should not block each other");
        release.countDown();
        scheduler.close();
    }

    private static Mono<Void> blockingTask(CountDownLatch started, CountDownLatch release) {
        return Mono.fromRunnable(
                        () -> {
                            started.countDown();
                            await(release);
                        })
                .then();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
