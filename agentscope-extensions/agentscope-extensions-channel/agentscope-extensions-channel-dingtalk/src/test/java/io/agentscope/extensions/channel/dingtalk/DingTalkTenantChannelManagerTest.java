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

import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.AES_KEY;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.sign;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Tests for {@link DingTalkTenantChannelManager}: lazy materialization, http-mode enforcement,
 * in-place credential refresh and eviction.
 */
class DingTalkTenantChannelManagerTest {

    private final Map<String, DingTalkChannelProperties> tenants = new HashMap<>();
    private final AtomicInteger resolveCount = new AtomicInteger();
    private final DingTalkCredentialResolver resolver =
            key -> {
                resolveCount.incrementAndGet();
                return Optional.ofNullable(tenants.get(key));
            };
    private final Gateway gateway = mock(Gateway.class);
    private final DingTalkTenantChannelManager manager =
            new DingTalkTenantChannelManager(
                    resolver, ChannelConfig.of("dingtalk", "main"), gateway);
    private final String tenantKey = "tenant-" + UUID.randomUUID();

    @AfterEach
    void tearDown() {
        manager.evict(tenantKey);
    }

    @Test
    void materializesChannelForResolvedTenant() {
        DingTalkChannelProperties properties = httpProperties("secret-1", AES_KEY);
        tenants.put(tenantKey, properties);

        DingTalkChannel channel = manager.channelFor(tenantKey).orElseThrow();

        assertEquals(tenantKey, channel.channelId());
        assertSame(properties, channel.credentials().properties());
    }

    @Test
    void reusesChannelWhileCredentialsUnchanged() {
        tenants.put(tenantKey, httpProperties("secret-1", AES_KEY));
        DingTalkChannel first = manager.channelFor(tenantKey).orElseThrow();
        resolveCount.set(0);

        DingTalkChannel second = manager.channelFor(tenantKey).orElseThrow();

        assertSame(first, second);
        // The resolver is consulted on every callback; reuse is decided by comparing results.
        assertEquals(1, resolveCount.get());
    }

    @Test
    void rotationKeepsTheChannelObjectAndTakesEffectInPlace() throws Exception {
        tenants.put(tenantKey, httpProperties("secret-old", AES_KEY));
        DingTalkChannel first = manager.channelFor(tenantKey).orElseThrow();

        tenants.put(tenantKey, httpProperties("secret-new", AES_KEY));
        DingTalkChannel second = manager.channelFor(tenantKey).orElseThrow();

        // Credentials rotate in place: no object swap, so sessions and guards survive.
        assertSame(first, second);
        String timestamp = Long.toString(System.currentTimeMillis());
        assertTrue(second.credentials().crypto().verify(timestamp, sign("secret-new", timestamp)));
        assertFalse(second.credentials().crypto().verify(timestamp, sign("secret-old", timestamp)));
    }

    @Test
    void aesKeyRotationKeepsTheOutboundRuntime() {
        DingTalkChannelProperties initial = httpProperties("secret-1", AES_KEY);
        tenants.put(tenantKey, initial);
        DingTalkChannel channel = manager.channelFor(tenantKey).orElseThrow();
        DingTalkChannel.Credentials before = channel.credentials();

        tenants.put(
                tenantKey,
                rotate(initial, initial.appSecret(), DingTalkCallbackTestSupport.newAesKey()));
        manager.channelFor(tenantKey).orElseThrow();

        assertNotSame(before.crypto(), channel.credentials().crypto());
        // The AES key does not feed the outbound path, so its token cache must survive.
        assertSame(before.outboundClient(), channel.credentials().outboundClient());
    }

    @Test
    void secretRotationRebuildsTheOutboundRuntime() {
        DingTalkChannelProperties initial = httpProperties("secret-old", AES_KEY);
        tenants.put(tenantKey, initial);
        DingTalkChannel channel = manager.channelFor(tenantKey).orElseThrow();
        DingTalkChannel.Credentials before = channel.credentials();

        tenants.put(tenantKey, rotate(initial, "secret-new", initial.aesKey()));
        manager.channelFor(tenantKey).orElseThrow();

        // The app secret feeds both the callback HMAC and token minting, so both rebuild.
        assertNotSame(before.crypto(), channel.credentials().crypto());
        assertNotSame(before.outboundClient(), channel.credentials().outboundClient());
    }

