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

import io.agentscope.durable.DurableTypes.ThreadKey;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/** Thread notifications and durable dispatch transport for a distributed runtime. */
public interface TurnScheduler extends Wakeups {
    /** After a committed change, schedule publication of any due dispatch for this Thread. */
    default void changed(SessionBackend backend, ThreadKey thread) {
        publish(thread);
    }

    /** A committed control can also wake the current owner of the targeted Turn. */
    default void controlChanged(SessionBackend backend, ThreadKey thread, String turnId) {
        changed(backend, thread);
    }

    /** Observe the recovery deadline written by a successful claim. */
    default void claimed(SessionBackend backend, ThreadKey thread) {}

    /**
     * Starts one runner. A delivery can be acknowledged when the returned stage confirms a
     * durable outcome or handoff. A false or failed stage leaves the delivery eligible for retry.
     */
    AutoCloseable start(
            SessionBackend backend,
            String runnerId,
            Function<TurnDispatch, CompletionStage<Boolean>> advance,
            Consumer<TurnSignal> refresh);

    /** Stop taking new dispatches while allowing committed work to be published. */
    default void quiesce() {}
}
