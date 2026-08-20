/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.a2a.redis;

import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.a2a.server.hitl.HitlDurableStorageComponent;
import io.agentscope.core.a2a.server.hitl.HitlExecutionKey;
import io.agentscope.core.a2a.server.hitl.HitlLeaseHandle;
import io.agentscope.core.a2a.server.hitl.HitlResumeRejectedException;
import io.agentscope.core.a2a.server.hitl.HitlSessionLease;
import io.agentscope.core.a2a.server.hitl.HitlTokenDigests;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owner-token Redis lease with periodic renewal; it deliberately avoids thread-bound locks. */
public final class RedisHitlSessionLease
        implements HitlSessionLease, HitlDurableStorageComponent, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisHitlSessionLease.class);
    private static final String HASH_TAG = "{agentscope-a2a-hitl}:";
    private static final String RENEW_SCRIPT =
            "if redis.call('get',KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "redis.call('pexpire',KEYS[1],ARGV[2]); return 1";
    private static final String RELEASE_SCRIPT =
            "if redis.call('get',KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "redis.call('del',KEYS[1]); return 1";

    private final RedissonClient redissonClient;
    private final String namespace;
    private final ScheduledExecutorService renewals;
    private final Set<Lease> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedisHitlSessionLease(RedissonClient redissonClient, String namespace) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.namespace = RedisTaskStore.normalizeNamespace(namespace);
        this.renewals =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "a2a-hitl-lease-renewal");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    @Override
    public HitlLeaseHandle acquire(HitlExecutionKey executionKey, Duration ttl) {
        Objects.requireNonNull(executionKey, "executionKey");
        Duration effectiveTtl = RedisTaskStore.requirePositiveMillis(ttl, "lease ttl");
        if (closed.get()) {
            throw new IllegalStateException("Redis HITL lease manager is closed");
        }
        String key = leaseKey(executionKey);
        String owner = UUID.randomUUID().toString();
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        if (!bucket.setIfAbsent(owner, effectiveTtl)) {
            throw new HitlResumeRejectedException(
                    "Another A2A turn is already executing for this logical session");
        }
        Lease lease = new Lease(key, owner, effectiveTtl);
        active.add(lease);
        try {
            if (closed.get()) {
                lease.close();
                throw new IllegalStateException("Redis HITL lease manager is closed");
            }
            long interval = Math.max(1L, effectiveTtl.toMillis() / 3L);
            lease.renewal =
                    renewals.scheduleAtFixedRate(
                            lease::renew, interval, interval, TimeUnit.MILLISECONDS);
            return lease;
        } catch (RuntimeException schedulingFailure) {
            lease.close();
            throw schedulingFailure;
        }
    }

    @Override
    public HitlDurabilityCapability durabilityCapability() {
        return HitlDurabilityCapability.DURABLE;
    }

    public RedissonClient redissonClient() {
        return redissonClient;
    }

    public String namespace() {
        return namespace;
    }

    @Override
    public Object storageClientIdentity() {
        return redissonClient;
    }

    @Override
    public String logicalStoreId() {
        return namespace;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        active.forEach(Lease::close);
        renewals.shutdownNow();
    }

    String leaseKey(HitlExecutionKey executionKey) {
        return namespace + HASH_TAG + "lease:" + HitlTokenDigests.sha256(executionKey.sessionKey());
    }

    private final class Lease implements HitlLeaseHandle {
        private final String key;
        private final String owner;
        private final Duration ttl;
        private final AtomicBoolean handleClosed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicReference<Runnable> lostAction = new AtomicReference<>();
        private volatile ScheduledFuture<?> renewal;

        private Lease(String key, String owner, Duration ttl) {
            this.key = key;
            this.owner = owner;
            this.ttl = ttl;
        }

        private void renew() {
            if (handleClosed.get()) {
                return;
            }
            try {
                Long renewed =
                        redissonClient
                                .getScript(StringCodec.INSTANCE)
                                .eval(
                                        RScript.Mode.READ_WRITE,
                                        RENEW_SCRIPT,
                                        RScript.ReturnType.LONG,
                                        List.of(key),
                                        owner,
                                        String.valueOf(ttl.toMillis()));
                if (renewed == null || renewed != 1L) {
                    markLost();
                }
            } catch (RuntimeException renewalFailure) {
                markLost();
            }
        }

        @Override
        public void onLost(Runnable action) {
            Objects.requireNonNull(action, "action");
            if (!lostAction.compareAndSet(null, action)) {
                throw new IllegalStateException("lease lost action is already registered");
            }
            if (lost.get()) {
                runLostAction();
            }
        }

        @Override
        public boolean isValid() {
            return !handleClosed.get() && !lost.get();
        }

        @Override
        public void close() {
            if (!handleClosed.compareAndSet(false, true)) {
                return;
            }
            active.remove(this);
            ScheduledFuture<?> current = renewal;
            if (current != null) {
                current.cancel(false);
            }
            try {
                redissonClient
                        .getScript(StringCodec.INSTANCE)
                        .eval(
                                RScript.Mode.READ_WRITE,
                                RELEASE_SCRIPT,
                                RScript.ReturnType.LONG,
                                List.of(key),
                                owner);
            } catch (RuntimeException releaseFailure) {
                log.warn("Failed to release an A2A HITL lease; TTL will expire it", releaseFailure);
            }
        }

        private void markLost() {
            if (!lost.compareAndSet(false, true)) {
                return;
            }
            handleClosed.set(true);
            active.remove(this);
            ScheduledFuture<?> current = renewal;
            if (current != null) {
                current.cancel(false);
            }
            runLostAction();
        }

        private void runLostAction() {
            Runnable action = lostAction.getAndSet(null);
            if (action == null) {
                return;
            }
            try {
                action.run();
            } catch (RuntimeException callbackFailure) {
                log.error("A2A HITL lease-loss callback failed", callbackFailure);
            }
        }
    }
}
