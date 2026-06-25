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
package io.agentscope.extensions.cos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.DeleteObjectsRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tencent Cloud COS backed {@link AgentStateStore}.
 *
 * <p>State objects are stored as JSON files in a COS bucket with the following key layout:
 *
 * <pre>
 * {keyPrefix}{userId}/{sessionId}/{stateKey}.json       — single State value
 * {keyPrefix}{userId}/{sessionId}/{stateKey}.list.json  — List&lt;State&gt; as JSON array
 * {keyPrefix}{userId}/{sessionId}/{stateKey}.list.hash  — hash for incremental append detection
 * </pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * COSClient cosClient = new COSClient(cred, clientConfig);
 *
 * AgentStateStore store = CosAgentStateStore.builder()
 *     .cosClient(cosClient)
 *     .bucketName("my-agentscope-bucket")
 *     .keyPrefix("agentscope/state/")
 *     .build();
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .stateStore(store)
 *     .build();
 * }</pre>
 */
public class CosAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope/state/";
    private static final String ANON_USER = "__anon__";
    private static final String JSON_SUFFIX = ".json";
    private static final String LIST_SUFFIX = ".list.json";
    private static final String HASH_SUFFIX = ".list.hash";

    private final COSClient cosClient;
    private final String bucketName;
    private final String keyPrefix;

    private CosAgentStateStore(Builder builder) {
        this.cosClient = Objects.requireNonNull(builder.cosClient, "cosClient must not be null");
        if (builder.bucketName == null || builder.bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = builder.bucketName;
        this.keyPrefix = normalizePrefix(builder.keyPrefix);
    }

    public static Builder builder() {
        return new Builder();
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
            ListObjectsRequest request = new ListObjectsRequest();
            request.setBucketName(bucketName);
            request.setPrefix(prefix);
            request.setMaxKeys(1);
            ObjectListing result = cosClient.listObjects(request);
            return result.getObjectSummaries() != null && !result.getObjectSummaries().isEmpty();
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
        cosClient.shutdown();
    }

    // ---- internal helpers ----

    private String stateObjectKey(String userId, String sessionId, String key) {
        return sessionPrefix(userId, sessionId) + key + JSON_SUFFIX;
    }

    private String listObjectKey(String userId, String sessionId, String key) {
        return sessionPrefix(userId, sessionId) + key + LIST_SUFFIX;
    }

    private String hashObjectKey(String userId, String sessionId, String key) {
        return sessionPrefix(userId, sessionId) + key + HASH_SUFFIX;
    }

    private String sessionPrefix(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return keyPrefix + normalizeUser(userId) + "/" + sessionId + "/";
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private void putString(String objectKey, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        cosClient.putObject(
                new PutObjectRequest(
                        bucketName, objectKey, new ByteArrayInputStream(bytes), metadata));
    }

    private String getString(String objectKey) {
        if (!objectExists(objectKey)) {
            return null;
        }
        try (COSObject obj = cosClient.getObject(new GetObjectRequest(bucketName, objectKey));
                InputStream is = obj.getObjectContent()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read COS object: " + objectKey, e);
        }
    }

    private boolean objectExists(String objectKey) {
        try {
            cosClient.getObjectMetadata(bucketName, objectKey);
            return true;
        } catch (CosServiceException e) {
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw new RuntimeException("Failed to check COS object existence: " + objectKey, e);
        }
    }

    private void deleteIfExists(String objectKey) {
        if (objectExists(objectKey)) {
            cosClient.deleteObject(bucketName, objectKey);
        }
    }

    private List<String> listAllKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        String marker = null;
        do {
            ListObjectsRequest request = new ListObjectsRequest();
            request.setBucketName(bucketName);
            request.setPrefix(prefix);
            request.setMaxKeys(1000);
            if (marker != null) {
                request.setMarker(marker);
            }
            ObjectListing result = cosClient.listObjects(request);
            for (COSObjectSummary summary : result.getObjectSummaries()) {
                keys.add(summary.getKey());
            }
            marker = result.isTruncated() ? result.getNextMarker() : null;
        } while (marker != null);
        return keys;
    }

    private void deleteKeys(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        for (int i = 0; i < keys.size(); i += 1000) {
            List<String> batch = keys.subList(i, Math.min(i + 1000, keys.size()));
            DeleteObjectsRequest req = new DeleteObjectsRequest(bucketName);
            req.setKeys(
                    batch.stream()
                            .map(DeleteObjectsRequest.KeyVersion::new)
                            .collect(Collectors.toList()));
            cosClient.deleteObjects(req);
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return DEFAULT_KEY_PREFIX;
        }
        String p = prefix.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (!p.isEmpty() && !p.endsWith("/")) {
            p = p + "/";
        }
        return p.isEmpty() ? DEFAULT_KEY_PREFIX : p;
    }

    public static class Builder {

        private COSClient cosClient;
        private String bucketName;
        private String keyPrefix = DEFAULT_KEY_PREFIX;

        public Builder cosClient(COSClient cosClient) {
            this.cosClient = cosClient;
            return this;
        }

        public Builder bucketName(String bucketName) {
            this.bucketName = bucketName;
            return this;
        }

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public CosAgentStateStore build() {
            return new CosAgentStateStore(this);
        }
    }
}
