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
package io.agentscope.extensions.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
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
import reactor.test.StepVerifier;

/**
 * Tests for {@link WeComTenantChannelManager}: lazy materialization, in-place credential refresh and
 * eviction.
 */
class WeComTenantChannelManagerTest {

    private final Map<String, WeComChannelProperties> tenants = new HashMap<>();
    private final AtomicInteger resolveCount = new AtomicInteger();
    private final WeComCredentialResolver resolver =
            key -> {
                resolveCount.incrementAndGet();
                return Optional.ofNullable(tenants.get(key));
            };
    private final Gateway gateway = mock(Gateway.class);
    private final WeComTenantChannelManager manager =
            new WeComTenantChannelManager(resolver, ChannelConfig.of("wecom", "main"), gateway);
    private final String tenantKey = "tenant-" + UUID.randomUUID();

    @AfterEach
    void tearDown() {
        manager.evict(tenantKey);
    }

    @Test
    void materializesChannelForResolvedTenant() {
        WeComChannelProperties properties = properties("token-1", "secret-1");
        tenants.put(tenantKey, properties);

        WeComChannel channel = manager.channelFor(tenantKey).orElseThrow();

        assertEquals(tenantKey, channel.channelId());
        assertSame(properties, channel.credentials().properties());
    }

    @Test
    void reusesChannelWhileCredentialsUnchanged() {
        tenants.put(tenantKey, properties("token-1", "secret-1"));
        WeComChannel first = manager.channelFor(tenantKey).orElseThrow();
        resolveCount.set(0);

        WeComChannel second = manager.channelFor(tenantKey).orElseThrow();

        assertSame(first, second);
        // The resolver is consulted on every callback; reuse is decided by comparing results.
        assertEquals(1, resolveCount.get());
    }

    @Test
    void rotationKeepsTheChannelObjectAndTakesEffectInPlace() {
        tenants.put(tenantKey, properties("token-old", "secret-1"));
        WeComChannel first = manager.channelFor(tenantKey).orElseThrow();

        tenants.put(tenantKey, properties("token-new", "secret-1"));
        WeComChannel second = manager.channelFor(tenantKey).orElseThrow();

        // Credentials rotate in place: no object swap, so sessions and guards survive.
        assertSame(first, second);
        String rotatedSignature = WeComTestSupport.sign("token-new", "1", "nonce", "payload");
        assertTrue(
                second.credentials()
                        .crypto()
                        .verifySignature(rotatedSignature, "1", "nonce", "payload"));
        assertFalse(
                second.credentials()
                        .crypto()
                        .verifySignature(
                                WeComTestSupport.sign("token-old", "1", "nonce", "payload"),
                                "1",
                                "nonce",
                                "payload"));
    }

    @Test
    void callbackTokenRotationKeepsTheOutboundRuntime() {
        WeComChannelProperties initial = properties("token-old", "secret-1");
        tenants.put(tenantKey, initial);
        WeComChannel channel = manager.channelFor(tenantKey).orElseThrow();
        WeComChannel.Credentials before = channel.credentials();

        tenants.put(tenantKey, rotate(initial, "token-new", initial.secret()));
        manager.channelFor(tenantKey).orElseThrow();

        assertNotSame(before.crypto(), channel.credentials().crypto());
        // The callback token does not feed the outbound path, so its token cache must survive.
        assertSame(before.outboundClient(), channel.credentials().outboundClient());
    }

    @Test
    void secretRotationRebuildsTheOutboundRuntime() {
        WeComChannelProperties initial = properties("token-1", "secret-old");
        tenants.put(tenantKey, initial);
        WeComChannel channel = manager.channelFor(tenantKey).orElseThrow();
        WeComChannel.Credentials before = channel.credentials();

        tenants.put(tenantKey, rotate(initial, initial.token(), "secret-new"));
        manager.channelFor(tenantKey).orElseThrow();

        assertSame(before.crypto(), channel.credentials().crypto());
        // A rotated secret invalidates the access token minted from it.
        assertNotSame(before.outboundClient(), channel.credentials().outboundClient());
    }

