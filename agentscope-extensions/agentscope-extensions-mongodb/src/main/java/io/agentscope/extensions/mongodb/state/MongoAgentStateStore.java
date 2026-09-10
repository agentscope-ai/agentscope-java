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
import com.mongodb.client.model.Aggregates;
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
import io.agentscope.extensions.mongodb.MongoConstants;
import io.agentscope.extensions.mongodb.MongoIndexUtils;
import io.agentscope.extensions.mongodb.MongoKeyEscaper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed implementation of {@link AgentStateStore}.
 *
 * <p>Each session is stored as a single MongoDB document with a compound {@code _id} of
 * {@code {user, session}} to prevent cross-tenant key collisions. State keys are stored inside
 * a {@code states} sub-document (via dot notation, e.g. {@code states.foo}), keeping them
 * isolated from reserved top-level fields such as {@code _id}, {@code user_id},
 * {@code session_id}, and {@code _updated_at}. Per-key versions live in a {@code versions}
 * sub-document and list-content hashes in a {@code hashes} sub-document.
 *
 * <p>Supports optimistic concurrency via the {@code versions.<key>} field. List state uses
 * {@link ListHashUtil} for change detection to avoid unnecessary full rewrites. The list
 * element count is obtained server-side via {@code $size} aggregation to avoid transferring
 * the full array just to call {@code .size()}.
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

    private static final Logger log = LoggerFactory.getLogger(MongoAgentStateStore.class);

    private static final String ANON_USER = "__anon__";
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_SESSION_ID = "session_id";
    private static final String FIELD_UPDATED_AT = "_updated_at";
    private static final String FIELD_STATES = "states";
    private static final String FIELD_VERSIONS = "versions";
    private static final String FIELD_HASHES = "hashes";
    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final MongoClient mongoClient;
    private final boolean ownsClient;
    private final MongoCollection<Document> collection;
    private final BiConsumer<String, String> onDeleteCallback;

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

        String dbName;
        if (builder.databaseName != null) {
            dbName = builder.databaseName;
        } else if (builder.connectionString != null) {
            String uriDb = new ConnectionString(builder.connectionString).getDatabase();
            dbName = uriDb != null ? uriDb : MongoConstants.DEFAULT_DATABASE;
        } else {
            dbName = MongoConstants.DEFAULT_DATABASE;
        }
        String collName =
                builder.collectionName != null
                        ? builder.collectionName
                        : MongoConstants.SESSIONS_COLLECTION;

        MongoDatabase db = this.mongoClient.getDatabase(dbName);
        this.collection = db.getCollection(collName);
        this.onDeleteCallback = builder.onDeleteCallback;

        ensureIndexes(builder.ttlDays);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    // ────────────────── Index Management ──────────────────

    private void ensureIndexes(Integer ttlDays) {
        MongoIndexUtils.createIndexWithMigration(
                collection,
                Indexes.compoundIndex(
                        Indexes.ascending(FIELD_USER_ID), Indexes.ascending(FIELD_SESSION_ID)),
                new IndexOptions());

        // Session TTL is opt-in, aligned with Postgres/JDBC/Redis (no forced expiry by default).
        if (ttlDays != null && ttlDays > 0) {
            long ttlSeconds = (long) ttlDays * 24 * 3600;
            MongoIndexUtils.createIndexWithMigration(
                    collection,
                    Indexes.ascending(FIELD_UPDATED_AT),
                    new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS).sparse(true));
        }
    }

    // ────────────────── Single Value CRUD ──────────────────

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        String versionField = FIELD_VERSIONS + "." + key;
        Document valueDoc = toDocument(value);
        Bson setFields =
                Updates.combine(
                        Updates.set(stateField, valueDoc),
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
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        Document doc =
                collection
                        .find(Filters.eq(slotId))
                        .projection(Projections.include(stateField))
                        .first();
        if (doc == null) {
            return Optional.empty();
        }
        Document states = doc.get(FIELD_STATES, Document.class);
        if (states == null || !states.containsKey(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(deserializeValue(states.get(key), type));
    }

    // ────────────────── List CRUD ──────────────────

    /**
     * Saves a list of state values with incremental-append optimization.
     *
     * <p>The list element count is obtained server-side via {@code $size} aggregation so only
     * a single integer is transferred, not the full array. This avoids negating the network
     * savings of incremental append for large conversation histories.
     *
     * <p><b>Concurrency note:</b> this method performs a read-then-write to decide between
     * incremental append and full replacement, so it is intentionally NOT atomic: concurrent
     * writers for the same {@code (userId, sessionId, key)} may interleave reads and writes,
     * causing duplicated or lost appends. This matches the harness execution model, where a
     * {@link io.agentscope.harness.agent.sandbox.SandboxExecutionGuard} serialises calls per
     * isolation slot, so each session normally has at most one writer at a time. Callers that
     * write the same list concurrently from outside the harness must synchronise externally.
     */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        String hashField = FIELD_HASHES + "." + key;

        // Obtain existing count and stored hash server-side via $size aggregation.
        // Only a number and a hash string are transferred — not the full array.
        // $cond($isArray(stateField), $size(stateField), 0) handles both array and
        // non-array / missing fields gracefully.
        Document countExpr =
                new Document(
                        "$cond",
                        new Document("if", new Document("$isArray", "$" + stateField))
                                .append("then", new Document("$size", "$" + stateField))
                                .append("else", 0));
        Document aggResult =
                collection
                        .aggregate(
                                Arrays.asList(
                                        Aggregates.match(Filters.eq(slotId)),
                                        Aggregates.project(
                                                Projections.fields(
                                                        Projections.computed("count", countExpr),
                                                        Projections.computed(
                                                                "storedHash", "$" + hashField)))))
                        .first();

        String storedHash = null;
        int existingCount = 0;
        if (aggResult != null) {
            storedHash = aggResult.getString("storedHash");
            Integer count = aggResult.getInteger("count");
            if (count != null) {
                existingCount = count;
            }
        }

        String currentHash = ListHashUtil.computeHash(values);

        if (ListHashUtil.needsFullRewrite(values, storedHash, existingCount)) {
            List<Document> bsonList = toDocumentList(values);
            Bson setFields =
                    Updates.combine(
                            Updates.set(stateField, bsonList),
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
            // $setOnInsert is required here as well: a brand-new session whose first write is
            // a non-empty list lands in this append branch (needsFullRewrite returns false for
            // an absent document), so without it the upserted document would carry no
            // user_id/session_id fields and listSessionIds could never see it — not even after
            // later full-rewrite saves, since $setOnInsert only fires on the initial insert.
            Bson update =
                    Updates.combine(
                            Updates.pushEach(stateField, newDocs),
                            Updates.set(hashField, currentHash),
                            Updates.set(FIELD_UPDATED_AT, new Date()),
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            collection.updateOne(Filters.eq(slotId), update, upsert());
        } else {
            // Hash unchanged but size decreased (elements removed) — force full rewrite.
            List<Document> bsonList = toDocumentList(values);
            Bson setFields =
                    Updates.combine(
                            Updates.set(stateField, bsonList),
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
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        Document doc =
                collection
                        .find(Filters.eq(slotId))
                        .projection(Projections.include(stateField))
                        .first();
        if (doc == null) {
            return List.of();
        }
        Document states = doc.get(FIELD_STATES, Document.class);
        if (states == null || !states.containsKey(key)) {
            return List.of();
        }
        List<?> rawList = states.getList(key, Object.class);
        if (rawList == null) {
            return List.of();
        }
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
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        String versionField = FIELD_VERSIONS + "." + key;
        Document doc =
                collection
                        .find(Filters.eq(slotId))
                        .projection(Projections.include(stateField, versionField))
                        .first();
        if (doc == null) {
            return new VersionedState<>(null, 0L);
        }
        Document states = doc.get(FIELD_STATES, Document.class);
        if (states == null || !states.containsKey(key)) {
            return new VersionedState<>(null, 0L);
        }
        Document versions = doc.get(FIELD_VERSIONS, Document.class);
        long version = (versions != null && versions.containsKey(key)) ? versions.getLong(key) : 0L;
        T value = deserializeValue(states.get(key), type);
        return new VersionedState<>(value, version);
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        validateStateKey(key);
        if (expectedVersion == UNVERSIONED) {
            // Unconditional overwrite (last-writer-wins). A duplicate-key error (11000) can
            // only occur on the very first concurrent insert of a brand-new slot — two writers
            // upserting the same _id — so retry once; on the second attempt the document
            // already exists and the upsert degrades to a plain update.
            Document slotId = slotId(userId, sessionId);
            String stateField = FIELD_STATES + "." + key;
            String versionField = FIELD_VERSIONS + "." + key;
            Document valueDoc = toDocument(value);
            Bson setFields =
                    Updates.combine(
                            Updates.set(stateField, valueDoc),
                            Updates.inc(versionField, 1L),
                            Updates.set(FIELD_UPDATED_AT, new Date()));
            Bson setOnInsert =
                    Updates.combine(
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            int attempt = 0;
            while (true) {
                try {
                    Document result =
                            collection.findOneAndUpdate(
                                    Filters.eq(slotId),
                                    Updates.combine(setFields, setOnInsert),
                                    new FindOneAndUpdateOptions()
                                            .upsert(true)
                                            .returnDocument(ReturnDocument.AFTER));
                    if (result == null) {
                        return UNVERSIONED;
                    }
                    Document versions = result.get(FIELD_VERSIONS, Document.class);
                    Long v = versions != null ? versions.getLong(key) : null;
                    return v != null ? v : 0L;
                } catch (MongoWriteException e) {
                    if (e.getError().getCode() != 11000 || attempt > 0) {
                        throw e;
                    }
                } catch (MongoCommandException e) {
                    if (e.getErrorCode() != 11000 || attempt > 0) {
                        throw e;
                    }
                }
                attempt++;
            }
        }

        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        String versionField = FIELD_VERSIONS + "." + key;
        Document valueDoc = toDocument(value);

        if (expectedVersion == 0) {
            Bson filter = Filters.and(Filters.eq(slotId), Filters.exists(versionField, false));
            Bson update =
                    Updates.combine(
                            Updates.set(stateField, valueDoc),
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
                Document versions = result.get(FIELD_VERSIONS, Document.class);
                Long newVersion = versions != null ? versions.getLong(key) : null;
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
                        Updates.set(stateField, valueDoc),
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
        Document versions = result.get(FIELD_VERSIONS, Document.class);
        Long newVersion = versions != null ? versions.getLong(key) : null;
        return newVersion != null ? newVersion : UNVERSIONED;
    }

    // ────────────────── Session CRUD ──────────────────

    @Override
    public boolean exists(String userId, String sessionId) {
        Document slotId = slotId(userId, sessionId);
        return collection.find(Filters.eq(slotId)).projection(Projections.include("_id")).first()
                != null;
    }

    @Override
    public void delete(String userId, String sessionId) {
        Document slotId = slotId(userId, sessionId);
        if (onDeleteCallback != null) {
            try {
                onDeleteCallback.accept(userId, sessionId);
            } catch (Exception e) {
                log.warn(
                        "[mongo-state] onDeleteCallback failed for session {}, proceeding"
                                + " with deletion",
                        sessionId,
                        e);
            }
        }
        collection.deleteOne(Filters.eq(slotId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        Bson unsetFields =
                Updates.combine(
                        Updates.unset(FIELD_STATES + "." + key),
                        Updates.unset(FIELD_VERSIONS + "." + key),
                        Updates.unset(FIELD_HASHES + "." + key),
                        Updates.set(FIELD_UPDATED_AT, new Date()));
        collection.updateOne(Filters.eq(slotId), unsetFields);
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

    /**
     * Builds a compound {@code _id} document from {@code userId} and {@code sessionId}. Using a
     * structured _id instead of a {@code ":"}-delimited string prevents cross-tenant collisions
     * when either side legitimately contains the separator character.
     */
    private static Document slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return new Document("user", normalizeUser(userId)).append("session", sessionId);
    }

    /**
     * Validates a state key for use as a field name inside the {@code states} sub-document (via
     * dot notation). The key must not contain {@code .} or {@code $} to prevent dot-traversal
     * injection and operator injection. Reserved top-level field names (e.g. {@code _id},
     * {@code user_id}) are no longer a concern because state keys live inside {@code states.<key>}
     * and cannot collide with the document schema.
     */
    private static void validateStateKey(String key) {
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

    @SuppressWarnings("unchecked")
    private Document toDocument(State value) {
        Map<String, Object> map = JsonUtils.getJsonCodec().convertValue(value, Map.class);
        return new Document(MongoKeyEscaper.escape(map == null ? Map.of() : map));
    }

    private List<Document> toDocumentList(List<? extends State> values) {
        List<Document> result = new ArrayList<>(values.size());
        for (State item : values) {
            result.add(toDocument(item));
        }
        return result;
    }

    private <T extends State> T deserializeValue(Object fieldValue, Class<T> type) {
        if (fieldValue == null) {
            return null;
        }
        Object unescaped = fieldValue;
        if (fieldValue instanceof Document doc) {
            unescaped = MongoKeyEscaper.unescape(doc);
        }
        return JsonUtils.getJsonCodec().convertValue(unescaped, type);
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
        private Integer ttlDays;
        private BiConsumer<String, String> onDeleteCallback;

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
         * Database name. Defaults to {@code "agentscope"}. When a connection string is
         * provided, the database name in the URI (if any) is used as a fallback before the
         * default.
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
         * Optional session TTL in days. When set to a positive value, a TTL index is created on
         * {@code _updated_at} so MongoDB automatically removes documents that have not been updated
         * within the specified number of days.
         *
         * <p>Defaults to {@code null} (no TTL), aligned with Postgres/JDBC/Redis which retain
         * sessions indefinitely by default.
         *
         * @param ttlDays number of days, or {@code null} to disable automatic expiry
         * @return this builder
         */
        public Builder ttlDays(Integer ttlDays) {
            if (ttlDays != null && ttlDays <= 0) {
                throw new IllegalArgumentException("ttlDays must be positive or null");
            }
            this.ttlDays = ttlDays;
            return this;
        }

        /**
         * Optional callback invoked before a session document is deleted via {@link
         * MongoAgentStateStore#delete(String, String)}. Receives the userId and sessionId.
         * Used by {@link io.agentscope.extensions.mongodb.MongoDistributedStore} to
         * cascade-delete associated snapshots.
         *
         * @param onDeleteCallback the callback (userId, sessionId) to run before session deletion
         * @return this builder
         */
        public Builder onDeleteCallback(BiConsumer<String, String> onDeleteCallback) {
            this.onDeleteCallback = onDeleteCallback;
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
