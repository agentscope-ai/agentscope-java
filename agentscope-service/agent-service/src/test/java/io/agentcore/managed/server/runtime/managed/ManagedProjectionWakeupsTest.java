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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ManagedProjectionWakeupsTest {
    @Test
    void visibleSignalReachesAnotherReplicaWithoutBorrowingExecutionConnection() throws Exception {
        var source = mock(DataSource.class);
        var redis = mock(RedisManagedInfrastructure.class);
        var subscribers = new CopyOnWriteArrayList<Consumer<String>>();
        when(redis.busListen(anyString(), any()))
                .thenAnswer(
                        call -> {
                            Consumer<String> listener = call.getArgument(1);
                            subscribers.add(listener);
                            return (AutoCloseable) () -> subscribers.remove(listener);
                        });
        doAnswer(
                        call -> {
                            String message = call.getArgument(1);
                            subscribers.forEach(listener -> listener.accept(message));
                            return null;
                        })
                .when(redis)
                .busPublish(anyString(), anyString());
        var local = new SessionEventNotifier(source, 100);
        var remote = new SessionEventNotifier(source, 100);
        var scope = new ManagedScope("t", "n", "s");
        var reached = new CountDownLatch(1);
        var reader =
                remote.wakeups(scope.messageGroup()).skip(1).subscribe(key -> reached.countDown());
        try (var sender = new ManagedProjectionWakeups(redis, local);
                var receiver = new ManagedProjectionWakeups(redis, remote)) {
            sender.start();
            receiver.start();
            sender.visible(scope);
            assertTrue(reached.await(3, TimeUnit.SECONDS));
        } finally {
            reader.dispose();
        }
        assertTrue(subscribers.isEmpty());
        verifyNoInteractions(source);
    }

    @Test
    void unavailableRedisDoesNotBlockOrFailTheVisibleCommitAndWakeupsRemainBounded()
            throws Exception {
        var source = mock(DataSource.class);
        var redis = mock(RedisManagedInfrastructure.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(
                        call -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            throw new IllegalStateException("Redis unavailable");
                        })
                .when(redis)
                .busPublish(anyString(), anyString());
        var notifier = new SessionEventNotifier(source, 100);
        var scope = new ManagedScope("t", "n", "s");
        var local = new AtomicInteger();
        var reader =
                notifier.wakeups(scope.messageGroup())
                        .skip(1)
                        .subscribe(key -> local.incrementAndGet());
        try (var sender = new ManagedProjectionWakeups(redis, notifier)) {
            sender.visible(scope);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) sender.visible(scope);
            assertEquals(1001, local.get());
            assertEquals(1, calls.get()); // Coalesced while the single sender is blocked.
            for (int i = 0; i < 1000; i++) sender.visible(new ManagedScope("t", "n", "s-" + i));
            assertEquals(1, calls.get()); // Queue overflow never runs IO on the caller.
        } finally {
            release.countDown();
            reader.dispose();
        }
        verifyNoInteractions(source);
    }
}
