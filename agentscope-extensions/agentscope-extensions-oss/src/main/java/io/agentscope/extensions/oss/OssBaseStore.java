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
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Alibaba Cloud OSS backed {@link BaseStore} for the harness remote filesystem.
 *
 * <p>Items are stored as JSON objects in OSS. The object key layout is:
 *
 * <pre>
 * {keyPrefix}{namespace[0]}/{namespace[1]}/.../{key}.json       — item data
 * {keyPrefix}{namespace[0]}/{namespace[1]}/.../{key}.version    — version counter
 * </pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
 *
 * BaseStore store = OssBaseStore.builder()
 *     .ossClient(ossClient)
 *     .bucketName("my-agentscope-bucket")
 *     .keyPrefix("agentscope/store/")
 *     .build();
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .filesystem(new RemoteFilesystemSpec(store))
 *     .build();
 * }</pre>
 */
public class OssBaseStore implements BaseStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope/store/";
    private static final String JSON_SUFFIX = ".json";
    private static final String VERSION_SUFFIX = ".version";

    private final OSS ossClient;
    private final String bucketName;
    private final String keyPrefix;

    private OssBaseStore(Builder builder) {
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
            String json = JsonUtils.getJsonCodec().toJson(value);
            putString(dataKey, json);
            long currentVersion = readVersion(versionKey);
            putString(versionKey, String.valueOf(currentVersion + 1));
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
            String json = JsonUtils.getJsonCodec().toJson(value);
            putString(dataKey, json);
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
                    String k = summary.getKey();
                    if (k.endsWith(JSON_SUFFIX) && !k.endsWith(VERSION_SUFFIX)) {
                        dataKeys.add(k);
                    }
                }
                continuationToken = result.isTruncated() ? result.getNextContinuationToken() : null;
            } while (continuationToken != null);

            Collections.sort(dataKeys);

            int start = Math.min(offset, dataKeys.size());
            int end = Math.min(start + limit, dataKeys.size());
            List<String> page = dataKeys.subList(start, end);

            List<StoreItem> items = new ArrayList<>(page.size());
            for (String dataKey : page) {
                String itemKey =
                        dataKey.substring(prefix.length(), dataKey.length() - JSON_SUFFIX.length());
                String json = getString(dataKey);
                if (json != null) {
                    Map<String, Object> val =
                            JsonUtils.getJsonCodec().fromJson(json, new TypeReference<>() {});
                    String vk =
                            dataKey.substring(0, dataKey.length() - JSON_SUFFIX.length())
                                    + VERSION_SUFFIX;
                    long version = readVersion(vk);
                    items.add(new StoreItem(itemKey, val, version));
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
            ossClient.deleteObject(bucketName, dataKey);
            if (ossClient.doesObjectExist(bucketName, versionKey)) {
                ossClient.deleteObject(bucketName, versionKey);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete item: " + key, e);
        }
    }

    // ---- internal helpers ----

    private String dataObjectKey(List<String> namespace, String key) {
        return namespacePrefix(namespace) + key + JSON_SUFFIX;
    }

    private String versionObjectKey(List<String> namespace, String key) {
        return namespacePrefix(namespace) + key + VERSION_SUFFIX;
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

        public OssBaseStore build() {
            return new OssBaseStore(this);
        }
    }
}
