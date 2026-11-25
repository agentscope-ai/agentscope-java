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
package io.agentscope.durable;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableTypes.ThreadKey;
import java.util.List;
import java.util.Objects;

/** The Agent's view of an asynchronous model execution. Operations perform blocking storage I/O. */
public interface ModelExecutions {
    record Key(ThreadKey thread, String turnId, String callId) {
        public Key {
            Objects.requireNonNull(thread, "thread");
            DurableJson.text(turnId, "turnId");
            DurableJson.text(callId, "callId");
        }

        public String wakeKey() {
            return "model-ready:" + DurableJson.hash(this);
        }
    }

    record Request(Key key, String route, JsonNode payload) {
        public Request {
            Objects.requireNonNull(key, "key");
            DurableJson.text(route, "model route");
            if (payload == null || !payload.isObject())
                throw new IllegalArgumentException("Model request payload must be an object");
        }
    }

    enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        UNKNOWN;

        public boolean settled() {
            return this != PENDING && this != RUNNING;
        }
    }

    record Outcome(Status status, String error, boolean retryable) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            if (!status.settled() || retryable && status != Status.FAILED)
                throw new IllegalArgumentException("Invalid model outcome");
        }
    }

    record View(Status status, long generation, long nextSequence, Outcome outcome) {
        public boolean available() {
            return nextSequence > 0 || outcome != null;
        }
    }

    /** A batch cursor always points to the first sequence of a committed batch. */
    record Batch(Key key, long generation, long firstSequence, List<JsonNode> chunks) {
        public Batch {
            Objects.requireNonNull(key, "key");
            chunks = List.copyOf(chunks);
            if (generation < 1
                    || firstSequence < 0
                    || chunks.isEmpty()
                    || chunks.stream().anyMatch(chunk -> !chunk.isObject()))
                throw new IllegalArgumentException("Invalid model output batch");
        }

        public long nextSequence() {
            return Math.addExact(firstSequence, chunks.size());
        }
    }

    void submit(Request request);

    /** Null before this request is admitted. */
    View query(Key key);

    /** Null when the requested batch is not committed yet. */
    Batch readOutput(Key key, long generation, long firstSequence);
}
