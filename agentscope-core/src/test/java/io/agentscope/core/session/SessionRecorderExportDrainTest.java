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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Every committed event must reach the sink. The one second heartbeat is not a safety net: the
 * recorder disposes it on close, so a request dropped while a drain is running can sit unexported
 * until an external retry.
 */
class SessionRecorderExportDrainTest {

    @Test
    void eventCommittedDuringDrainIsStillExported() throws Exception {
        var sink = new BlockingSink();
        var log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user", "agent", "export-drain"), null);
        var recorder = new SessionRecorder(log, "turn-1", "run-1", sink);
        try {
            recordMessage(recorder, "first");
            assertTrue(sink.started.await(5, TimeUnit.SECONDS), "the first drain never started");

            // Committed while the drain above is still inside accept().
            recordMessage(recorder, "second");
            recorder.close();

            sink.release.countDown();

            assertTrue(
                    sink.awaitExported(2),
                    "the event committed during the drain never reached the sink: "
                            + sink.exported);
            assertEquals(List.of(1L, 2L), sink.sortedSeqs());
        } finally {
            sink.release.countDown();
            recorder.close();
        }
    }

    private static void recordMessage(SessionRecorder recorder, String id) {
        recorder.recordNow(
                "message/user",
                Map.of(
                        "message",
                        Msg.builder()
                                .id(id)
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(id).build())
                                .build()));
    }

    private static final class BlockingSink implements SessionExportSink {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger accepts = new AtomicInteger();
        private final List<Long> exported = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String name() {
            return "export-drain-test";
        }

        @Override
        public void accept(SessionEvent event) {
            if (accepts.incrementAndGet() == 1) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            exported.add(event.seq());
        }

        List<Long> sortedSeqs() {
            var copy = new ArrayList<>(exported);
            Collections.sort(copy);
            return copy;
        }

        boolean awaitExported(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (exported.size() >= count) return true;
                Thread.sleep(10);
            }
            return exported.size() >= count;
        }
    }
}
