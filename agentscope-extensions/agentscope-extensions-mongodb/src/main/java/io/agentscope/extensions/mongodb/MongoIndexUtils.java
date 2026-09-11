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
package io.agentscope.extensions.mongodb;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for creating MongoDB indexes with automatic migration on
 * {@code IndexOptionsConflict} (error code 85).
 *
 * <p>When an existing index has conflicting options (e.g. TTL value changed), this utility
 * uses {@code listIndexes()} to find the <em>actual</em> index name by key pattern — not a
 * guessed default name — so it works even when DBAs renamed the index.
 */
public final class MongoIndexUtils {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexUtils.class);

    private static final int MAX_MIGRATION_ATTEMPTS = 3;

    private MongoIndexUtils() {}

    /**
     * Creates an index, migrating if an existing index has conflicting options (error 85).
     *
     * <p>If {@code createIndex} fails with error 85, this method:
     * <ol>
     *   <li>Calls {@code listIndexes()} to find the existing index whose key pattern matches
     *       {@code indexKeys}.</li>
     *   <li>Drops that index by its actual name (not a guessed default), tolerating "index not
     *       found" if another node already dropped it.</li>
     *   <li>Re-creates the index with the new definition.</li>
     * </ol>
     *
     * <p>The drop + recreate path is fault-tolerant for multi-node startup: if another node
     * concurrently drops or recreates the same index, the operation degrades to a harmless
     * no-op rather than failing the application bootstrap.
     *
     * @param collection  the MongoDB collection
     * @param indexKeys   the index key specification (e.g. {@code Indexes.ascending("field")})
     * @param options     the index options (e.g. TTL, sparse, unique)
     */
    public static void createIndexWithMigration(
            MongoCollection<Document> collection, Bson indexKeys, IndexOptions options) {
        try {
            collection.createIndex(indexKeys, options);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) {
                throw e;
            }
            migrateConflictingIndex(collection, indexKeys, options);
        }
    }

    /**
     * Handles error 85 by dropping the conflicting index and recreating with new options.
     * Both the drop and the recreate are fault-tolerant: if another node concurrently drops
     * or recreates the same index, the operation succeeds idempotently. Up to {@value
     * MAX_MIGRATION_ATTEMPTS} attempts are made to handle concurrent multi-node startup races.
     */
    private static void migrateConflictingIndex(
            MongoCollection<Document> collection, Bson indexKeys, IndexOptions options) {
        String conflictingName = findIndexNameByKeyPattern(collection, indexKeys);
        if (conflictingName != null) {
            log.info(
                    "[mongo-index] Index conflict (error 85), dropping '{}' and recreating"
                            + " with new options",
                    conflictingName);
            dropIndexTolerant(collection, conflictingName);
        }

        // A single createIndex path for both cases (conflicting index already dropped by us, or
        // already gone when we looked), so a concurrent recreation mid-migration is still caught
        // and retried rather than escaping unhandled.
        for (int attempt = 1; attempt <= MAX_MIGRATION_ATTEMPTS; attempt++) {
            try {
                collection.createIndex(indexKeys, options);
                return;
            } catch (MongoCommandException retryEx) {
                if (retryEx.getErrorCode() != 85) {
                    throw retryEx;
                }
                if (attempt < MAX_MIGRATION_ATTEMPTS) {
                    log.warn(
                            "[mongo-index] Concurrent conflict (error 85) on attempt {}/{}"
                                    + " — another node recreated the index; re-dropping and"
                                    + " retrying",
                            attempt,
                            MAX_MIGRATION_ATTEMPTS,
                            retryEx);
                    // Re-drop the current conflicting index before retrying so a persistent
                    // conflict (e.g. an old node recreating the old index during a rolling
                    // upgrade) is actually resolved rather than retried against unchanged state.
                    String current = findIndexNameByKeyPattern(collection, indexKeys);
                    if (current != null) {
                        dropIndexTolerant(collection, current);
                    }
                } else {
                    log.warn(
                            "[mongo-index] Exhausted {} migration attempts — propagating"
                                    + " final error 85",
                            MAX_MIGRATION_ATTEMPTS,
                            retryEx);
                    throw retryEx;
                }
            }
        }
    }

    /**
     * Drops an index by name, tolerating "index not found" (code 27) and "ns not found"
     * errors that occur when another node concurrently dropped the same index.
     */
    private static void dropIndexTolerant(MongoCollection<Document> collection, String name) {
        try {
            collection.dropIndex(name);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == 27 || e.getMessage().contains("ns not found")) {
                log.debug("[mongo-index] Index '{}' already dropped by another node", name);
            } else {
                throw e;
            }
        }
    }

    /**
     * Finds the name of an existing index whose key pattern matches the given keys.
     *
     * @param collection the MongoDB collection
     * @param indexKeys  the index key specification to match
     * @return the index name, or {@code null} if no matching index exists
     */
    private static String findIndexNameByKeyPattern(
            MongoCollection<Document> collection, Bson indexKeys) {
        BsonDocument keyPattern =
                indexKeys.toBsonDocument(BsonDocument.class, collection.getCodecRegistry());
        for (Document index : collection.listIndexes()) {
            Document existingKeys = index.get("key", Document.class);
            if (existingKeys != null) {
                BsonDocument existingBson =
                        existingKeys.toBsonDocument(
                                BsonDocument.class, collection.getCodecRegistry());
                if (existingBson.equals(keyPattern)) {
                    return index.getString("name");
                }
            }
        }
        return null;
    }
}
