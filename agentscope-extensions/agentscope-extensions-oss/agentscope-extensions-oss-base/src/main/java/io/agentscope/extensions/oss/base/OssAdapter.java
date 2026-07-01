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

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;

/**
 * Vendor-independent, minimal OSS abstraction used by the shared {@code Abstract*}
 * base classes. Each cloud vendor (Alibaba OSS, AWS S3, Tencent COS, ...) provides a concrete
 * adapter that delegates to its own SDK.
 *
 * <p>Adapter implementations are expected to be thread-safe and stateless with respect to the
 * bucket they operate on: the bucket name is provided at construction time.
 */
public interface OssAdapter {

    /**
     * Returns whether an object with the given key exists in the underlying bucket.
     *
     * @param key full object key
     * @return {@code true} if the object exists
     */
    boolean exists(String key);

    /**
     * Uploads {@code data} to {@code key}, replacing any existing object with the same key.
     *
     * @param key full object key
     * @param data payload bytes (never {@code null})
     */
    void putBytes(String key, byte[] data);

    /**
     * Reads the object at {@code key} into memory and returns its bytes. Returns {@code null}
     * when the key does not exist. All other errors must be surfaced as unchecked exceptions.
     *
     * @param key full object key
     * @return the object payload, or {@code null} when the key does not exist
     */
    byte[] getBytes(String key);

    /**
     * Opens a streaming download for {@code key}. Callers are responsible for closing the
     * returned stream.
     *
     * @param key full object key
     * @return an {@link InputStream} over the object content
     * @throws FileNotFoundException if the key does not exist
     */
    InputStream openStream(String key) throws FileNotFoundException;

    /**
     * Deletes a single object. No-op when the key does not exist.
     *
     * @param key full object key
     */
    void delete(String key);

    /**
     * Lists a single page of objects under {@code prefix}. Callers drive pagination by
     * feeding {@link OssListPage#nextContinuationToken()} back into the next call until
     * it returns {@code null}.
     *
     * @param prefix prefix filter (must not be {@code null}; use {@code ""} for whole bucket)
     * @param continuationToken opaque token from the previous page, or {@code null} for the
     *     first page
     * @param maxKeys upper bound on the number of results per page
     * @return the page of results
     */
    OssListPage list(String prefix, String continuationToken, int maxKeys);

    /**
     * Batch-deletes the given keys. The default implementation deletes them one by one;
     * vendors with native batch delete APIs should override this for efficiency.
     *
     * @param keys keys to delete (must not be {@code null})
     */
    default void deleteBatch(List<String> keys) {
        for (String key : keys) {
            delete(key);
        }
    }

    /**
     * Releases any resources held by the underlying SDK client. The default is a no-op;
     * vendors should override this when their client has a shutdown / close method.
     */
    default void close() {
        // no-op by default
    }
}
