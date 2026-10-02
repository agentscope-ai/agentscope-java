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
package io.agentscope.extensions.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.fasterxml.jackson.core.type.TypeReference;
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

/**
 * Alibaba Cloud OSS backed {@link AgentStateStore}.
 *
 * <p>State objects are stored as JSON files in an OSS bucket with the following key layout:
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
 * OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
 *
 * AgentStateStore store = OssAgentStateStore.builder()
 *     .ossClient(ossClient)
 *     .bucketName("my-agentscope-bucket")
 *     .keyPrefix("agentscope/state/")
 *     .build();
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .stateStore(store)
 *     .build();
 * }</pre>
 */
public class OssAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope/state/";
    private static final String ANON_USER = "__anon__";
    private static final String JSON_SUFFIX = ".json";
    private static final String LIST_SUFFIX = ".list.json";
    private static final String HASH_SUFFIX = ".list.hash";

    private final OSS ossClient;
    private final String bucketName;
    private final String keyPrefix;

    private OssAgentStateStore(Builder builder) {
        this.ossClient = Objects.requireNonNull(builder.ossClient, "ossClient must not be null");
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
            ListObjectsV2Request request = new ListObjectsV2Request(bucketName);
            request.setPrefix(prefix);
            request.setMaxKeys(1);
            ListObjectsV2Result result = ossClient.listObjectsV2(request);
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
            String stateKey = stateObjectKey(userId, sessionId, key);
            String listKey = listObjectKey(userId, sessionId, key);
            String hashKey = hashObjectKey(userId, sessionId, key);
            if (ossClient.doesObjectExist(bucketName, stateKey)) {
                ossClient.deleteObject(bucketName, stateKey);
            }
            if (ossClient.doesObjectExist(bucketName, listKey)) {
                ossClient.deleteObject(bucketName, listKey);
            }
            if (ossClient.doesObjectExist(bucketName, hashKey)) {
                ossClient.deleteObject(bucketName, hashKey);
            }
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
        ossClient.shutdown();
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
        ossClient.putObject(bucketName, objectKey, new ByteArrayInputStream(bytes));
    }

    private String getString(String objectKey) {
        if (!ossClient.doesObjectExist(bucketName, objectKey)) {
            return null;
        }
        try (OSSObject obj = ossClient.getObject(bucketName, objectKey);
                InputStream is = obj.getObjectContent()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read OSS object: " + objectKey, e);
        }
    }

    private List<String> listAllKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request request = new ListObjectsV2Request(bucketName);
            request.setPrefix(prefix);
            request.setMaxKeys(1000);
            if (continuationToken != null) {
                request.setContinuationToken(continuationToken);
            }
            ListObjectsV2Result result = ossClient.listObjectsV2(request);
            for (OSSObjectSummary summary : result.getObjectSummaries()) {
                keys.add(summary.getKey());
            }
            continuationToken = result.isTruncated() ? result.getNextContinuationToken() : null;
        } while (continuationToken != null);
        return keys;
    }

    private void deleteKeys(List<String> keys) {
        for (int i = 0; i < keys.size(); i += 1000) {
            List<String> batch = keys.subList(i, Math.min(i + 1000, keys.size()));
            ossClient.deleteObjects(
                    new DeleteObjectsRequest(bucketName).withKeys(batch).withQuiet(true));
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

        private OSS ossClient;
        private String bucketName;
        private String keyPrefix = DEFAULT_KEY_PREFIX;

        public Builder ossClient(OSS ossClient) {
            this.ossClient = ossClient;
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

        public OssAgentStateStore build() {
            return new OssAgentStateStore(this);
        }
    }
}
