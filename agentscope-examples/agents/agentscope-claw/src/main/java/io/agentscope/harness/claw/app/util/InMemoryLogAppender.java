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
package io.agentscope.harness.claw.app.util;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Logback appender that stores the last {@value #MAX_LINES} log lines in an in-memory ring buffer
 * and broadcasts each new entry to all active subscribers via a {@link Sinks.Many} multicast sink.
 *
 * <p>Register in {@code logback-spring.xml}:
 *
 * <pre>{@code
 * <appender name="MEMORY" class="io.agentscope.harness.claw.app.util.InMemoryLogAppender"/>
 * }</pre>
 *
 * <p>Static helpers {@link #recentLines(int)} and {@link #liveStream()} access the singleton safely.
 */
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> {

    public static final int MAX_LINES = 1000;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final AtomicReference<InMemoryLogAppender> INSTANCE = new AtomicReference<>();

    private final ArrayDeque<String> ring = new ArrayDeque<>(MAX_LINES + 1);
    private final Sinks.Many<String> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    @Override
    public void start() {
        super.start();
        INSTANCE.set(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
        String line = format(event);
        synchronized (ring) {
            ring.addLast(line);
            if (ring.size() > MAX_LINES) ring.pollFirst();
        }
        sink.tryEmitNext(line);
    }

    /** Returns the {@code n} most recent log lines, oldest first. */
    public static List<String> recentLines(int n) {
        InMemoryLogAppender instance = INSTANCE.get();
        if (instance == null) return List.of();
        synchronized (instance.ring) {
            List<String> all = new ArrayList<>(instance.ring);
            int from = Math.max(0, all.size() - n);
            return all.subList(from, all.size());
        }
    }

    /** Live log {@link Flux} of new log lines (does not replay history). */
    public static Flux<String> liveStream() {
        InMemoryLogAppender instance = INSTANCE.get();
        if (instance == null) return Flux.empty();
        return instance.sink.asFlux();
    }

    /** Instance method: returns recent lines (all, up to {@value #MAX_LINES}). */
    public List<String> recentLines() {
        return recentLines(MAX_LINES);
    }

    /** Instance method: replays current buffer then continues with live lines. */
    public Flux<String> stream() {
        return Flux.fromIterable(recentLines()).concatWith(liveStream());
    }

    /** Returns the active singleton, or {@code null} if Logback hasn't started it yet. */
    public static InMemoryLogAppender getInstance() {
        return INSTANCE.get();
    }

    private static String format(ILoggingEvent event) {
        String ts = TIME_FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
        String level = event.getLevel().toString();
        String logger = abbreviateLogger(event.getLoggerName());
        String msg = event.getFormattedMessage();
        return ts + " " + level + " " + logger + " - " + msg;
    }

    private static String abbreviateLogger(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return name;
        int prev = name.lastIndexOf('.', dot - 1);
        return prev < 0 ? name.substring(dot + 1) : "..." + name.substring(prev + 1);
    }
}
