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
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * S3-compatible object storage backed {@link BaseStore} for the harness remote filesystem.
 */
public class OssBaseStore implements BaseStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope/store/";
    private static final String JSON_SUFFIX = ".json";
    private static final String LIST_SUFFIX = ".list.json";
    private static final String VERSION_SUFFIX = ".version";

    private final S3Client s3Client;
    private final String bucketName;
    private final String keyPrefix;

    private OssBaseStore(Builder builder) {
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
    public StoreItem get(List<String> namespace, String key) {
        String dataKey = dataObjectKey(namespace, key);
        String versionKey = versionObjectKey(namespace, key);
        try {
            String json = getString(dataKey);
            if (json == null) {
                return null;
            }
            Map<String, Object> value =
                    JsonUtils.getJsonCodec().fromJson(json, new TypeReference<>() {});
            long version = readVersion(versionKey);
            return new StoreItem(key, value, version);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get item: " + key, e);
        }
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        String dataKey = dataObjectKey(namespace, key);
        String versionKey = versionObjectKey(namespace, key);
        try {
            putString(dataKey, JsonUtils.getJsonCodec().toJson(value));
            putString(versionKey, String.valueOf(readVersion(versionKey) + 1));
        } catch (Exception e) {
            throw new RuntimeException("Failed to put item: " + key, e);
        }
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        String dataKey = dataObjectKey(namespace, key);
        String versionKey = versionObjectKey(namespace, key);
        try {
            long currentVersion = readVersion(versionKey);
            if (currentVersion != expectedVersion) {
                return false;
            }
            putString(dataKey, JsonUtils.getJsonCodec().toJson(value));
            putString(versionKey, String.valueOf(currentVersion + 1));
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to putIfVersion item: " + key, e);
        }
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String prefix = namespacePrefix(namespace);
        try {
            List<String> dataKeys = new ArrayList<>();
            for (String key : listAllKeys(prefix)) {
                if (key.endsWith(JSON_SUFFIX) && !key.endsWith(LIST_SUFFIX)) {
                    dataKeys.add(key);
                }
            }

            Collections.sort(dataKeys);
            int start = Math.min(offset, dataKeys.size());
            int end = Math.min(start + limit, dataKeys.size());
            List<StoreItem> items = new ArrayList<>(end - start);
            for (String dataKey : dataKeys.subList(start, end)) {
                String itemKey =
                        dataKey.substring(prefix.length(), dataKey.length() - JSON_SUFFIX.length());
                String json = getString(dataKey);
                if (json != null) {
                    Map<String, Object> value =
                            JsonUtils.getJsonCodec().fromJson(json, new TypeReference<>() {});
                    long version =
                            readVersion(
                                    dataKey.substring(0, dataKey.length() - JSON_SUFFIX.length())
                                            + VERSION_SUFFIX);
                    items.add(new StoreItem(itemKey, value, version));
                }
            }
            return items;
        } catch (Exception e) {
            throw new RuntimeException("Failed to search namespace", e);
        }
    }

    @Override
    public void delete(List<String> namespace, String key) {
        String dataKey = dataObjectKey(namespace, key);
        String versionKey = versionObjectKey(namespace, key);
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucketName).key(dataKey).build());
            s3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucketName).key(versionKey).build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete item: " + key, e);
        }
    }

    private String dataObjectKey(List<String> namespace, String key) {
        return namespacePrefix(namespace) + stripLeadingSlashes(key) + JSON_SUFFIX;
    }

    private String versionObjectKey(List<String> namespace, String key) {
        return namespacePrefix(namespace) + stripLeadingSlashes(key) + VERSION_SUFFIX;
    }

    private static String stripLeadingSlashes(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') {
            i++;
        }
        return i == 0 ? s : s.substring(i);
    }

    private String namespacePrefix(List<String> namespace) {
        StringBuilder sb = new StringBuilder(keyPrefix);
        for (String component : namespace) {
            sb.append(component).append('/');
        }
        return sb.toString();
    }

    private long readVersion(String versionKey) {
        String content = getString(versionKey);
        if (content == null) {
            return 0L;
        }
        try {
            return Long.parseLong(content.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
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

        public OssBaseStore build() {
            return new OssBaseStore(this);
        }
    }
}
