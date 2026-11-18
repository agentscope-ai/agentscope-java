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
package io.agentscope.core.journal;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Session persistence and maintenance never compete with the shared model-request queue. */
public final class JournalSchedulers {
    private JournalSchedulers() {}

    public static Scheduler commits() {
        return Commits.INSTANCE;
    }

    public static Scheduler maintenance() {
        return Maintenance.INSTANCE;
    }

    static Scheduler leases() {
        return Leases.INSTANCE;
    }

    static Scheduler timer() {
        return Timer.INSTANCE;
    }

    private static Scheduler create(String role, int fallback) {
        int threads = Integer.getInteger("agentscope.journal." + role + "ThreadCap", fallback);
        int queue = Integer.getInteger("agentscope.journal.taskQueueCapacity", 65536);
        if (threads < 1 || queue < 1)
            throw new IllegalArgumentException("Invalid session scheduler capacity");
        return Schedulers.newBoundedElastic(threads, queue, "agentscope-session-" + role, 60, true);
    }

    private static final class Commits {
        private static final Scheduler INSTANCE =
                create("commit", Math.max(64, Runtime.getRuntime().availableProcessors() * 10));
    }

    private static final class Maintenance {
        private static final Scheduler INSTANCE = create("maintenance", 4);
    }

    private static final class Leases {
        private static final Scheduler INSTANCE = create("lease", 4);
    }

    private static final class Timer {
        private static final Scheduler INSTANCE =
                Schedulers.newSingle("agentscope-session-timer", true);
    }
}
