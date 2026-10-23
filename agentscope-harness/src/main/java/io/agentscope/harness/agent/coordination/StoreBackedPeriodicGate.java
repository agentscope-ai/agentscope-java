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
package io.agentscope.harness.agent.coordination;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed {@link PeriodicGate} backed by {@link BaseStore#putIfVersion}. Enables cross-node
 * coordination of periodic background work (memory maintenance, skill curator, etc.).
 */
public final class StoreBackedPeriodicGate implements PeriodicGate {

    private static final Logger log = LoggerFactory.getLogger(StoreBackedPeriodicGate.class);

    private static final List<String> NAMESPACE = List.of("coordination", "periodic");
    private static final int MAX_CAS_RETRIES = 5;
    private static final Instant EPOCH = Instant.EPOCH;

    private final BaseStore store;

    public StoreBackedPeriodicGate(BaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public boolean tryClaim(String name, Duration minGap) {
        if (name == null || name.isBlank() || minGap == null) {
            return false;
        }
        Instant now = Instant.now();
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            StoreItem item;
            try {
                item = store.get(NAMESPACE, name);
            } catch (RuntimeException e) {
                log.warn("Failed to read periodic gate {}: {}", name, e.getMessage());
                return false;
            }
            long expectedVersion = item != null ? item.version() : 0L;
            Instant last = readLastClaimAt(item);
            if (Duration.between(last, now).compareTo(minGap) < 0) {
                return false;
            }
            Map<String, Object> value = new HashMap<>();
            value.put("lastClaimAt", now.toEpochMilli());
            try {
                if (store.putIfVersion(NAMESPACE, name, value, expectedVersion)) {
                    return true;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to claim periodic gate {}: {}", name, e.getMessage());
                return false;
            }
        }
        return false;
    }

    private static Instant readLastClaimAt(StoreItem item) {
        if (item == null || item.value() == null) {
            return EPOCH;
        }
        Object ts = item.value().get("lastClaimAt");
        if (ts instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        return EPOCH;
    }
}
