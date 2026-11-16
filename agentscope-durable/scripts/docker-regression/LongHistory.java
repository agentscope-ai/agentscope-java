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
package io.agentscope.durable.examples.brainhands.echo;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.*;
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.LiveEvents;
import io.agentscope.durable.SessionStreamEvent;
import io.agentscope.extensions.durable.jdbc.PostgresBackend;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LongHistory {
    public static void main(String[] args) throws Exception {
        var key = new SessionKey("regression", "long-history");
        var bus = LiveEvents.local();
        try (var backend = PostgresBackend.builder().dataSource(Probe.database()).build();
                var runtime = DurableRuntime.builder().backend(backend).liveEvents(bus).build()) {
            backend.create(new SessionSpec(key, new ThreadBinding("history", "1", "1"), null));
            var thread = new ThreadKey(key, "main");
            var turn = backend.submit(thread, "history", DurableJson.value("history")).turnId();
            var lease =
                    backend.claim(thread, "history-writer", Duration.ofMinutes(5)).orElseThrow();
            var session = runtime.getSession(key);
            var options =
                    EventStreamOptions.builder()
                            .after(session.snapshot().cursor())
                            .deltas(EventStreamOptions.EventDeltaType.MESSAGE)
                            .build();
            int total = 2048;
            var delivered = new CountDownLatch(total);
            var positive = new CountDownLatch(1);
            var stale = new AtomicInteger();
            try (var observer =
                    session.subscribeEvents(
                            options,
                            event -> {
                                if (event instanceof SessionStreamEvent.Committed c
                                        && "message.completed".equals(c.event().type()))
                                    delivered.countDown();
                                if (event instanceof SessionStreamEvent.EventDelta delta) {
                                    if (delta.eventId().equals("active")) positive.countDown();
                                    else stale.incrementAndGet();
                                }
                            })) {
                long revision = backend.frame(lease).turn().revision();
                String cursor = null;
                for (int first = 0; first < total; first += 64) {
                    var events = new ArrayList<EventInput>();
                    for (int i = first; i < first + 64; i++) {
                        String id = "history-" + i;
                        events.add(
                                new EventInput(
                                        id,
                                        "message.completed",
                                        DurableJson.value(
                                                Map.of(
                                                        "messageId",
                                                        id,
                                                        "attempt",
                                                        1,
                                                        "revision",
                                                        0,
                                                        "text",
                                                        id))));
                    }
                    if (first == 0)
                        events.add(
                                new EventInput(
                                        "active-partial",
                                        "message.delta",
                                        DurableJson.value(
                                                Map.of(
                                                        "messageId",
                                                        "partial",
                                                        "attempt",
                                                        2,
                                                        "revision",
                                                        4,
                                                        "text",
                                                        "partial"))));
                    var receipt =
                            backend.commit(
                                    lease,
                                    new Commit(
                                            "page-" + first,
                                            revision,
                                            DurableJson.value("history"),
                                            List.of(),
                                            List.of(),
                                            List.of(),
                                            events,
                                            TurnStatus.RUNNING,
                                            null,
                                            null,
                                            null));
                    revision = receipt.revision();
                    cursor = receipt.cursor();
                }
                if (!delivered.await(90, TimeUnit.SECONDS))
                    throw new AssertionError("missed completions " + delivered.getCount());
                backend.pruneEvents(key, cursor);
                for (var id :
                        List.of("history-0", "history-1024", "history-2047", "partial", "active")) {
                    bus.publish(
                            new LiveEvent(
                                    "1",
                                    id,
                                    key,
                                    "main",
                                    turn,
                                    null,
                                    null,
                                    1,
                                    "message.delta",
                                    DurableJson.value(
                                            Map.of(
                                                    "messageId",
                                                    id,
                                                    "text",
                                                    "preview",
                                                    "attempt",
                                                    2,
                                                    "revision",
                                                    3))));
                }
                if (!positive.await(30, TimeUnit.SECONDS) || stale.get() != 0)
                    throw new AssertionError("stale preview count " + stale.get());
                System.out.println("LONG_HISTORY completions=2048 stale=0 active=1");
            }
        }
    }
}
