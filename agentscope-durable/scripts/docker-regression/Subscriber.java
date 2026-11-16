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

import io.agentscope.durable.*;
import io.agentscope.durable.DurableTypes.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Subscriber {
    private static final Path CURSOR = Path.of("/tmp/subscriber-cursor");

    private static void await(CountDownLatch signal) {
        try {
            if (!signal.await(120, TimeUnit.SECONDS))
                throw new IllegalStateException("callback deadline");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void publish(LiveEvents bus, String turn, String field, String id, String text) {
        publish(bus, Probe.KEY, turn, field, id, text);
    }

    private static void publish(
            LiveEvents bus, SessionKey source, String turn, String field, String id, String text) {
        String type = field.equals("messageId") ? "message.delta" : "tool.arguments.delta";
        bus.publish(
                new LiveEvent(
                        "1",
                        UUID.randomUUID().toString(),
                        source,
                        "main",
                        turn,
                        null,
                        null,
                        1,
                        type,
                        DurableJson.value(Map.of(field, id, "text", text))));
    }

    public static void main(String[] args) throws Exception {
        var bus = LiveEvents.local();
        try (var backend =
                        io.agentscope.extensions.durable.jdbc.PostgresBackend.builder()
                                .dataSource(Probe.database())
                                .build();
                var runtime = DurableRuntime.builder().backend(backend).liveEvents(bus).build()) {
            var session = runtime.getSession(Probe.KEY);
            if (args[0].equals("save")) {
                Files.writeString(CURSOR, session.snapshot().cursor());
                return;
            }
            if (args[0].equals("pressure")) {
                pressure(session, bus, args[1]);
                return;
            }
            var filter = new EventFilter("main", args[1], null);
            var finished = new CountDownLatch(1);
            try (var sub =
                    session.subscribeEvents(
                            filter,
                            EventStreamOptions.builder().after(Files.readString(CURSOR)).build(),
                            e -> {
                                try {
                                    if (e instanceof SessionStreamEvent.Committed c) {
                                        var data = c.event().data();
                                        if (EchoBrainExample.FINAL_ANSWER.equals(
                                                data.path("text").asText())) {
                                            var id = data.required("messageId").asText();
                                            Files.writeString(
                                                    Path.of("/tmp/display-" + id),
                                                    id,
                                                    StandardOpenOption.CREATE_NEW);
                                            System.out.println("DISPLAY_BEFORE_CURSOR " + id);
                                            Runtime.getRuntime().halt(73);
                                        }
                                    }
                                    if (e instanceof SessionStreamEvent.SnapshotReset reset) {
                                        int answers = 0;
                                        for (var message :
                                                reset.snapshot()
                                                        .projection()
                                                        .required("messages")) {
                                            if (!args[1].equals(
                                                    message.required("turnId").asText()))
                                                throw new AssertionError("turn leak");
                                            if (!EchoBrainExample.FINAL_ANSWER.equals(
                                                    message.path("text").asText())) continue;
                                            String id = message.required("messageId").asText();
                                            if (!Files.readString(Path.of("/tmp/display-" + id))
                                                    .equals(id)) throw new AssertionError();
                                            answers++;
                                        }
                                        if (answers != 1)
                                            throw new AssertionError(
                                                    "snapshot answer count " + answers);
                                        Files.createFile(Path.of("/tmp/reset-reached"));
                                        while (Files.exists(Path.of("/tmp/hold-reset"))) {
                                            try {
                                                Thread.sleep(50);
                                            } catch (InterruptedException ex) {
                                                throw new IllegalStateException(ex);
                                            }
                                        }
                                        Files.writeString(CURSOR, reset.snapshot().cursor());
                                        finished.countDown();
                                    }
                                } catch (java.io.IOException error) {
                                    throw new IllegalStateException(error);
                                }
                            })) {
                await(finished);
                String boundary = session.snapshot().cursor();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!boundary.equals(sub.cursor())) {
                    if (System.nanoTime() > deadline)
                        throw new AssertionError("filtered subscriber lag");
                    Thread.sleep(50);
                }
            }
        }
    }

    private static void pressure(DurableSession session, LiveEvents bus, String oldTurn)
            throws Exception {
        var base = session.snapshot(new EventFilter("main", oldTurn, null));
        var options =
                EventStreamOptions.builder()
                        .after(base.cursor())
                        .deltas(
                                EventStreamOptions.EventDeltaType.MESSAGE,
                                EventStreamOptions.EventDeltaType.TOOL_ARGUMENTS)
                        .build();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fastAnswer = new CountDownLatch(1);
        var slowAnswer = new CountDownLatch(1);
        var sentinel = new CountDownLatch(2);
        var stale = new AtomicInteger();
        var slowBurst = new AtomicInteger();
        try (var slow =
                        session.subscribeEvents(
                                options,
                                e -> {
                                    if (e instanceof SessionStreamEvent.EventDelta d) {
                                        String text = d.data().path("text").asText();
                                        if (text.equals("block")) {
                                            blocked.countDown();
                                            await(release);
                                        }
                                        if (text.equals("late")) stale.incrementAndGet();
                                        if (text.equals("burst")) slowBurst.incrementAndGet();
                                        if (text.equals("sentinel")) sentinel.countDown();
                                    }
                                    if (answer(e)) slowAnswer.countDown();
                                });
                var fast =
                        session.subscribeEvents(
                                options,
                                e -> {
                                    if (e instanceof SessionStreamEvent.EventDelta d) {
                                        if ("late".equals(d.data().path("text").asText()))
                                            stale.incrementAndGet();
                                        if ("sentinel".equals(d.data().path("text").asText()))
                                            sentinel.countDown();
                                    }
                                    if (answer(e)) fastAnswer.countDown();
                                })) {
            try {
                publish(bus, oldTurn, "toolCallId", "new", "block");
                await(blocked);
                for (int i = 0; i < 4096; i++)
                    publish(bus, oldTurn, "toolCallId", "burst", "burst");
                var turn = session.submit("slow-fast", "Call echo");
                await(fastAnswer);
                System.out.println("FAST_WHILE_BLOCKED " + turn.turnId());
            } finally {
                release.countDown();
            }
            await(slowAnswer);
            // Wait for the saturated queue to drain before using an ordered positive control.
            var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!Objects.equals(fast.cursor(), session.snapshot().cursor())
                    || !Objects.equals(slow.cursor(), fast.cursor())) {
                if (System.nanoTime() > end) throw new AssertionError("durable lag");
                Thread.sleep(50);
            }
            for (var message : base.projection().required("messages")) {
                if (message.path("complete").asBoolean())
                    publish(
                            bus,
                            oldTurn,
                            "messageId",
                            message.required("messageId").asText(),
                            "late");
            }
            publish(bus, oldTurn, "toolCallId", "echo-" + oldTurn, "late");
            publish(
                    bus,
                    new SessionKey("other", Probe.KEY.sessionId()),
                    oldTurn,
                    "toolCallId",
                    "new",
                    "late");
            bus.publish(
                    new LiveEvent(
                            "1",
                            "oversized",
                            Probe.KEY,
                            "main",
                            oldTurn,
                            null,
                            null,
                            1,
                            "tool.arguments.delta",
                            DurableJson.value(
                                    Map.of(
                                            "toolCallId",
                                            "new",
                                            "text",
                                            "late",
                                            "padding",
                                            "x".repeat(70 * 1024)))));
            publish(bus, oldTurn, "toolCallId", "new", "sentinel");
            await(sentinel);
            if (stale.get() != 0) throw new AssertionError("stale previews " + stale);
            if (slowBurst.get() > 128) throw new AssertionError("slow queue exceeded capacity");
            System.out.println("STALE_PREVIEWS 0 SLOW_BURST " + slowBurst.get() + "/4096");
        }
    }

    private static boolean answer(SessionStreamEvent e) {
        return e instanceof SessionStreamEvent.Committed c
                && EchoBrainExample.FINAL_ANSWER.equals(c.event().data().path("text").asText());
    }
}
