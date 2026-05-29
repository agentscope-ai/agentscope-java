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
package io.agentscope.dataagent.runtime.channel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The idempotency store sits in front of every webhook channel; the contract this test pins is
 * what {@code WebhookChannel} (and the IM channels ported from builder) rely on:
 *
 * <ul>
 *   <li>First call for a key returns {@code true} (proceed). Repeated calls within the TTL return
 *       {@code false} (drop, the provider is retrying).
 *   <li>Once the TTL expires the same key is treated as fresh again — providers that retry hours
 *       later must not be silently dropped.
 *   <li>Null keys are always {@code true} (callers without an idempotency key opt out of dedup).
 * </ul>
 */
class IdempotencyStoreTest {

    @Test
    void firstSeenIsTrueRepeatedIsFalse() {
        IdempotencyStore store = new IdempotencyStore();

        assertThat(store.firstSeen("msg-1")).isTrue();
        assertThat(store.firstSeen("msg-1")).isFalse();
        assertThat(store.firstSeen("msg-1")).isFalse();
    }

    @Test
    void independentKeysAreTrackedSeparately() {
        IdempotencyStore store = new IdempotencyStore();

        assertThat(store.firstSeen("msg-A")).isTrue();
        assertThat(store.firstSeen("msg-B")).isTrue();
        assertThat(store.firstSeen("msg-A")).isFalse();
        assertThat(store.firstSeen("msg-B")).isFalse();
    }

    @Test
    void nullKeysOptOutOfDeduplication() {
        IdempotencyStore store = new IdempotencyStore();

        assertThat(store.firstSeen(null)).isTrue();
        assertThat(store.firstSeen(null)).isTrue();
        assertThat(store.size()).isEqualTo(0);
    }

    /**
     * The store sweeps expired entries before honoring a new key. A 1-ms TTL with a small sleep
     * gives us a deterministic post-expiry observation without flake-prone timing.
     */
    @Test
    void expiredEntriesAreReadmitted() throws InterruptedException {
        IdempotencyStore store = new IdempotencyStore(1L, 16);
        assertThat(store.firstSeen("msg-1")).isTrue();

        Thread.sleep(10);
        // The store treats anything older than ttlMillis as fresh on the next probe.
        assertThat(store.firstSeen("msg-1")).isTrue();
    }

    @Test
    void illegalConfigurationIsRejected() {
        assertThatThrownBy(() -> new IdempotencyStore(0L, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyStore(-1L, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyStore(10L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyStore(10L, -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The hard cap protects us from a runaway producer. Once the store is at capacity, the next
     * call must still succeed — sweep evicts arbitrary entries to make room.
     */
    @Test
    void hardCapBoundsMemoryUsage() {
        IdempotencyStore store = new IdempotencyStore(5 * 60_000L, 4);

        for (int i = 0; i < 100; i++) {
            store.firstSeen("msg-" + i);
        }

        assertThat(store.size()).isLessThanOrEqualTo(4);
    }
}
