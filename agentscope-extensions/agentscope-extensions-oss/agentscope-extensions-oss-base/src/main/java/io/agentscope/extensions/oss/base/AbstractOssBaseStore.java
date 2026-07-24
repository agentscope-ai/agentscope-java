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
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Vendor-independent implementation of {@link BaseStore} on top of an
 * {@link OssAdapter}.
 *
 * <p>Items are stored as JSON objects. The object key layout is:
 *
 * <pre>
 * {keyPrefix}{namespace[0]}/{namespace[1]}/.../{key}.json       — item data
 * {keyPrefix}{namespace[0]}/{namespace[1]}/.../{key}.version    — version counter
 * </pre>
 *
 * <p>Concrete vendor implementations only need to provide an
 * {@link OssAdapter}; all key composition, JSON encoding, versioning, pagination
 * and error mapping happens here.
 */
public abstract class AbstractOssBaseStore implements BaseStore {

    protected static final String DEFAULT_KEY_PREFIX = "agentscope/store/";
    protected static final String JSON_SUFFIX = ".json";
    protected static final String VERSION_SUFFIX = ".version";
    protected static final int LIST_PAGE_SIZE = 1000;

    protected final OssAdapter adapter;
    protected final String bucketName;
    protected final String keyPrefix;

    /**
     * @param adapter vendor-specific OSS adapter
     * @param bucketName bucket in which items are stored (must be non-blank)
     * @param keyPrefix optional key prefix; falls back to {@link #DEFAULT_KEY_PREFIX} when
     *     {@code null} or blank
     */
    protected AbstractOssBaseStore(OssAdapter adapter, String bucketName, String keyPrefix) {
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = bucketName;
        this.keyPrefix = PathUtils.normalizePrefix(keyPrefix, DEFAULT_KEY_PREFIX);
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
                OssListObjectPage page = adapter.list(prefix, continuationToken, LIST_PAGE_SIZE);
                for (OssObjectSummary summary : page.objects()) {
                    String k = summary.key();
                    if (k.endsWith(JSON_SUFFIX) && !k.endsWith(VERSION_SUFFIX)) {
                        dataKeys.add(k);
                    }
                }
                continuationToken = page.nextContinuationToken();
            } while (continuationToken != null);

            Collections.sort(dataKeys);

            int start = Math.min(offset, dataKeys.size());
            int end = Math.min(start + limit, dataKeys.size());
            List<String> pageSlice = dataKeys.subList(start, end);

            List<StoreItem> items = new ArrayList<>(pageSlice.size());
            for (String dataKey : pageSlice) {
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
            adapter.delete(dataKey);
            if (adapter.exists(versionKey)) {
                adapter.delete(versionKey);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete item: " + key, e);
        }
    }

    // ---- internal helpers ----

    protected String dataObjectKey(List<String> namespace, String key) {
        return namespacePrefix(namespace) + PathUtils.stripLeadingSlashes(key) + JSON_SUFFIX;
    }

    protected String versionObjectKey(List<String> namespace, String key) {
        return namespacePrefix(namespace) + PathUtils.stripLeadingSlashes(key) + VERSION_SUFFIX;
    }

    protected String namespacePrefix(List<String> namespace) {
        StringBuilder sb = new StringBuilder(keyPrefix);
        for (String component : namespace) {
            sb.append(component).append('/');
        }
        return sb.toString();
    }

    protected long readVersion(String versionKey) {
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
}
