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
import java.util.stream.Collectors;

/**
 * Tencent Cloud COS backed {@link BaseStore} for the harness remote filesystem.
 *
 * <p>Items are stored as JSON objects in COS. The object key layout is:
 *
 * <pre>
 * {keyPrefix}{namespace[0]}/{namespace[1]}/.../{key}.json       — item data
 * {keyPrefix}{namespace[0]}/{namespace[1]}/.../{key}.version    — version counter
 * </pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * COSClient cosClient = new COSClient(cred, clientConfig);
 *
 * BaseStore store = CosBaseStore.builder()
 *     .cosClient(cosClient)
 *     .bucketName("my-agentscope-bucket")
 *     .keyPrefix("agentscope/store/")
 *     .build();
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .filesystem(new RemoteFilesystemSpec(store))
 *     .build();
 * }</pre>
 */
public class CosBaseStore implements BaseStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope/store/";
    private static final String JSON_SUFFIX = ".json";
    private static final String VERSION_SUFFIX = ".version";

    private final COSClient cosClient;
    private final String bucketName;
    private final String keyPrefix;

    private CosBaseStore(Builder builder) {
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
                    String k = summary.getKey();
                    if (k.endsWith(JSON_SUFFIX) && !k.endsWith(VERSION_SUFFIX)) {
                        dataKeys.add(k);
                    }
                }
                marker = result.isTruncated() ? result.getNextMarker() : null;
            } while (marker != null);

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
            cosClient.deleteObject(bucketName, dataKey);
            if (objectExists(versionKey)) {
                cosClient.deleteObject(bucketName, versionKey);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete item: " + key, e);
        }
    }

    // ---- internal helpers ----

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

        public CosBaseStore build() {
            return new CosBaseStore(this);
        }
    }
}
