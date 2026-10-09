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
package io.agentscope.extensions.channel.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Tests that the provider honors an injected token store: a cached token is served without any
 * HTTP call, and {@code invalidate()} drops it. The API base points at an unreachable address, so
 * a regression that bypasses the store fails fast instead of hitting the network.
 */
class DingTalkAccessTokenProviderTest {

    private static final String UNREACHABLE_API_BASE = "http://localhost:1";

    private final InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
    private final DingTalkAccessTokenProvider provider =
            new DingTalkAccessTokenProvider(UNREACHABLE_API_BASE, "appKey", "appSecret", store);

    @Test
    void servesCachedTokenFromInjectedStore() {
        store.put(new CachedAccessToken("cached-token", System.currentTimeMillis() + 60_000));
        assertEquals("cached-token", provider.token().block(Duration.ofSeconds(2)));
    }

    @Test
    void invalidateDropsCachedToken() {
        store.put(new CachedAccessToken("cached-token", System.currentTimeMillis() + 60_000));
        provider.invalidate();
        assertThrows(RuntimeException.class, () -> provider.token().block(Duration.ofSeconds(2)));
    }

    @Test
    void expiredEntryTriggersRefreshNotStaleToken() {
        store.put(new CachedAccessToken("stale-token", System.currentTimeMillis() - 1_000));
        assertThrows(RuntimeException.class, () -> provider.token().block(Duration.ofSeconds(2)));
    }
}