    @Test
    void unknownTenantResolvesEmpty() {
        assertTrue(manager.channelFor(tenantKey).isEmpty());
    }

    @Test
    void resolverFailurePropagates() {
        WeComTenantChannelManager failing =
                new WeComTenantChannelManager(
                        key -> {
                            throw new IllegalStateException("tenant store down");
                        },
                        ChannelConfig.of("wecom", "main"),
                        gateway);

        assertThrows(IllegalStateException.class, () -> failing.channelFor("any"));
    }

    @Test
    void evictDropsCachedChannel() {
        tenants.put(tenantKey, properties("token-1", "secret-1"));
        WeComChannel first = manager.channelFor(tenantKey).orElseThrow();

        manager.evict(tenantKey);

        WeComChannel second = manager.channelFor(tenantKey).orElseThrow();
        assertNotSame(first, second);
    }

    @Test
    void emptyResolutionKeepsTheCachedChannelForALiveTenant() {
        tenants.put(tenantKey, properties("token-1", "secret-1"));
        WeComChannel first = manager.channelFor(tenantKey).orElseThrow();

        tenants.remove(tenantKey);
        assertTrue(manager.channelFor(tenantKey).isEmpty());

        // A lookup that transiently returns nothing must not tear down the live tenant's runtime,
        // so the same channel serves again once the resolver sees the tenant.
        tenants.put(tenantKey, properties("token-1", "secret-1"));
        assertSame(first, manager.channelFor(tenantKey).orElseThrow());
    }

    @Test
    void materializedChannelDispatchesThroughItsGateway() {
        when(gateway.run(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        tenants.put(tenantKey, properties("token-1", "secret-1"));
        WeComChannel channel = manager.channelFor(tenantKey).orElseThrow();
        Msg msg = Msg.builder().role(MsgRole.USER).name("alice").textContent("hello").build();
        InboundMessage inbound =
                InboundMessage.builder(tenantKey, new Peer(PeerKind.DIRECT, "alice"), List.of(msg))
                        .accountId("corp-1")
                        .senderId("alice")
                        .build();

        // Composes instead of failing with "has no gateway", which is what an init-less
        // materialization would produce.
        StepVerifier.create(channel.dispatch(inbound)).verifyComplete();
    }

    @Test
    void resolutionIsSerializedPerTenantSoTheNewestAnswerWins() throws Exception {
        WeComChannelProperties older = properties("token-1", "secret-old");
        WeComChannelProperties newer = properties("token-1", "secret-new");
        AtomicReference<WeComChannelProperties> answer = new AtomicReference<>(older);
        AtomicBoolean stallNextResolve = new AtomicBoolean();
        AtomicInteger inResolver = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch stalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WeComTenantChannelManager racing =
                new WeComTenantChannelManager(
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
                        ChannelConfig.of("wecom", "main"),
                        gateway);
        WeComChannel channel = racing.channelFor(tenantKey).orElseThrow();

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
        tenants.put(tenantKey, properties("token-1", "secret-1"));
        WeComChannel channel = manager.channelFor(tenantKey).orElseThrow();
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
                                                    properties("token-" + i, "secret-" + i));
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
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting on a test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static WeComChannelProperties properties(String token, String secret) {
        return new WeComChannelProperties(
                "corp-1", 1000002, secret, token, WeComTestSupport.newAesKey(), null, null);
    }

    /** The same tenant with the callback token and/or the secret replaced. */
    private static WeComChannelProperties rotate(
            WeComChannelProperties base, String token, String secret) {
        return new WeComChannelProperties(
                base.corpId(),
                base.agentId(),
                secret,
                token,
                base.encodingAesKey(),
                base.callbackPath(),
                base.apiBase());
    }
}
