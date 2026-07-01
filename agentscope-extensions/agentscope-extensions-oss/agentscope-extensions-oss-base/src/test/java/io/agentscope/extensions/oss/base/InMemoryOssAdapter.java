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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory {@link OssAdapter} used by tests to exercise the vendor-independent behaviour of the
 * base abstractions without touching any real OSS SDK. Keys are stored in a sorted map
 * so that {@link #list(String, String, int)} is deterministic.
 */
final class InMemoryOssAdapter implements OssAdapter {

    final TreeMap<String, byte[]> objects = new TreeMap<>();
    boolean closed;

    @Override
    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    @Override
    public void putBytes(String key, byte[] data) {
        objects.put(key, data.clone());
    }

    @Override
    public byte[] getBytes(String key) {
        byte[] bytes = objects.get(key);
        return bytes == null ? null : bytes.clone();
    }

    @Override
    public InputStream openStream(String key) throws FileNotFoundException {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new FileNotFoundException(key);
        }
        return new ByteArrayInputStream(bytes.clone());
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    @Override
    public OssListPage list(String prefix, String continuationToken, int maxKeys) {
        List<OssSummary> page = new ArrayList<>();
        boolean started = continuationToken == null;
        String next = null;
        for (Map.Entry<String, byte[]> entry : objects.tailMap(prefix, true).entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                break;
            }
            if (!started) {
                if (key.equals(continuationToken)) {
                    started = true;
                }
                continue;
            }
            if (page.size() >= maxKeys) {
                next = key;
                break;
            }
            page.add(new OssSummary(key));
        }
        return new OssListPage(page, next);
    }

    @Override
    public void deleteBatch(List<String> keys) {
        for (String key : keys) {
            objects.remove(key);
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