    @Test
    void tokenStoreFactorySeesTenantAndGenerationAndRunsOncePerGeneration() {
        List<String> factoryCalls = new ArrayList<>();
        List<AccessTokenStore> stores = new ArrayList<>();
        DingTalkTenantChannelManager instrumented =
                new DingTalkTenantChannelManager(
                        resolver,
                        ChannelConfig.of("dingtalk", "main"),
                        gateway,
                        new IdempotencyStore(),
                        (key, properties) -> {
                            factoryCalls.add(key + ":" + properties.appSecret());
                            InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
                            stores.add(store);
                            return store;
                        });

        DingTalkChannelProperties initial = httpProperties("secret-1", AES_KEY);
        tenants.put(tenantKey, initial);
        instrumented.channelFor(tenantKey).orElseThrow();
        instrumented.channelFor(tenantKey).orElseThrow();
        // An AES-key-only rotation leaves the outbound runtime — and its store — in place.
        tenants.put(
                tenantKey,
                rotate(initial, initial.appSecret(), DingTalkCallbackTestSupport.newAesKey()));
        instrumented.channelFor(tenantKey).orElseThrow();
        // An app-secret rotation rebuilds the outbound runtime onto a new generation.
        tenants.put(tenantKey, rotate(initial, "secret-2", initial.aesKey()));
        instrumented.channelFor(tenantKey).orElseThrow();

        assertEquals(List.of(tenantKey + ":secret-1", tenantKey + ":secret-2"), factoryCalls);
        // One store instance per credential generation: a generation's slot must not be reachable
        // from another generation's requests.
        assertEquals(2, stores.size());
        assertNotSame(stores.get(0), stores.get(1));
        instrumented.evict(tenantKey);
    }

    @Test
    void rejectsStreamModeResolution() {
        tenants.put(
                tenantKey,
                new DingTalkChannelProperties(
                        "app-key-1",
                        "secret-1",
                        "robot-1",
                        DingTalkChannelProperties.MODE_STREAM,
                        null,
                        null,
                        null,
                        null));

        assertThrows(IllegalArgumentException.class, () -> manager.channelFor(tenantKey));
    }

