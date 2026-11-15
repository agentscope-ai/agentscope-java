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

import io.agentscope.durable.DurableTypes.LiveEvent;
import java.util.function.Consumer;

/**
 * Best-effort fan-out for live presentation events. It is deliberately separate from
 * {@link SessionBackend}: publishing never creates durable history, cursors or projections.
 */
public interface LiveEvents {
    /** No live delivery. Durable execution and committed event history remain unaffected. */
    static LiveEvents none() {
        return new LiveEvents() {
            @Override
            public void publish(LiveEvent event) {}

            @Override
            public AutoCloseable subscribe(Consumer<LiveEvent> listener) {
                return () -> {};
            }
        };
    }

    /** Thread-safe process-local fan-out suitable for an integrated Brain/API deployment. */
    static LiveEvents local() {
        return new LocalLiveEvents();
    }

    void publish(LiveEvent event);

    AutoCloseable subscribe(Consumer<LiveEvent> listener);
}
