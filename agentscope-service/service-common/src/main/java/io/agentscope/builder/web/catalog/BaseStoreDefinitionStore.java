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
package io.agentscope.builder.web.catalog;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link DefinitionStore} backed by the shared {@link BaseStore} under namespace {@code
 * definitions / {ownerId} / {agentId}}.
 */
@Component
public class BaseStoreDefinitionStore implements DefinitionStore {

    static final String NS_ROOT = "definitions";

    private final BaseStore baseStore;

    public BaseStoreDefinitionStore(BaseStore baseStore) {
        this.baseStore = Objects.requireNonNull(baseStore, "baseStore");
    }

    @Override
    public void putText(String ownerId, String agentId, String relativePath, String content) {
        String key = normalizeKey(relativePath);
        Map<String, Object> value = new HashMap<>();
        value.put("content", content != null ? content : "");
        value.put("encoding", "utf-8");
        value.put("modified_at", Instant.now().toString());
        baseStore.put(namespace(ownerId, agentId), key, value);
    }

    @Override
    public Optional<String> getText(String ownerId, String agentId, String relativePath) {
        StoreItem item = baseStore.get(namespace(ownerId, agentId), normalizeKey(relativePath));
        if (item == null || item.value() == null) {
            return Optional.empty();
        }
        Object content = item.value().get("content");
        return content == null ? Optional.empty() : Optional.of(String.valueOf(content));
    }

    @Override
    public void delete(String ownerId, String agentId, String relativePath) {
        baseStore.delete(namespace(ownerId, agentId), normalizeKey(relativePath));
    }

    @Override
    public void deletePrefix(String ownerId, String agentId, String prefix) {
        String normalized = normalizeKey(prefix == null ? "" : prefix);
        for (String key : list(ownerId, agentId, normalized)) {
            baseStore.delete(namespace(ownerId, agentId), key);
        }
    }

    @Override
    public List<String> list(String ownerId, String agentId, String prefix) {
        String normalizedPrefix = normalizeKey(prefix == null ? "" : prefix);
        List<StoreItem> items = searchAll(namespace(ownerId, agentId));
        Map<String, Boolean> keys = new LinkedHashMap<>();
        for (StoreItem item : items) {
            if (item == null || item.key() == null) {
                continue;
            }
            String key = normalizeKey(item.key());
            if (normalizedPrefix.isEmpty()
                    || key.equals(normalizedPrefix)
                    || key.startsWith(normalizedPrefix + "/")) {
                keys.put(key, Boolean.TRUE);
            }
        }
        return new ArrayList<>(keys.keySet());
    }

    private List<StoreItem> searchAll(List<String> ns) {
        List<StoreItem> all = new ArrayList<>();
        int offset = 0;
        final int page = 200;
        while (true) {
            List<StoreItem> batch = baseStore.search(ns, page, offset);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < page) {
                break;
            }
            offset += batch.size();
        }
        return all;
    }

    static List<String> namespace(String ownerId, String agentId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId is required");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        return List.of(NS_ROOT, ownerId.trim(), agentId.trim());
    }

    static String normalizeKey(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String key = relativePath.replace('\\', '/').trim();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        while (key.endsWith("/") && key.length() > 1) {
            key = key.substring(0, key.length() - 1);
        }
        if (key.contains("..")) {
            throw new IllegalArgumentException(
                    "relativePath must not contain '..': " + relativePath);
        }
        return key;
    }
}
