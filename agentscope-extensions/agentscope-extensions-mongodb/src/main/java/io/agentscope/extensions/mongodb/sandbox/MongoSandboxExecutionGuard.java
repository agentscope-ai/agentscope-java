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

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
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
 * <p>Lock acquisition uses {@code findOneAndUpdate} with upsert and a filter that rejects documents
 * whose {@code expiresAt} has not yet passed. This provides a non-blocking try-lock semantics.
 * Acquisition polls until the lock is obtained or the timeout expires.
 */
public final class MongoSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(MongoSandboxExecutionGuard.class);

    private static final String DEFAULT_COLLECTION = "agentscope_sandbox_locks";
    private static final String FIELD_LOCK_ID = "_id";
    private static final String FIELD_OWNER = "owner";
    private static final String FIELD_EXPIRES_AT = "expiresAt";

    private final MongoCollection<Document> collection;
    private final long lockTimeoutMs;
    private final String owner;

    private MongoSandboxExecutionGuard(Builder builder) {
        MongoDatabase db = builder.mongoClient.getDatabase(builder.databaseName);
        this.collection = db.getCollection(builder.collectionName);
        this.lockTimeoutMs = builder.lockTimeout.toMillis();
        this.owner = builder.owner;
        ensureIndexes();
    }

    /**
     * Creates a new builder.
     *
     * @param mongoClient the MongoDB client
     * @return a new builder
     */
    public static Builder builder(com.mongodb.client.MongoClient mongoClient) {
        return new Builder(mongoClient);
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String lockId = composeLockId(key);
        log.debug("[sandbox-guard] Acquiring MongoDB lock: {}", lockId);

        long deadline = System.nanoTime() + Duration.ofMillis(lockTimeoutMs).toNanos();
        while (true) {
            Date now = new Date();
            Date expiresAt = new Date(now.getTime() + lockTimeoutMs);

            Bson filter =
                    Filters.and(
                            Filters.eq(FIELD_LOCK_ID, lockId),
                            Filters.or(
                                    Filters.exists(FIELD_OWNER, false),
                                    Filters.lte(FIELD_EXPIRES_AT, now)));

            Bson update =
                    Updates.combine(
                            Updates.setOnInsert(FIELD_LOCK_ID, lockId),
                            Updates.set(FIELD_OWNER, owner),
                            Updates.set(FIELD_EXPIRES_AT, expiresAt));

            try {
                Document result =
                        collection.findOneAndUpdate(
                                filter, update, new FindOneAndUpdateOptions().upsert(true));

                if (result == null) {
                    log.debug("[sandbox-guard] Acquired MongoDB lock: {}", lockId);
                    return new MongoLease(collection, lockId);
                } else {
                    log.debug(
                            "[sandbox-guard] Lock held by {}, retrying: {}",
                            result.getString(FIELD_OWNER),
                            lockId);
                }
            } catch (MongoBulkWriteException e) {
                // Duplicate key — lock held by someone else, retry
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("E11000")) {
                    // Duplicate key error — lock held by someone else
                } else {
                    throw new RuntimeException("Failed to acquire MongoDB lock: " + lockId, e);
                }
            }

            if (System.nanoTime() >= deadline) {
                throw new InterruptedException(
                        "Timed out waiting for MongoDB lock: "
                                + lockId
                                + " (timeout="
                                + Duration.ofMillis(lockTimeoutMs)
                                + ")");
            }

            Thread.sleep(100L);
        }
    }

    private void ensureIndexes() {
        collection.createIndex(
                Indexes.ascending(FIELD_EXPIRES_AT),
                new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
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

        MongoLease(MongoCollection<Document> collection, String lockId) {
            this.collection = collection;
            this.lockId = lockId;
        }

        @Override
        public void close() {
            try {
                collection.deleteOne(Filters.eq(lockId));
                log.debug("[sandbox-guard] Released MongoDB lock: {}", lockId);
            } catch (Exception e) {
                log.warn(
                        "[sandbox-guard] Failed to release MongoDB lock {}: {}",
                        lockId,
                        e.getMessage());
            }
        }
    }

    /** Builder for {@link MongoSandboxExecutionGuard}. */
    public static final class Builder {

        private final com.mongodb.client.MongoClient mongoClient;
        private String databaseName = "agentscope";
        private String collectionName = DEFAULT_COLLECTION;
        private Duration lockTimeout = Duration.ofMinutes(30);
        private String owner = "agentscope:" + ProcessHandle.current().pid();

        Builder(com.mongodb.client.MongoClient mongoClient) {
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

        public Builder lockTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.lockTimeout = timeout;
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
