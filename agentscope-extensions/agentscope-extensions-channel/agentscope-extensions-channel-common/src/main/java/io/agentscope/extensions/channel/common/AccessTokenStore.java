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
package io.agentscope.extensions.channel.common;

/**
 * Caches the platform access token of one channel credential.
 *
 * <p>Channel token providers consult this store before fetching a token from the platform and
 * write each fetched token back. The provider owns the policy — refresh timing, response parsing,
 * and error-code invalidation — and treats the store as an opaque cache slot: one store instance
 * serves one credential, and the store is not keyed, so an instance shared across providers built
 * from different credentials would silently mix their tokens.
 *
 * <p>The default {@link InMemoryAccessTokenStore} keeps the token in the JVM heap, so it is not
 * shared across processes. Deployments running multiple channel instances should provide an
 * implementation backed by shared storage (for example Redis keyed by channel id) and inject it
 * through the channel factories: one instance's refresh or invalidation then serves the whole
 * deployment.
 *
 * <p>Implementations must be thread-safe: providers invoke these methods from concurrent reactive
 * pipelines. Concurrent refreshes are expected (there is no cross-instance lock); providers store
 * fetched tokens with {@link #putIfNewer} so racing refreshes cannot regress the cache to an
 * earlier refresh deadline, while {@link #put} replaces unconditionally.
 */
public interface AccessTokenStore {

    /**
     * Returns the cached token.
     *
     * <p>The store holds the token of a single credential: sharing one instance across providers
     * built from different credentials mixes their tokens.
     *
     * @return the last stored token, or {@code null} when none is cached; entries are returned
     *     regardless of age — the owning provider applies the refresh deadline carried in {@link
     *     CachedAccessToken#refreshAtMs()}
     */
    CachedAccessToken get();

    /**
     * Caches {@code token}, replacing any previous entry unconditionally.
     *
     * <p>The store holds the token of a single credential: do not share one instance across
     * providers built from different credentials.
     *
     * @param token the token to cache
     */
    void put(CachedAccessToken token);

    /**
     * Caches {@code token} only when it is fresher than the entry currently held (strictly later
     * {@link CachedAccessToken#refreshAtMs()}), so racing refreshes — expected, as there is no
     * cross-instance lock — cannot pin an older token with an earlier deadline.
     *
     * <p>The default implementation is a non-atomic read-compare-write; implementations backed by
     * shared storage should override it with an atomic conditional write (for example a
     * compare-and-set script) to keep the guarantee under concurrency.
     *
     * @param token the token to cache; the single-credential rule of {@link #put} applies
     * @return {@code true} when {@code token} was stored
     */
    default boolean putIfNewer(CachedAccessToken token) {
        CachedAccessToken current = get();
        if (current == null || token.refreshAtMs() > current.refreshAtMs()) {
            put(token);
            return true;
        }
        return false;
    }

    /** Drops the cached token so the owning provider fetches a fresh one on its next call. */
    void clear();
}
