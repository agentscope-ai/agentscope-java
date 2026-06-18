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

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * S3-compatible object storage backed {@link AgentStateStore}.
 *
 * <p>State objects are stored as JSON files in an object storage bucket with the following key
 * layout:
 *
 * <pre>
 * {keyPrefix}{userId}/{sessionId}/{stateKey}.json       - single State value
 * {keyPrefix}{userId}/{sessionId}/{stateKey}.list.json  - List&lt;State&gt; as JSON array
 * {keyPrefix}{userId}/{sessionId}/{stateKey}.list.hash  - hash for incremental append detection
 * </pre>
 */
public class OssAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope/state/";
    private static final String ANON_USER = "__anon__";
    private static final String JSON_SUFFIX = ".json";
    private static final String LIST_SUFFIX = ".list.json";
    private static final String HASH_SUFFIX = ".list.hash";

    private final S3Client s3Client;
    private final String bucketName;
    private final String keyPrefix;

    private OssAgentStateStore(Builder builder) {
        this.s3Client = Objects.requireNonNull(builder.s3Client, "s3Client must not be null");
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
        try {
            putString(
                    stateObjectKey(userId, sessionId, key), JsonUtils.getJsonCodec().toJson(value));
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
                putString(listKey, JsonUtils.getJsonCodec().toJson(values));
            }
            putString(hashKey, currentHash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        try {
            String json = getString(stateObjectKey(userId, sessionId, key));
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
        try {
            String json = getString(listObjectKey(userId, sessionId, key));
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
        try {
            return S3ObjectStoreSupport.hasObjectsWithPrefix(
                    s3Client, bucketName, sessionPrefix(userId, sessionId));
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence", e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        try {
            List<String> keys = listAllKeys(sessionPrefix(userId, sessionId));
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
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(stateObjectKey(userId, sessionId, key))
                            .build());
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(listObjectKey(userId, sessionId, key))
                            .build());
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(hashObjectKey(userId, sessionId, key))
                            .build());
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
        s3Client.close();
    }

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
        S3ObjectStoreSupport.putString(s3Client, bucketName, objectKey, content);
    }

    private String getString(String objectKey) {
        return S3ObjectStoreSupport.getString(s3Client, bucketName, objectKey);
    }

    private List<String> listAllKeys(String prefix) {
        return S3ObjectStoreSupport.listAllKeys(s3Client, bucketName, prefix);
    }

    private void deleteKeys(List<String> keys) {
        S3ObjectStoreSupport.deleteKeys(s3Client, bucketName, keys);
    }

    private static String normalizePrefix(String prefix) {
        return S3ObjectStoreSupport.normalizePrefix(prefix, DEFAULT_KEY_PREFIX);
    }

    public static class Builder {

        private S3Client s3Client;
        private String bucketName;
        private String keyPrefix = DEFAULT_KEY_PREFIX;

        public Builder s3Client(S3Client s3Client) {
            this.s3Client = s3Client;
            return this;
        }

        /**
         * Backwards-compatible alias for {@link #s3Client(S3Client)}.
         */
        public Builder ossClient(S3Client s3Client) {
            return s3Client(s3Client);
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
