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
package io.agentscope.extensions.mongodb.sandbox;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-based {@link SandboxExecutionGuard}.
 *
 * <p>Uses a dedicated MongoDB collection as a distributed lock mechanism. Each lock is a document
 * with a unique {@code _id} derived from the {@link SandboxIsolationKey} and a TTL index on {@code
 * expiresAt} to auto-release stale locks.
 *
 * <p>Lock acquisition uses a two-step approach for correct mutual exclusion:
 *
 * <ol>
 *   <li>Attempt {@code insertOne} — atomic under the unique {@code _id} index; only one
 *       concurrent caller succeeds.
 *   <li>If the lock document already exists (duplicate key), attempt {@code findOneAndUpdate}
 *       (non-upsert) with a filter that matches only when {@code expiresAt &lt;= now} — reclaiming
 *       an expired lock.
 * </ol>
 *
 * <p>Acquisition polls until the lock is obtained or the acquisition timeout ({@code
 * lockTimeout}) expires. The lock document itself lives for {@code leaseTtl}; while the returned
 * {@link SandboxLease} is open, a background watchdog renews the lease every {@code leaseTtl / 3}
 * by pushing {@code expiresAt} forward, so an execution that outlives the initial TTL does not
 * lose its own lock to reclamation. If renewal stops working (lock deleted or stolen), the
 * watchdog only warns — like the Redis guard, TTL expiry remains a safety valve against permanent
 * deadlock rather than a correctness guarantee.
 *
 * <p>The lease releases the lock on close, filtered by owner to avoid releasing another process's
 * lock.
 */
