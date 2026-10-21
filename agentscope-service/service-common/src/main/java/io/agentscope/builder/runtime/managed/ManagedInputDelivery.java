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

import io.agentscope.builder.runtime.managed.ManagedAdmissionStore.Input;
import io.agentscope.builder.runtime.managed.ManagedInputInbox.Signal;
import java.time.Duration;
import java.util.List;

/** Redis is a replayable runtime copy; acceptance and application remain durable DB facts. */
public final class ManagedInputDelivery {
    private final ManagedAdmissionStore store;
    private final ManagedInputInbox inbox;

    public ManagedInputDelivery(ManagedAdmissionStore store, ManagedInputInbox inbox) {
        this.store = store;
        this.inbox = inbox;
    }

    public boolean cancelled(ManagedScope scope, String turn) {
        return inbox.cancelled(scope, turn);
    }

    public String deliver(ManagedScope scope, Input input) {
        String cursor =
                inbox.append(
                        scope,
                        input.turnId(),
                        new Signal(
                                input.id(),
                                input.sequence(),
                                input.type(),
                                input.actionId(),
                                "ma:input:" + input.id()));
        store.confirmInputDelivery(scope, input.id());
        return cursor;
    }

    /** Rebuild after a lost Redis copy. No accepted Input is inferred from a Redis stream cursor. */
    public int refill(ManagedScope scope, String turn, long afterSequence, int limit) {
        int count = 0;
        var session = store.session(scope);
        if (!"active".equals(session.lifecycle())) {
            inbox.append(
                    scope,
                    turn,
                    new Signal(
                            "lifecycle_" + session.lifecycleRevision(),
                            1,
                            ManagedInputInbox.Type.CANCEL,
                            null,
                            "ma:lifecycle:" + session.lifecycleCommand()));
            count++;
        }
        for (var input : store.pendingInputs(scope, turn, limit)) {
            if (!"accepted".equals(input.status())) continue;
            deliver(scope, input);
            count++;
        }
        return count;
    }

    /** Resolve durable payloads in business order, even when two Portals append Redis in reverse order. */
    public List<Input> pending(ManagedScope scope, String turn, long afterSequence, int limit) {
        return store.pendingInputs(scope, turn, limit).stream()
                .filter(input -> "accepted".equals(input.status()))
                .toList();
    }

    public List<ManagedInputInbox.Entry> wake(
            ManagedScope scope,
            String turn,
            String cursor,
            long afterSequence,
            int limit,
            Duration block) {
        var entries = inbox.read(scope, turn, cursor, limit, block);
        if (!entries.isEmpty()) return entries;
        // An empty timed read also detects data loss when the Redis server remains reachable.
        if (refill(scope, turn, afterSequence, limit) == 0) return List.of();
        return inbox.read(scope, turn, "0-0", limit, Duration.ZERO);
    }
}
