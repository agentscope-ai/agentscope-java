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
package io.agentscope.core.model;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Shared, lazily created schedulers that isolate model work from application blocking tasks.
 *
 * <p>Requests, blocking stream reads, and response delivery use separate bounded pools. Threads
 * are daemon threads and idle threads expire after 60 seconds. The pools live for the JVM lifetime;
 * callers must not dispose these shared schedulers.
 *
 * <p>Set {@code agentscope.model.requestThreadCap}, {@code agentscope.model.streamThreadCap}, or
 * {@code agentscope.model.responseThreadCap} before the corresponding pool is first used. Each
 * defaults to the greater of 64 and ten times the available processor count. The per-thread queued
 * task limit is {@code agentscope.model.taskQueueCapacity} (default 256). These are task limits,
 * not HTTP connection limits. Transport builders also accept caller-owned schedulers.
 */
public final class ModelSchedulers {

    private ModelSchedulers() {}

    /** Returns the shared scheduler for request preparation and blocking non-streaming calls. */
    public static Scheduler requests() {
        return Requests.INSTANCE;
    }

    /** Returns the shared scheduler for blocking stream connection and read operations. */
    public static Scheduler streams() {
        return Streams.INSTANCE;
    }

    /** Returns the shared scheduler for ordered response delivery, separate from stream reads. */
    public static Scheduler responses() {
        return Responses.INSTANCE;
    }

    private static Scheduler create(String role) {
        int defaultCap = Math.max(64, Runtime.getRuntime().availableProcessors() * 10);
        return Schedulers.newBoundedElastic(
                positiveProperty("agentscope.model." + role + "ThreadCap", defaultCap),
                positiveProperty("agentscope.model.taskQueueCapacity", 256),
                "agentscope-model-" + role,
                60,
                true);
    }

    private static int positiveProperty(String name, int fallback) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static final class Requests {
        private static final Scheduler INSTANCE = create("request");
    }

    private static final class Streams {
        private static final Scheduler INSTANCE = create("stream");
    }

    private static final class Responses {
        private static final Scheduler INSTANCE = create("response");
    }
}
