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
package io.agentscope.extensions.oss.base;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Vendor-independent implementation of {@link AgentStateStore} on top of an
 * {@link OssAdapter}.
 *
 * <p>State objects are stored as JSON files with the following key layout:
 *
 * <pre>
 * {keyPrefix}{userId}/{sessionId}/{stateKey}.json       — single State value
 * {keyPrefix}{userId}/{sessionId}/{stateKey}.list.json  — List&lt;State&gt; as JSON array
 * {keyPrefix}{userId}/{sessionId}/{stateKey}.list.hash  — hash for incremental append detection
 * </pre>
 */
public abstract class AbstractOssAgentStateStore implements AgentStateStore {

    protected static final String DEFAULT_KEY_PREFIX = "agentscope/state/";
    protected static final String ANON_USER = "__anon__";
    protected static final String JSON_SUFFIX = ".json";
    protected static final String LIST_SUFFIX = ".list.json";
    protected static final String HASH_SUFFIX = ".list.hash";
    protected static final int LIST_PAGE_SIZE = 1000;
    protected static final int DELETE_BATCH_SIZE = 1000;

    protected final OssAdapter adapter;
    protected final String bucketName;
    protected final String keyPrefix;

    protected AbstractOssAgentStateStore(OssAdapter adapter, String bucketName, String keyPrefix) {
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = bucketName;
        this.keyPrefix = PathUtils.normalizePrefix(keyPrefix, DEFAULT_KEY_PREFIX);
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String objectKey = stateObjectKey(userId, sessionId, key);
        try {
            String json = JsonUtils.getJsonCodec().toJson(value);
            putString(objectKey, json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String listKey = listObjectKey(userId, sessionId, key);
        String hashKey = hashObjectKey(userId, sessionId, key);
        try {
            String currentHash = ListHashUtil.computeHash(values);
            String storedHash = getString(hashKey);
            int existingCount = 0;
            if (storedHash != null) {
                String existingJson = getString(listKey);
                if (existingJson != null) {
                    List<?> existingList =
                            JsonUtils.getJsonCodec()
                                    .fromJson(existingJson, new TypeReference<List<Object>>() {});
                    existingCount = existingList.size();
                }
            }

            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(values, storedHash, existingCount);

            if (needsFullRewrite || values.size() != existingCount) {
                String json = JsonUtils.getJsonCodec().toJson(values);
                putString(listKey, json);
            }

            putString(hashKey, currentHash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String objectKey = stateObjectKey(userId, sessionId, key);
        try {
            String json = getString(objectKey);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String listKey = listObjectKey(userId, sessionId, key);
        try {
            String json = getString(listKey);
            if (json == null) {
                return List.of();
            }
            List<Object> rawList =
                    JsonUtils.getJsonCodec().fromJson(json, new TypeReference<>() {});
            List<T> result = new ArrayList<>(rawList.size());
            for (Object raw : rawList) {
                result.add(JsonUtils.getJsonCodec().convertValue(raw, itemType));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key, e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String prefix = sessionPrefix(userId, sessionId);
        try {
            OssListObjectPage page = adapter.list(prefix, null, 1);
            return page.objects() != null && !page.objects().isEmpty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence", e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String prefix = sessionPrefix(userId, sessionId);
        try {
            List<String> keys = listAllKeys(prefix);
            if (!keys.isEmpty()) {
                deleteKeys(keys);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session", e);
        }
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        try {
            deleteIfExists(stateObjectKey(userId, sessionId, key));
            deleteIfExists(listObjectKey(userId, sessionId, key));
            deleteIfExists(hashObjectKey(userId, sessionId, key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete state key: " + key, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userPrefix = keyPrefix + normalizeUser(userId) + "/";
        try {
            List<String> keys = listAllKeys(userPrefix);
            Set<String> sessionIds = new HashSet<>();
            for (String key : keys) {
                String remainder = key.substring(userPrefix.length());
                int slash = remainder.indexOf('/');
                if (slash > 0) {
                    sessionIds.add(remainder.substring(0, slash));
                }
            }
            return sessionIds;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    @Override
    public void close() {
        adapter.close();
    }

    // ---- internal helpers ----

    protected String stateObjectKey(String userId, String sessionId, String key) {
        return sessionPrefix(userId, sessionId) + key + JSON_SUFFIX;
    }

    protected String listObjectKey(String userId, String sessionId, String key) {
        return sessionPrefix(userId, sessionId) + key + LIST_SUFFIX;
    }

    protected String hashObjectKey(String userId, String sessionId, String key) {
        return sessionPrefix(userId, sessionId) + key + HASH_SUFFIX;
    }

    protected String sessionPrefix(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return keyPrefix + normalizeUser(userId) + "/" + sessionId + "/";
    }

    protected static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    protected void putString(String objectKey, String content) {
        adapter.putBytes(objectKey, content.getBytes(StandardCharsets.UTF_8));
    }

    protected String getString(String objectKey) {
        byte[] bytes = adapter.getBytes(objectKey);
        if (bytes == null) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    protected void deleteIfExists(String objectKey) {
        if (adapter.exists(objectKey)) {
            adapter.delete(objectKey);
        }
    }

    protected List<String> listAllKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            OssListObjectPage page = adapter.list(prefix, continuationToken, LIST_PAGE_SIZE);
            for (OssObjectSummary summary : page.objects()) {
                keys.add(summary.key());
            }
            continuationToken = page.nextContinuationToken();
        } while (continuationToken != null);
        return keys;
    }

    protected void deleteKeys(List<String> keys) {
        for (int i = 0; i < keys.size(); i += DELETE_BATCH_SIZE) {
            List<String> batch = keys.subList(i, Math.min(i + DELETE_BATCH_SIZE, keys.size()));
            adapter.deleteBatch(batch);
        }
    }
}
