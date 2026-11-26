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
import java.util.function.Predicate;

/** Thread notifications and durable dispatch transport for a distributed runtime. */
public interface TurnScheduler extends Wakeups {
    /** After a committed change, schedule publication of any due dispatch for this Thread. */
    default void changed(SessionBackend backend, ThreadKey thread) {
        publish(thread);
    }

    /**
     * Starts one runner. Successful admission may acknowledge a delivery because the backend
     * retains the dispatch until a claim changes its generation.
     */
    AutoCloseable start(SessionBackend backend, Predicate<TurnDispatch> admission);

    /** Stop taking new dispatches while allowing committed work to be published. */
    default void quiesce() {}
}
