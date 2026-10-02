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
package io.agentscope.harness.agent.skill.curator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed {@link SkillUsageBackend} storing one record per skill under
 * {@code ["skills", "usage"]} with CAS retries via {@link BaseStore#putIfVersion}.
 */
final class BaseStoreSkillUsageBackend implements SkillUsageBackend {

    private static final Logger log = LoggerFactory.getLogger(BaseStoreSkillUsageBackend.class);

    private static final List<String> NAMESPACE = List.of("skills", "usage");
    private static final int MAX_CAS_RETRIES = 5;
    private static final int SCAN_PAGE_SIZE = 1000;

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final BaseStore store;

    BaseStoreSkillUsageBackend(BaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Map<String, SkillUsageRecord> loadAll() {
        Map<String, SkillUsageRecord> out = new LinkedHashMap<>();
        try {
            List<StoreItem> items = store.search(NAMESPACE, SCAN_PAGE_SIZE, 0);
            if (items == null) {
                return out;
            }
            for (StoreItem item : items) {
                SkillUsageRecord record = recordFromItem(item);
                if (record != null && item.key() != null) {
                    out.put(item.key(), record);
                }
            }
        } catch (RuntimeException e) {
            log.debug("BaseStoreSkillUsageBackend.loadAll() failed: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public Optional<SkillUsageRecord> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            StoreItem item = store.get(NAMESPACE, name);
            SkillUsageRecord record = recordFromItem(item);
            return Optional.ofNullable(record);
        } catch (RuntimeException e) {
            log.debug("BaseStoreSkillUsageBackend.get({}) failed: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void mutate(String name, UnaryOperator<SkillUsageRecord> mutator) {
        if (name == null || name.isBlank()) {
            return;
        }
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            StoreItem item;
            try {
                item = store.get(NAMESPACE, name);
            } catch (RuntimeException e) {
                log.debug(
                        "BaseStoreSkillUsageBackend.mutate({}) read failed: {}",
                        name,
                        e.getMessage());
                return;
            }
            long expectedVersion = item != null ? item.version() : 0L;
            SkillUsageRecord current =
                    item != null ? recordFromItem(item) : SkillUsageRecord.defaults();
            if (current == null) {
                current = SkillUsageRecord.defaults();
            }
            SkillUsageRecord updated = mutator.apply(current);
            if (updated == current) {
                return; // explicit no-op (e.g. provenance gate denied)
            }
            if (updated == null) {
                if (item == null) {
                    return;
                }
                try {
                    store.delete(NAMESPACE, name);
                } catch (RuntimeException e) {
                    log.debug(
                            "BaseStoreSkillUsageBackend.mutate({}) delete failed: {}",
                            name,
                            e.getMessage());
                }
                return;
            }
            Map<String, Object> value = recordToMap(updated);
            try {
                if (store.putIfVersion(NAMESPACE, name, value, expectedVersion)) {
                    return;
                }
            } catch (RuntimeException e) {
                log.debug(
                        "BaseStoreSkillUsageBackend.mutate({}) CAS failed: {}",
                        name,
                        e.getMessage());
                return;
            }
        }
        log.warn("BaseStoreSkillUsageBackend.mutate({}) exhausted CAS retries", name);
    }

    @Override
    public void replaceAll(Map<String, SkillUsageRecord> data) {
        // Multi-key store: upsert the provided keys only. Deleting absent keys would require a
        // cross-key transaction we do not have; callers that need a true wipe use forget().
        if (data == null || data.isEmpty()) {
            return;
        }
        for (Map.Entry<String, SkillUsageRecord> entry : data.entrySet()) {
            String name = entry.getKey();
            SkillUsageRecord value = entry.getValue();
            if (value == null) {
                mutate(name, rec -> null);
            } else {
                mutate(name, rec -> value);
            }
        }
    }

    private static SkillUsageRecord recordFromItem(StoreItem item) {
        if (item == null || item.value() == null) {
            return null;
        }
        try {
            return JSON.convertValue(item.value(), SkillUsageRecord.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> recordToMap(SkillUsageRecord record) {
        return JSON.convertValue(record, new TypeReference<Map<String, Object>>() {});
    }
}
