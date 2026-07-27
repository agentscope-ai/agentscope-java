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
package io.agentscope.harness.agent.gateway;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed {@link SubagentRegistry} backed by a {@link BaseStore}. Because the store is supplied
 * by a {@link io.agentscope.harness.agent.DistributedStore} (Redis / OSS / MySQL), exposure
 * records become visible to every node, so any replica can resolve a {@code subagentId} and
 * re-materialize the subagent.
 *
 * <p>Records are stored under the namespace {@code ["subagents", "exposed"]} keyed by
 * {@code subagentId}. TTL is enforced lazily on {@link #find}: an elapsed record is deleted and
 * reported as absent.
 *
 * <p>This registry deliberately stores only routing/identity metadata. Concurrency safety for the
 * subagent's mutable conversation state is the responsibility of the distributed
 * {@link io.agentscope.core.state.AgentStateStore} (and {@link BaseStore#putIfVersion} where
 * explicit optimistic guarding is desired), not of this registry.
 */
public final class StoreBackedSubagentRegistry implements SubagentRegistry {

    private static final Logger log = LoggerFactory.getLogger(StoreBackedSubagentRegistry.class);

    private static final List<String> NAMESPACE = List.of("subagents", "exposed");
    private static final int SCAN_PAGE_SIZE = 1000;

    private final BaseStore store;

    public StoreBackedSubagentRegistry(BaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void register(SubagentRecord record) {
        if (record == null || record.subagentId() == null) {
            return;
        }
        try {
            store.put(NAMESPACE, record.subagentId(), record.toMap());
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to persist exposed-subagent record {}: {}",
                    record.subagentId(),
                    e.getMessage());
        }
    }

    @Override
    public Optional<SubagentRecord> find(String subagentId) {
        if (subagentId == null) {
            return Optional.empty();
        }
        StoreItem item;
        try {
            item = store.get(NAMESPACE, subagentId);
        } catch (RuntimeException e) {
            log.warn("Failed to read exposed-subagent record {}: {}", subagentId, e.getMessage());
            return Optional.empty();
        }
        if (item == null || item.value() == null) {
            return Optional.empty();
        }
        SubagentRecord record = SubagentRecord.fromMap(item.value());
        if (record == null) {
            return Optional.empty();
        }
        if (record.isExpired(Instant.now())) {
            revoke(subagentId);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public Optional<SubagentRecord> findByLineage(
            String userId, String parentSessionId, String childSessionId) {
        if (isBlank(userId) || isBlank(parentSessionId) || isBlank(childSessionId)) {
            return Optional.empty();
        }
        try {
            int offset = 0;
            SubagentRecord latest = null;
            while (true) {
                List<StoreItem> items = store.search(NAMESPACE, SCAN_PAGE_SIZE, offset);
                if (items == null || items.isEmpty()) {
                    break;
                }
                for (StoreItem item : items) {
                    SubagentRecord record = SubagentRecord.fromMap(item.value());
                    if (record != null
                            && !record.isExpired(Instant.now())
                            && Objects.equals(userId, record.userId())
                            && Objects.equals(parentSessionId, record.parentSessionId())
                            && Objects.equals(childSessionId, record.sessionId())
                            && (latest == null
                                    || Comparator.nullsFirst(Instant::compareTo)
                                                    .compare(record.createdAt(), latest.createdAt())
                                            > 0)) {
                        latest = record;
                    }
                }
                if (items.size() < SCAN_PAGE_SIZE) {
                    break;
                }
                offset += items.size();
            }
            return Optional.ofNullable(latest);
        } catch (RuntimeException e) {
            log.warn("Failed to resolve exposed-subagent lineage: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void revoke(String subagentId) {
        if (subagentId == null) {
            return;
        }
        try {
            store.delete(NAMESPACE, subagentId);
        } catch (RuntimeException e) {
            log.warn("Failed to revoke exposed-subagent record {}: {}", subagentId, e.getMessage());
        }
    }

    @Override
    public void revokeByParentSession(String parentSessionId) {
        if (parentSessionId == null) {
            return;
        }
        try {
            int offset = 0;
            List<String> matches = new java.util.ArrayList<>();
            while (true) {
                List<StoreItem> items = store.search(NAMESPACE, SCAN_PAGE_SIZE, offset);
                if (items == null || items.isEmpty()) {
                    break;
                }
                for (StoreItem item : items) {
                    SubagentRecord record = SubagentRecord.fromMap(item.value());
                    if (record != null && parentSessionId.equals(record.parentSessionId())) {
                        matches.add(record.subagentId());
                    }
                }
                if (items.size() < SCAN_PAGE_SIZE) {
                    break;
                }
                offset += items.size();
            }
            for (String subagentId : matches) {
                revoke(subagentId);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to revoke exposed subagents for parent session {}: {}",
                    parentSessionId,
                    e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