public final class MongoSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(MongoSandboxExecutionGuard.class);

    private static final String DEFAULT_COLLECTION = "agentscope_sandbox_locks";
    private static final String FIELD_LOCK_ID = "_id";
    private static final String FIELD_OWNER = "owner";
    private static final String FIELD_EXPIRES_AT = "expiresAt";

    private final MongoCollection<Document> collection;
    private final long lockTimeoutMs;
    private final long leaseTtlMs;
    private final long retryIntervalMs;
    private final String owner;
    private final ScheduledExecutorService renewalExecutor;

    private MongoSandboxExecutionGuard(Builder builder) {
        MongoDatabase db = builder.mongoClient.getDatabase(builder.databaseName);
        this.collection = db.getCollection(builder.collectionName);
        this.lockTimeoutMs = builder.lockTimeout.toMillis();
        this.leaseTtlMs = builder.leaseTtl.toMillis();
        this.retryIntervalMs = builder.retryInterval.toMillis();
        this.owner = builder.owner;
        this.renewalExecutor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "mongo-sandbox-lease-renewal");
                            thread.setDaemon(true);
                            return thread;
                        });
        ensureIndexes();
    }

    /**
     * Creates a new builder.
     *
     * @param mongoClient the MongoDB client
     * @return a new builder
     */
    public static Builder builder(MongoClient mongoClient) {
        return new Builder(mongoClient);
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String lockId = composeLockId(key);
        log.debug("[sandbox-guard] Acquiring MongoDB lock: {}", lockId);

        long deadline = System.nanoTime() + Duration.ofMillis(lockTimeoutMs).toNanos();
        while (true) {
            Date now = new Date();
            Date expiresAt = new Date(now.getTime() + leaseTtlMs);

            // Step 1: Try to insert a new lock document. This is atomic — only one
            // concurrent caller can succeed due to the _id unique index.
            Document lockDoc =
                    new Document(FIELD_LOCK_ID, lockId)
                            .append(FIELD_OWNER, owner)
                            .append(FIELD_EXPIRES_AT, expiresAt);
            try {
                collection.insertOne(lockDoc);
                log.debug("[sandbox-guard] Acquired MongoDB lock (insert): {}", lockId);
                return new MongoLease(collection, lockId, owner, leaseTtlMs, renewalExecutor);
            } catch (MongoWriteException e) {
                if (e.getError().getCode() != 11000) {
                    throw new RuntimeException("Failed to acquire MongoDB lock: " + lockId, e);
                }
                // Duplicate key — lock document already exists, fall through to step 2
            }

            // Step 2: Lock document exists. Try to reclaim it if it has expired.
            Bson expiredFilter =
                    Filters.and(
                            Filters.eq(FIELD_LOCK_ID, lockId), Filters.lte(FIELD_EXPIRES_AT, now));
            Bson reclaimUpdate =
                    Updates.combine(
                            Updates.set(FIELD_OWNER, owner),
                            Updates.set(FIELD_EXPIRES_AT, expiresAt));

            Document reclaimed =
                    collection.findOneAndUpdate(
                            expiredFilter,
                            reclaimUpdate,
                            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

            if (reclaimed != null) {
                log.debug(
                        "[sandbox-guard] Acquired MongoDB lock (reclaimed from {}): {}",
                        reclaimed.getString(FIELD_OWNER),
                        lockId);
                return new MongoLease(collection, lockId, owner, leaseTtlMs, renewalExecutor);
            }

            // Lock exists and has not expired — held by someone else.
            //
            // TOCTOU safety: between the failed insertOne above and the findOneAndUpdate
            // reclaim attempt, another process may have released the lock and a third process
            // may have re-acquired it. This is safe because the reclaim filter includes
            // `expiresAt <= now` — a freshly acquired lock has `expiresAt` in the future and
            // will NOT match the reclaim filter, so we will not steal it.
            if (log.isDebugEnabled()) {
                Document held = collection.find(Filters.eq(FIELD_LOCK_ID, lockId)).first();
                if (held != null) {
                    log.debug(
                            "[sandbox-guard] Lock held by {}, retrying: {}",
                            held.getString(FIELD_OWNER),
                            lockId);
                }
            }

            if (System.nanoTime() >= deadline) {
                throw new InterruptedException("Timed out waiting for MongoDB lock: " + lockId);
            }

            // MongoDB lacks server-side blocking locks (unlike MySQL GET_LOCK), so we poll.
            // InterruptedException is declared on the method signature and propagated by sleep.
            Thread.sleep(retryIntervalMs);
        }
    }

    private void ensureIndexes() {
        collection.createIndex(
                Indexes.ascending(FIELD_EXPIRES_AT),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    private static String composeLockId(SandboxIsolationKey key) {
        String raw = key.getScope().name().toLowerCase() + ":" + key.getValue();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("lock:");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static final class MongoLease implements SandboxLease {

        private final MongoCollection<Document> collection;
        private final String lockId;
        private final String owner;
        private final long leaseTtlMs;
        private final ScheduledFuture<?> renewal;

        MongoLease(
                MongoCollection<Document> collection,
                String lockId,
                String owner,
                long leaseTtlMs,
                ScheduledExecutorService renewalExecutor) {
            this.collection = collection;
            this.lockId = lockId;
            this.owner = owner;
            this.leaseTtlMs = leaseTtlMs;
            // Renew with 3x headroom before expiry so a single missed tick or a slow write
            // does not cost the lease.
            long renewalIntervalMs = Math.max(1L, leaseTtlMs / 3);
            this.renewal =
                    renewalExecutor.scheduleAtFixedRate(
                            this::renew,
                            renewalIntervalMs,
                            renewalIntervalMs,
                            TimeUnit.MILLISECONDS);
        }

        /**
         * Watchdog tick: push {@code expiresAt} forward while the lease is open, so an
         * execution longer than the initial lease TTL keeps holding its own lock.
         */
        private void renew() {
            try {
                Date expiresAt = new Date(System.currentTimeMillis() + leaseTtlMs);
                UpdateResult result =
                        collection.updateOne(
                                Filters.and(Filters.eq(lockId), Filters.eq(FIELD_OWNER, owner)),
                                Updates.set(FIELD_EXPIRES_AT, expiresAt));
                if (result.getMatchedCount() == 0) {
                    // Lock document gone or owned by someone else: it expired and was
                    // reclaimed before renewal succeeded. Warn only — like the Redis guard,
                    // TTL expiry is a safety valve, not a correctness guarantee.
                    log.warn(
                            "[sandbox-guard] MongoDB lock {} lost during execution (expired or"
                                    + " reclaimed); continuing without the lock",
                            lockId);
                }
            } catch (Exception e) {
                // Never let an exception escape a scheduled task, or the executor would
                // silently stop renewing.
                log.warn(
                        "[sandbox-guard] Failed to renew MongoDB lock {}: {}",
                        lockId,
                        e.getMessage());
            }
        }

        @Override
        public void close() {
            renewal.cancel(false);
            try {
                // Only delete if we still own the lock — prevents releasing someone else's
                // lock when close() is called after lease expiry and re-acquisition by another
                // process. Also makes close() idempotent: if already released, deleteOne is a
                // no-op.
                collection.deleteOne(
                        Filters.and(Filters.eq(lockId), Filters.eq(FIELD_OWNER, owner)));
                log.debug("[sandbox-guard] Released MongoDB lock: {}", lockId);
            } catch (Exception e) {
                log.warn(
                        "[sandbox-guard] Failed to release MongoDB lock {}: {}",
                        lockId,
                        e.getMessage());
            }
        }
    }

    /**
     * Builder for {@link MongoSandboxExecutionGuard}.
     */
    public static final class Builder {

        private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(500);
        private static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(30);

        private final MongoClient mongoClient;
        private String databaseName = "agentscope";
        private String collectionName = DEFAULT_COLLECTION;
        private Duration lockTimeout = Duration.ofMinutes(30);
        private Duration leaseTtl = DEFAULT_LEASE_TTL;
        private Duration retryInterval = DEFAULT_RETRY_INTERVAL;
        private String owner = "agentscope:" + ProcessHandle.current().pid();

        Builder(MongoClient mongoClient) {
            this.mongoClient = Objects.requireNonNull(mongoClient, "mongoClient");
        }

        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        /**
         * Sets the maximum time to wait for acquiring the lock before {@link #tryEnter} throws
         * {@link InterruptedException}. This bounds queueing delay only; it does NOT bound the
         * lock's lifetime — that is controlled by {@link #leaseTtl(Duration)}.
         *
         * <p>Default: {@code 30 minutes}.
         *
         * @param timeout the acquisition timeout; must be positive
         * @return this builder
         */
        public Builder lockTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.lockTimeout = timeout;
            return this;
        }

        /**
         * Sets the lifetime of the lock document ({@code expiresAt}) written on acquisition.
         * While the returned lease is open, a background watchdog renews the lease every {@code
         * leaseTtl / 3}, so executions longer than the TTL keep holding their lock. The TTL only
         * matters when the holder dies without releasing: after {@code leaseTtl} the lock becomes
         * eligible for reclamation by the next caller (MongoDB's TTL monitor may take up to 60
         * additional seconds to delete the document).
         *
         * <p>Default: {@code 30 minutes}.
         *
         * @param ttl the lease TTL; must be positive
         * @return this builder
         */
        public Builder leaseTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "ttl");
            if (ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("ttl must be positive");
            }
            this.leaseTtl = ttl;
            return this;
        }

        /**
         * Sets the polling interval between lock acquisition attempts.
         *
         * <p>Default: {@code 500 ms}. Lower values reduce latency at the cost of more MongoDB
         * round-trips; higher values reduce load at the cost of increased queuing delay.
         *
         * @param interval the retry interval; must be positive
         * @return this builder
         */
        public Builder retryInterval(Duration interval) {
            Objects.requireNonNull(interval, "retryInterval");
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("retryInterval must be positive");
            }
            this.retryInterval = interval;
            return this;
        }

        public Builder owner(String owner) {
            this.owner = owner;
            return this;
        }

        public MongoSandboxExecutionGuard build() {
            return new MongoSandboxExecutionGuard(this);
        }
    }
}
