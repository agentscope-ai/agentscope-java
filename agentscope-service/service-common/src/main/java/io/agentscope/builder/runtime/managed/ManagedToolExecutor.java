/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import java.util.Base64;

/** Hands boundary: start/query/cancel use the same executionId, including after worker replacement. */
public interface ManagedToolExecutor {
    /** Bytes are immutable source output. A terminal page covers output through size, or declares a gap. */
    record OutputPage(
            long offset, long nextOffset, String data, long size, boolean terminal, String gap) {
        public OutputPage {
            if (offset < 0 || nextOffset < offset || size < nextOffset || data == null)
                throw new IllegalArgumentException("Invalid output capture position");
            int bytes = Base64.getDecoder().decode(data).length;
            if (bytes > ManagedToolEvents.CHUNK_BYTES
                    || (gap == null && nextOffset - offset != bytes)
                    || (gap != null && (gap.isBlank() || gap.length() > 256 || bytes != 0)))
                throw new IllegalArgumentException("Invalid output capture coverage");
        }
    }

    default boolean streamsOutput(ToolTask task) {
        return false;
    }

    default OutputPage output(ManagedScope scope, ToolTask task, long offset) {
        throw new UnsupportedOperationException("This execution has no replayable output source");
    }

    enum State {
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        NOT_FOUND,
        UNKNOWN
    }

    record Outcome(State state, String result) {
        public Outcome {
            if (state == null || (terminal(state) && result == null))
                throw new IllegalArgumentException("Confirmed tool outcome needs a durable result");
        }

        public boolean terminal() {
            return terminal(state);
        }

        private static boolean terminal(State state) {
            return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED;
        }
    }

    /** True only when the external launcher atomically deduplicates stable executionId values. */
    default boolean idempotentStart() {
        return false;
    }

    /** A child Turn must not hold a Hands slot while it waits for its own tools. */
    default boolean waitForCompletion(ToolTask task) {
        return true;
    }

    String start(ManagedScope scope, ToolTask task);

    Outcome query(ManagedScope scope, ToolTask task);

    Outcome cancel(ManagedScope scope, ToolTask task);
}
