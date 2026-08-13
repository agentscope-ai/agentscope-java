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
package io.agentscope.extensions.mongodb.state;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * MongoDB-backed implementation of {@link AgentStateStore}.
 *
 * <p>Each session is stored as a single MongoDB document. State keys map to top-level BSON fields.
 * Supports optimistic concurrency via a per-key {@code _version_{key}} field.
 *
 * <p>List state uses {@link ListHashUtil} for change detection to avoid unnecessary full rewrites.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * MongoAgentStateStore store = MongoAgentStateStore.builder()
 *     .connectionString("mongodb://localhost:27017")
 *     .databaseName("agentscope")
 *     .collectionName("sessions")
 *     .build();
 * }</pre>
 */
public class MongoAgentStateStore implements AgentStateStore, AutoCloseable {

    private static final String DEFAULT_DATABASE_NAME = "agentscope";
    private static final String DEFAULT_COLLECTION_NAME = "agentscope_sessions";
    private static final String ANON_USER = "__anon__";
    private static final String LIST_SUFFIX = ":list";
    private static final String HASH_PREFIX = "_hash_";
    private static final String VERSION_PREFIX = "_version_";
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_SESSION_ID = "session_id";
    private static final String FIELD_UPDATED_AT = "_updated_at";
    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final MongoClient mongoClient;
    private final boolean ownsClient;
    private final MongoCollection<Document> collection;

    private MongoAgentStateStore(Builder builder) {
        if (builder.mongoClient != null) {
            this.mongoClient = builder.mongoClient;
            this.ownsClient = false;
        } else if (builder.connectionString != null) {
            MongoClientSettings settings =
                    MongoClientSettings.builder()
                            .applyConnectionString(new ConnectionString(builder.connectionString))
                            .build();
            this.mongoClient = MongoClients.create(settings);
            this.ownsClient = true;
        } else {
            throw new IllegalArgumentException(
                    "Either mongoClient or connectionString must be provided");
        }

        String dbName = builder.databaseName != null ? builder.databaseName : DEFAULT_DATABASE_NAME;
        String collName =
                builder.collectionName != null ? builder.collectionName : DEFAULT_COLLECTION_NAME;

        MongoDatabase db = this.mongoClient.getDatabase(dbName);
        this.collection = db.getCollection(collName);

        ensureIndexes();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    // ────────────────── Index Management ──────────────────

    private void ensureIndexes() {
        collection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending(FIELD_USER_ID), Indexes.ascending(FIELD_SESSION_ID)));

