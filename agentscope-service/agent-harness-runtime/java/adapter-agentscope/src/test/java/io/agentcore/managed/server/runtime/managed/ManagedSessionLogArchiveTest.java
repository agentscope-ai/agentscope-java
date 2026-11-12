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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.SessionEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ManagedSessionLogArchiveTest {
    private final ManagedScope scope = new ManagedScope("tenant", "ns", "logs");

    private SessionEvent chunk() {
        return new SessionEvent(
                1,
                "event",
                1,
                1,
                "model/chunk",
                "run",
                "turn",
                false,
                "{\"chunk\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}");
    }

    @Test
    void disabledOrSampledChunksNeedNoStorage() {
        var objects = mock(RuntimeObjectStore.class);
        try (var archive = new ManagedSessionLogArchive(objects, new ObjectMapper(), false, true)) {
            archive.offer(scope, chunk());
        }
        try (var archive = new ManagedSessionLogArchive(objects, new ObjectMapper(), true, false)) {
            archive.offer(scope, chunk());
        }
        verifyNoInteractions(objects);
    }

    @Test
    void slowFailedUploadsNeverWaitOnTheProducer() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var objects = mock(RuntimeObjectStore.class);
        when(objects.put(anyString(), eq("session-log"), anyString(), any()))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            throw new IllegalStateException("offline");
                        });
        try (var archive = new ManagedSessionLogArchive(objects, new ObjectMapper(), true, true)) {
            archive.offer(scope, chunk());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var producer =
                    CompletableFuture.runAsync(
                            () -> {
                                for (int i = 0; i < 6000; i++) archive.offer(scope, chunk());
                            });
            producer.get(2, TimeUnit.SECONDS);
            assertTrue(archive.dropped() > 0);
            assertTrue(archive.bufferedBytes() <= 8 * 1024 * 1024);
            release.countDown();
        } finally {
            release.countDown();
        }
    }
}
