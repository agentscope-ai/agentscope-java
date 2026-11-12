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
package io.agentcore.managed.server.runtime.managed;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Runtime copy of durable inputs. Stream position is never an application receipt. */
public interface ManagedInputInbox {
    enum Type {
        STEER,
        INJECT,
        REPLY,
        CANCEL
    }

    record Signal(
            String inputId, long sequence, Type type, String actionId, String payloadReference) {
        public Signal {
            ManagedScope.requireText(inputId, "input id");
            if (sequence <= 0)
                throw new IllegalArgumentException("Input sequence must be positive");
            Objects.requireNonNull(type, "input type");
            if (type == Type.REPLY) ManagedScope.requireText(actionId, "reply action id");
            ManagedScope.requireText(payloadReference, "input payload reference");
        }
    }

    record Entry(String cursor, Signal signal) {}

    String append(ManagedScope scope, String turnId, Signal signal);

    List<Entry> read(ManagedScope scope, String turnId, String after, int limit, Duration block);

    boolean cancelled(ManagedScope scope, String turnId);

    /** Called only after a committed terminal state; live input copies must not expire. */
    void expire(ManagedScope scope, String turnId, Duration retention);
}