        String ttlIndexName = FIELD_UPDATED_AT + "_1";
        long ttlSeconds = 30L * 24 * 3600;
        try {
            collection.createIndex(
                    Indexes.ascending(FIELD_UPDATED_AT),
                    new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS).sparse(true));
        } catch (MongoCommandException e) {
            // IndexOptionsConflict
            if (e.getErrorCode() == 85) {
                collection.dropIndex(ttlIndexName);
                collection.createIndex(
                        Indexes.ascending(FIELD_UPDATED_AT),
                        new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS).sparse(true));
            } else {
                throw e;
            }
        }
    }

    // ────────────────── Single Value CRUD ──────────────────

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        validateKey(key);
        String slotId = slotId(userId, sessionId);
        String versionField = VERSION_PREFIX + key;
        String json = JsonUtils.getJsonCodec().toJson(value);
        Bson setFields =
                Updates.combine(
                        Updates.set(key, Document.parse(json)),
                        Updates.inc(versionField, 1L),
                        Updates.set(FIELD_UPDATED_AT, new Date()));
        Bson setOnInsert =
                Updates.combine(
                        Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                        Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
        collection.updateOne(Filters.eq(slotId), Updates.combine(setFields, setOnInsert), upsert());
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        validateKey(key);
        String slotId = slotId(userId, sessionId);
        Document doc =
                collection.find(Filters.eq(slotId)).projection(Projections.include(key)).first();
        if (doc == null || !doc.containsKey(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(deserializeValue(doc.get(key), type));
    }

    // ────────────────── List CRUD ──────────────────

    /**
     * Saves a list of state values with incremental-append optimization.
     *
     * <p><b>Concurrency note:</b> this method performs a read-then-write to decide between
     * incremental append and full replacement. It is NOT atomic — concurrent calls for the same
     * {@code (userId, sessionId, key)} may interleave reads and writes, causing lost appends or
     * stale hash comparisons. Callers that require strict consistency should synchronize externally
     * (e.g. via {@link io.agentscope.harness.agent.sandbox.SandboxExecutionGuard}).
     */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        validateKey(key);
        String slotId = slotId(userId, sessionId);
        String listKey = key + LIST_SUFFIX;
        String hashField = HASH_PREFIX + key;

        Document doc =
                collection
                        .find(Filters.eq(slotId))
                        .projection(Projections.include(listKey, hashField))
                        .first();

        String storedHash = null;
        int existingCount = 0;
        if (doc != null) {
            if (doc.containsKey(hashField)) {
                storedHash = doc.getString(hashField);
            }
            if (doc.containsKey(listKey)) {
                existingCount = doc.getList(listKey, Object.class).size();
            }
        }

        String currentHash = ListHashUtil.computeHash(values);

        if (ListHashUtil.needsFullRewrite(values, storedHash, existingCount)) {
            List<Document> bsonList = toDocumentList(values);
            Bson setFields =
                    Updates.combine(
                            Updates.set(listKey, bsonList),
                            Updates.set(hashField, currentHash),
                            Updates.set(FIELD_UPDATED_AT, new Date()));
            Bson setOnInsert =
                    Updates.combine(
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            collection.updateOne(
                    Filters.eq(slotId), Updates.combine(setFields, setOnInsert), upsert());
        } else if (values.size() > existingCount) {
            List<? extends State> newItems = values.subList(existingCount, values.size());
            List<Document> newDocs = toDocumentList(newItems);
            Bson update =
                    Updates.combine(
                            Updates.pushEach(listKey, newDocs),
                            Updates.set(hashField, currentHash),
                            Updates.set(FIELD_UPDATED_AT, new Date()));
            collection.updateOne(Filters.eq(slotId), update, upsert());
        } else {
            // Hash unchanged but size decreased (elements removed) — force full rewrite.
            List<Document> bsonList = toDocumentList(values);
            Bson setFields =
                    Updates.combine(
                            Updates.set(listKey, bsonList),
                            Updates.set(hashField, currentHash),
                            Updates.set(FIELD_UPDATED_AT, new Date()));
            Bson setOnInsert =
                    Updates.combine(
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            collection.updateOne(
                    Filters.eq(slotId), Updates.combine(setFields, setOnInsert), upsert());
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        validateKey(key);
        String slotId = slotId(userId, sessionId);
        String listKey = key + LIST_SUFFIX;
        Document doc =
                collection
                        .find(Filters.eq(slotId))
                        .projection(Projections.include(listKey))
                        .first();
        if (doc == null || !doc.containsKey(listKey)) {
            return List.of();
        }
        List<?> rawList = doc.getList(listKey, Object.class);
        List<T> result = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            result.add(deserializeValue(item, itemType));
        }
        return result;
    }

    // ────────────────── Versioning ──────────────────

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        validateKey(key);
        String slotId = slotId(userId, sessionId);
        String versionField = VERSION_PREFIX + key;
        Document doc =
                collection
                        .find(Filters.eq(slotId))
                        .projection(Projections.include(key, versionField))
                        .first();
        if (doc == null || !doc.containsKey(key)) {
            return new VersionedState<>(null, 0L);
        }
        long version = doc.containsKey(versionField) ? doc.getLong(versionField) : 0L;
        T value = deserializeValue(doc.get(key), type);
        return new VersionedState<>(value, version);
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        validateKey(key);
        if (expectedVersion == UNVERSIONED) {
            save(userId, sessionId, key, value);
            String slotId = slotId(userId, sessionId);
            String versionField = VERSION_PREFIX + key;
            Document doc =
                    collection
                            .find(Filters.eq(slotId))
                            .projection(Projections.include(versionField))
                            .first();
            if (doc == null) {
                return UNVERSIONED;
            }
            Long v = doc.getLong(versionField);
            return v != null ? v : 0L;
        }

        String slotId = slotId(userId, sessionId);
        String versionField = VERSION_PREFIX + key;
        String json = JsonUtils.getJsonCodec().toJson(value);

        if (expectedVersion == 0) {
            Bson filter = Filters.and(Filters.eq(slotId), Filters.exists(versionField, false));
            Bson update =
                    Updates.combine(
                            Updates.set(key, Document.parse(json)),
                            Updates.set(versionField, 1L),
                            Updates.set(FIELD_UPDATED_AT, new Date()),
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            try {
                Document result =
                        collection.findOneAndUpdate(
                                filter,
                                update,
                                new FindOneAndUpdateOptions()
                                        .upsert(true)
                                        .returnDocument(ReturnDocument.AFTER));
                if (result == null) {
                    return UNVERSIONED;
                }
                Long newVersion = result.getLong(versionField);
                return newVersion != null && newVersion == 1L ? 1L : UNVERSIONED;
            } catch (MongoWriteException e) {
                if (e.getError().getCode() == 11000) {
                    return UNVERSIONED;
                }
                throw e;
            } catch (MongoCommandException e) {
                if (e.getErrorCode() == 11000) {
                    return UNVERSIONED;
                }
                throw e;
            }
        }

        Bson filter = Filters.and(Filters.eq(slotId), Filters.eq(versionField, expectedVersion));
        Bson update =
                Updates.combine(
                        Updates.set(key, Document.parse(json)),
                        Updates.inc(versionField, 1L),
                        Updates.set(FIELD_UPDATED_AT, new Date()));
        Document result =
                collection.findOneAndUpdate(
                        filter,
                        update,
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (result == null) {
            return UNVERSIONED;
        }
        Long newVersion = result.getLong(versionField);
        return newVersion != null ? newVersion : UNVERSIONED;
    }

    // ────────────────── Session CRUD ──────────────────

    @Override
    public boolean exists(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        return collection.find(Filters.eq(slotId)).projection(Projections.include("_id")).first()
                != null;
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        collection.deleteOne(Filters.eq(slotId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        validateKey(key);
        String slotId = slotId(userId, sessionId);
        Document unsetFields =
                new Document(key, "")
                        .append(VERSION_PREFIX + key, "")
                        .append(HASH_PREFIX + key, "")
                        .append(key + LIST_SUFFIX, "");
        collection.updateOne(Filters.eq(slotId), new Document("$unset", unsetFields));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String normalizedUser = normalizeUser(userId);
        List<String> ids =
                collection
                        .distinct(
                                FIELD_SESSION_ID,
                                Filters.eq(FIELD_USER_ID, normalizedUser),
                                String.class)
                        .into(new ArrayList<>());
        return new LinkedHashSet<>(ids);
    }

    // ────────────────── Close ──────────────────

    @Override
    public void close() {
        if (ownsClient) {
            mongoClient.close();
        }
    }

    // ────────────────── Internal Helpers ──────────────────

    private static String normalizeUser(String userId) {
        return (userId == null || userId.isBlank()) ? ANON_USER : userId;
    }

    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + ":" + sessionId;
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (!SAFE_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "key must match pattern "
                            + SAFE_KEY_PATTERN
                            + " but was: "
                            + key
                            + " (MongoDB field names cannot contain '.' or '$')");
        }
    }

    private <T extends State> T deserializeValue(Object fieldValue, Class<T> type) {
        if (fieldValue == null) {
            return null;
        }
        String json;
        if (fieldValue instanceof String s) {
            json = s;
        } else if (fieldValue instanceof Document doc) {
            json = doc.toJson();
        } else {
            json = fieldValue.toString();
        }
        return JsonUtils.getJsonCodec().fromJson(json, type);
    }

    private List<Document> toDocumentList(List<? extends State> values) {
        List<Document> result = new ArrayList<>(values.size());
        for (State item : values) {
            result.add(Document.parse(JsonUtils.getJsonCodec().toJson(item)));
        }
        return result;
    }

    private static UpdateOptions upsert() {
        return new UpdateOptions().upsert(true);
    }

    // ────────────────── Builder ──────────────────

    /**
     * Builder for {@link MongoAgentStateStore}.
     */
    public static class Builder {
        private MongoClient mongoClient;
        private String connectionString;
        private String databaseName;
        private String collectionName;

        /**
         * Use an existing {@link MongoClient}. The caller owns its lifecycle; {@link
         * MongoAgentStateStore#close()} will NOT close a client supplied through this method.
         *
         * @param mongoClient the client to use
         * @return this builder
         */
        public Builder mongoClient(MongoClient mongoClient) {
            this.mongoClient = mongoClient;
            return this;
        }

        /**
         * MongoDB connection string (e.g. {@code "mongodb://localhost:27017"}).
         *
         * <p>A new {@link MongoClient} will be created internally and closed when {@link
         * MongoAgentStateStore#close()} is called.
         *
         * @param connectionString the connection string
         * @return this builder
         */
        public Builder connectionString(String connectionString) {
            this.connectionString = connectionString;
            return this;
        }

        /**
         * Database name. Defaults to {@code "agentscope"}.
         *
         * @param databaseName the database name
         * @return this builder
         */
        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        /**
         * Collection name. Defaults to {@code "agentscope_sessions"}.
         *
         * @param collectionName the collection name
         * @return this builder
         */
        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        /**
         * Build the {@link MongoAgentStateStore}.
         *
         * @return a new instance
         */
        public MongoAgentStateStore build() {
            return new MongoAgentStateStore(this);
        }
    }
}
