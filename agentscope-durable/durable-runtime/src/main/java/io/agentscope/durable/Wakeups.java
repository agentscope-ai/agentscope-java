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

package io.agentscope.durable;

import io.agentscope.durable.DurableTypes.SessionKey;
import java.util.function.Consumer;

/**
 * Optional session-change hints, never execution commands or event payloads. Hints may be lost,
 * duplicated or delivered to only one host; durable discovery/replay repairs delivery gaps.
 * Implementations fan out to local subscribers. Callbacks must only schedule or signal work,
 * never perform database I/O or turn an Agent. Normal return acknowledges the hint, not the Turn.
 * Closing a subscription removes only that listener; an in-flight callback may still finish.
 */
public interface Wakeups {
    /** No transport required: readers and runtimes poll. */
    static Wakeups none() {
        return new Wakeups() {
            public void publish(SessionKey session) {}

            public AutoCloseable subscribe(Consumer<SessionKey> listener) {
                return () -> {};
            }
        };
    }

    /** Thread-safe process-local fan-out without a background thread or external dependency. */
    static Wakeups local() {
        return new LocalWakeups();
    }

    void publish(SessionKey session);

    AutoCloseable subscribe(Consumer<SessionKey> listener);
}
