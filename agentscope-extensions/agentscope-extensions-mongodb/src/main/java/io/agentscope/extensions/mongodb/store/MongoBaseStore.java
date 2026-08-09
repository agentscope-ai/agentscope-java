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
package io.agentscope.extensions.mongodb.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed implementation of {@link BaseStore}.
 *
 * <p>Each item is stored as a separate MongoDB document. Namespace paths and keys are encoded into
 * a compound {@code _id} for uniqueness. Supports optimistic concurrency via a {@code version}
 * field.
 */
public class MongoBaseStore implements BaseStore {

    private static final Logger log = LoggerFactory.getLogger(MongoBaseStore.class);

    private static final String FIELD_ID = "_id";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_NAMESPACE = "namespace";
    private static final String FIELD_VALUE = "value";
    private static final String FIELD_VERSION = "version";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MongoCollection<Document> collection;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new instance.
     *
     * @param database       the MongoDB database
     * @param collectionName the collection name
     */
    public MongoBaseStore(MongoDatabase database, String collectionName) {
        this(database, collectionName, new ObjectMapper());
    }

    /**
     * Creates a new instance with a custom ObjectMapper.
     *
     * @param database       the MongoDB database
     * @param collectionName the collection name
     * @param objectMapper   Jackson mapper for serializing values
     */
    public MongoBaseStore(
            MongoDatabase database, String collectionName, ObjectMapper objectMapper) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(collectionName, "collectionName");
        this.collection = database.getCollection(collectionName);
        this.objectMapper = objectMapper;
        ensureIndexes();
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        String id = itemDocId(namespace, key);
        Document doc =
                collection
                        .find(Filters.eq(id))
                        .projection(Projections.include(FIELD_VALUE, FIELD_VERSION))
                        .first();
        if (doc == null) {
            return null;
        }
        Map<String, Object> value = parseValue(doc.get(FIELD_VALUE));
        long version = doc.containsKey(FIELD_VERSION) ? doc.getLong(FIELD_VERSION) : 0L;
        return new StoreItem(key, value, version);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        String id = itemDocId(namespace, key);
        String nsKey = namespacePath(namespace);
        String json = serialize(value);
        Bson setFields =
                Updates.combine(
                        Updates.set(FIELD_VALUE, Document.parse(json)),
                        Updates.set(FIELD_KEY, key),
                        Updates.set(FIELD_NAMESPACE, nsKey));
        Bson setOnInsert = Updates.setOnInsert(FIELD_ID, id);
        collection.updateOne(
                Filters.eq(id),
                Updates.combine(setFields, setOnInsert, Updates.inc(FIELD_VERSION, 1L)),
                upsert());
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        String id = itemDocId(namespace, key);
        String nsKey = namespacePath(namespace);
        String json = serialize(value);

        Document result;
        if (expectedVersion == 0) {
            Document doc =
                    new Document(FIELD_ID, id)
                            .append(FIELD_VALUE, Document.parse(json))
                            .append(FIELD_KEY, key)
                            .append(FIELD_NAMESPACE, nsKey)
                            .append(FIELD_VERSION, 1L);
            try {
                collection.insertOne(doc);
                return true;
            } catch (MongoWriteException e) {
                if (e.getError().getCode() == 11000) {
                    return false;
                }
                throw e;
            }
        } else {
            Bson filter = Filters.and(Filters.eq(id), Filters.eq(FIELD_VERSION, expectedVersion));
            Bson update =
                    Updates.combine(
                            Updates.set(FIELD_VALUE, Document.parse(json)),
                            Updates.set(FIELD_KEY, key),
                            Updates.set(FIELD_NAMESPACE, nsKey),
                            Updates.inc(FIELD_VERSION, 1L));
            result =
                    collection.findOneAndUpdate(
                            filter,
                            update,
                            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        }

        return result != null;
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String nsKey = namespacePath(namespace);
        List<Document> docs =
                collection
                        .find(Filters.eq(FIELD_NAMESPACE, nsKey))
                        .sort(Sorts.ascending(FIELD_KEY))
                        .skip(offset)
                        .limit(limit)
                        .projection(Projections.include(FIELD_KEY, FIELD_VALUE, FIELD_VERSION))
                        .into(new ArrayList<>());
        List<StoreItem> result = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            String key = doc.getString(FIELD_KEY);
            Map<String, Object> value = parseValue(doc.get(FIELD_VALUE));
            long version = doc.containsKey(FIELD_VERSION) ? doc.getLong(FIELD_VERSION) : 0L;
            result.add(new StoreItem(key, value, version));
        }
        return result;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        String id = itemDocId(namespace, key);
        collection.deleteOne(Filters.eq(id));
    }

    // ────────────────── Internal Helpers ──────────────────

    private void ensureIndexes() {
        collection.createIndex(Indexes.ascending(FIELD_NAMESPACE));
        collection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending(FIELD_NAMESPACE), Indexes.ascending(FIELD_KEY)));
    }

    private static String itemDocId(List<String> namespace, String key) {
        return namespacePath(namespace) + "\0" + key;
    }

    private static String namespacePath(List<String> namespace) {
        return namespace.stream().collect(Collectors.joining("\0"));
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize value", e);
        }
    }

    private Map<String, Object> parseValue(Object raw) {
        if (raw instanceof Document doc) {
            return new LinkedHashMap<>(doc);
        }
        if (raw instanceof String s) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(s, MAP_TYPE);
                return parsed != null ? parsed : Map.of();
            } catch (JsonProcessingException e) {
                log.warn(
                        "Failed to parse stored JSON value, returning empty map: {}",
                        e.getMessage());
                return Map.of();
            }
        }
        return Map.of();
    }

    private static UpdateOptions upsert() {
        return new UpdateOptions().upsert(true);
    }
}