    @Test
    void streamModeChannelCannotRefreshInPlace() {
        DingTalkChannel streamChannel =
                DingTalkChannel.fromProperties(
                        "stream-no-start",
                        ChannelConfig.of("stream-no-start", "main"),
                        new DingTalkChannelProperties(
                                "app-key-1",
                                "secret-1",
                                "robot-1",
                                DingTalkChannelProperties.MODE_STREAM,
                                null,
                                null,
                                null,
                                null),
                        new IdempotencyStore(),
                        properties -> new InMemoryAccessTokenStore());
        DingTalkChannelProperties rotated = httpProperties("secret-2", null);

        // Stream properties are rejected outright; http properties cannot refresh a stream
        // channel, whose WebSocket is bound to its construction-time credentials.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        streamChannel.refreshCredentials(
                                new DingTalkChannelProperties(
                                        "app-key-1",
                                        "secret-1",
                                        "robot-1",
                                        DingTalkChannelProperties.MODE_STREAM,
                                        null,
                                        null,
                                        null,
                                        null)));
        assertThrows(IllegalStateException.class, () -> streamChannel.refreshCredentials(rotated));
    }

    @Test
    void unknownTenantResolvesEmpty() {
        assertTrue(manager.channelFor(tenantKey).isEmpty());
    }

    @Test
    void resolverFailurePropagates() {
        DingTalkTenantChannelManager failing =
                new DingTalkTenantChannelManager(
                        key -> {
                            throw new IllegalStateException("tenant store down");
                        },
                        ChannelConfig.of("dingtalk", "main"),
                        gateway);

        assertThrows(IllegalStateException.class, () -> failing.channelFor("any"));
    }

    @Test
    void evictDropsCachedChannel() {
        tenants.put(tenantKey, httpProperties("secret-1", AES_KEY));
        DingTalkChannel first = manager.channelFor(tenantKey).orElseThrow();

        manager.evict(tenantKey);

        DingTalkChannel second = manager.channelFor(tenantKey).orElseThrow();
        assertNotSame(first, second);
    }

    @Test
    void emptyResolutionKeepsTheCachedChannelForALiveTenant() {
        tenants.put(tenantKey, httpProperties("secret-1", AES_KEY));
        DingTalkChannel first = manager.channelFor(tenantKey).orElseThrow();

        tenants.remove(tenantKey);
        assertTrue(manager.channelFor(tenantKey).isEmpty());

        // A lookup that transiently returns nothing must not tear down the live tenant's runtime,
        // so the same channel serves again once the resolver sees the tenant.
        tenants.put(tenantKey, httpProperties("secret-1", AES_KEY));
        assertSame(first, manager.channelFor(tenantKey).orElseThrow());
    }

    @Test
    void materializedChannelDispatchesThroughItsGateway() throws Exception {
        when(gateway.run(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        tenants.put(tenantKey, httpProperties("secret-1", AES_KEY));
        DingTalkChannel channel = manager.channelFor(tenantKey).orElseThrow();

        // Feeding the shared intake composes instead of failing with "has no gateway", which is
        // what an init-less materialization would produce.
        channel.onInboundPayload(new ObjectMapper().readTree(botMessage("m-1")));

        verify(gateway, times(1)).run(any(), any(), any(), any(), any());
    }

    @Test
    void resolutionIsSerializedPerTenantSoTheNewestAnswerWins() throws Exception {
        DingTalkChannelProperties older = httpProperties("secret-old", AES_KEY);
        DingTalkChannelProperties newer = httpProperties("secret-new", AES_KEY);
        AtomicReference<DingTalkChannelProperties> answer = new AtomicReference<>(older);
        AtomicBoolean stallNextResolve = new AtomicBoolean();
        AtomicInteger inResolver = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch stalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DingTalkTenantChannelManager racing =
                new DingTalkTenantChannelManager(
                        key -> {
                            int concurrent = inResolver.incrementAndGet();
                            maxConcurrent.accumulateAndGet(concurrent, Math::max);
                            try {
                                if (stallNextResolve.compareAndSet(true, false)) {
                                    stalled.countDown();
                                    awaitLatch(release);
                                    return Optional.of(older);
                                }
                                return Optional.of(answer.get());
                            } finally {
                                inResolver.decrementAndGet();
                            }
                        },
                        ChannelConfig.of("dingtalk", "main"),
                        gateway);
        DingTalkChannel channel = racing.channelFor(tenantKey).orElseThrow();

        // Thread A resolves the older generation and stalls inside the resolver; thread B looks the
        // tenant up while A is still in there.
        stallNextResolve.set(true);
        answer.set(newer);
        Thread slow = new Thread(() -> racing.channelFor(tenantKey));
        slow.start();
        assertTrue(stalled.await(5, TimeUnit.SECONDS), "thread A never entered the resolver");
        Thread fresh = new Thread(() -> racing.channelFor(tenantKey));
        fresh.start();
        fresh.join(500);
        release.countDown();
        slow.join(TimeUnit.SECONDS.toMillis(10));
        fresh.join(TimeUnit.SECONDS.toMillis(10));

        // Resolve-and-refresh runs under the tenant's credential lock, so B cannot even ask the
        // resolver while A is in it — which is what makes the answer applied last the newest one
        // rather than A's older answer overwriting it.
        assertEquals(1, maxConcurrent.get());
        assertSame(newer, channel.credentials().properties());
        racing.evict(tenantKey);
    }

    @Test
    void concurrentLookupsDuringRotationsKeepServingTheSameChannel() throws Exception {
        tenants.put(tenantKey, httpProperties("secret-1", AES_KEY));
        DingTalkChannel channel = manager.channelFor(tenantKey).orElseThrow();
        int threads = 4;
        int iterations = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        try {
            for (int worker = 0; worker < threads; worker++) {
                int id = worker;
                workers.add(
                        pool.submit(
                                () -> {
                                    awaitLatch(start);
                                    for (int i = 1; i <= iterations; i++) {
                                        if (id == 0 && i % 10 == 0) {
                                            tenants.put(
                                                    tenantKey,
                                                    httpProperties("secret-" + i, AES_KEY));
                                        }
                                        assertSame(
                                                channel,
                                                manager.channelFor(tenantKey).orElseThrow());
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static DingTalkChannelProperties httpProperties(String appSecret, String aesKey) {
        return new DingTalkChannelProperties(
                "app-key-1",
                appSecret,
                "robot-1",
                DingTalkChannelProperties.MODE_HTTP,
                aesKey,
                null,
                null,
                null);
    }

    /** The same tenant with the app secret and/or the callback AES key replaced. */
    private static DingTalkChannelProperties rotate(
            DingTalkChannelProperties base, String appSecret, String aesKey) {
        return new DingTalkChannelProperties(
                base.appKey(),
                appSecret,
                base.robotCode(),
                base.mode(),
                aesKey,
                base.apiBase(),
                base.oapiBase(),
                base.streamRegisterUrl());
    }

    private static String botMessage(String msgId) {
        return "{\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"},\"conversationId\":\"cid-1\","
                   + "\"conversationType\":\"1\",\"senderStaffId\":\"staff-1\",\"msgId\":\""
                + msgId
                + "\"}";
    }
}
