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

import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.ThreadKey;
import java.util.Objects;

/** A hint to refresh controls for a particular leased Turn, without carrying the control itself. */
public record TurnSignal(ThreadKey thread, String turnId, String owner, long epoch) {
    public TurnSignal {
        Objects.requireNonNull(thread);
        DurableJson.text(turnId, "turnId");
        DurableJson.text(owner, "owner");
        if (epoch < 1) throw new IllegalArgumentException("Invalid signal lease epoch");
    }

    /** Runtime claim IDs append an invocation identity to the runner identity. */
    public String runnerId() {
        int separator = owner.indexOf(':');
        return separator < 0 ? owner : owner.substring(0, separator);
    }

    boolean matches(Lease lease) {
        return lease != null
                && thread.equals(lease.thread())
                && turnId.equals(lease.turnId())
                && owner.equals(lease.owner())
                && epoch == lease.epoch();
    }
}
