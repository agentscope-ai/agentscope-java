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
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.agentscope.extensions.mongodb.MongoIndexUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * MongoDB-backed implementation of {@link BaseStore}.
 *
 * <p>Each item is stored as a separate MongoDB document. Namespace paths and keys are encoded
 * into a compound {@code _id} for uniqueness, using {@code \\u001F} (ASCII Unit Separator) as
 * the segment delimiter. The {@code namespace} field stores the full namespace path with a
 * trailing separator, enabling prefix-matching via range queries ({@code $gte}/{$lt}) on the
 * compound index — consistent with {@code PostgresBaseStore} and {@code JdbcStore}.
 *
 * <p>Supports optimistic concurrency via a {@code version} field. Value serialization uses
 * {@link ObjectMapper#convertValue} to avoid an intermediate JSON string round-trip.
 */
public class MongoBaseStore implements BaseStore {

    private static final String FIELD_ID = "_id";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_NAMESPACE = "namespace";
    private static final String FIELD_VALUE = "value";
    private static final String FIELD_VERSION = "version";

    static final char NS_SEPARATOR = '\u001F';

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
        validateKey(key);
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
        validateKey(key);
        String id = itemDocId(namespace, key);
        String nsKey = namespacePath(namespace);
        Document valueDoc = toDocument(value);
        Bson setFields =
                Updates.combine(Updates.set(FIELD_VALUE, valueDoc), Updates.inc(FIELD_VERSION, 1L));
        Bson setOnInsert =
                Updates.combine(
                        Updates.setOnInsert(FIELD_ID, id),
                        Updates.setOnInsert(FIELD_KEY, key),
                        Updates.setOnInsert(FIELD_NAMESPACE, nsKey));
        collection.updateOne(Filters.eq(id), Updates.combine(setFields, setOnInsert), upsert());
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        validateKey(key);
        String id = itemDocId(namespace, key);
        String nsKey = namespacePath(namespace);
        Document valueDoc = toDocument(value);

        Document result;
        if (expectedVersion == 0) {
            Document doc =
                    new Document(FIELD_ID, id)
                            .append(FIELD_VALUE, valueDoc)
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
                            Updates.set(FIELD_VALUE, valueDoc),
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

    /**
     * Searches for items within a namespace, including child namespaces (prefix matching).
     *
     * <p>The namespace path is encoded with a trailing separator so that a range query
     * {@code gte(nsPrefix)} / {@code lt(nsPrefix + \uFFFF)} matches both the exact namespace
     * and all descendant sub-namespaces, consistent with {@code InMemoryStore},
     * {@code PostgresBaseStore}, and {@code JdbcStore}.
     *
     * <p><b>Note:</b> the upper bound uses {@code Character.MAX_VALUE} ({@code \uFFFF}), which
     * does not match namespace segments containing Unicode supplementary characters (code points
     * above {@code U+FFFF}, such as emoji). This is a theoretical limitation — supplementary
     * characters are unlikely in namespace segments — and the range query preserves full B-tree
     * index utilisation, which a {@code $regex} prefix match would not.
     */
    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        String nsPrefix = namespacePath(namespace);
        List<Document> docs =
                collection
                        .find(
                                Filters.and(
                                        Filters.gte(FIELD_NAMESPACE, nsPrefix),
                                        Filters.lt(
                                                FIELD_NAMESPACE, nsPrefix + Character.MAX_VALUE)))
                        .sort(Sorts.ascending(FIELD_KEY))
                        .skip(Math.max(offset, 0))
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
        validateKey(key);
        String id = itemDocId(namespace, key);
        collection.deleteOne(Filters.eq(id));
    }

    // ────────────────── Internal Helpers ──────────────────

    private void ensureIndexes() {
        MongoIndexUtils.createIndexWithMigration(
                collection,
                Indexes.compoundIndex(
                        Indexes.ascending(FIELD_NAMESPACE), Indexes.ascending(FIELD_KEY)),
                new IndexOptions());
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
        if (key.indexOf(NS_SEPARATOR) >= 0) {
            throw new IllegalArgumentException("key must not contain the unit separator (0x1F)");
        }
    }

    private static String itemDocId(List<String> namespace, String key) {
        return namespacePath(namespace) + key;
    }

    /**
     * Encodes a namespace path into a string suitable for prefix matching. Segments are joined
     * with {@code \\u001F} (ASCII Unit Separator), and a trailing separator is appended so that
     * range queries ({@code gte}/{@code lt}) can match both the exact namespace and all
     * descendant sub-namespaces — consistent with {@code PostgresBaseStore} and
     * {@code JdbcStore}.
     *
     * @throws NullPointerException if namespace is null
     * @throws IllegalArgumentException if any segment is null or contains the separator char
     */
    private static String namespacePath(List<String> namespace) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < namespace.size(); i++) {
            String segment = namespace.get(i);
            if (segment == null) {
                throw new IllegalArgumentException("namespace segment must not be null");
            }
            if (segment.indexOf(NS_SEPARATOR) >= 0) {
                throw new IllegalArgumentException(
                        "namespace segment must not contain the unit separator (0x1F)");
            }
            if (i > 0) {
                sb.append(NS_SEPARATOR);
            }
            sb.append(segment);
        }
        sb.append(NS_SEPARATOR);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Document toDocument(Map<String, Object> value) {
        Map<String, Object> map =
                objectMapper.convertValue(value == null ? Map.of() : value, MAP_TYPE);
        return new Document(escapeKeys(map));
    }

    private Map<String, Object> parseValue(Object raw) {
        if (raw instanceof Document doc) {
            return unescapeKeys(new LinkedHashMap<>(doc));
        }
        if (raw instanceof String s) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(s, MAP_TYPE);
                return parsed != null ? parsed : Map.of();
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to decode store value", e);
            }
        }
        throw new IllegalStateException(
                "Unexpected store value type: "
                        + (raw == null ? "null" : raw.getClass().getName()));
    }

    // ────────────────── Key escaping for MongoDB field names ──────────────────
    // MongoDB rejects '.' and '$' in field names. We escape them to fullwidth equivalents
    // on write and restore on read, so callers are not restricted by MongoDB naming rules.

    private static final char DOT_ESCAPE = '\uFF0E'; // fullwidth full stop
    private static final char DOLLAR_ESCAPE = '\uFF04'; // fullwidth dollar sign

    @SuppressWarnings("unchecked")
    private static Map<String, Object> escapeKeys(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String escaped = escapeKey(key);
            if (value instanceof Map<?, ?> nested) {
                result.put(escaped, escapeKeys((Map<String, Object>) nested));
            } else if (value instanceof List<?> list) {
                result.put(escaped, escapeListItems(list));
            } else {
                result.put(escaped, value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> escapeListItems(List<?> list) {
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> nested) {
                result.add(escapeKeys((Map<String, Object>) nested));
            } else if (item instanceof List<?> nestedList) {
                result.add(escapeListItems(nestedList));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unescapeKeys(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String unescaped = unescapeKey(key);
            if (value instanceof Map<?, ?> nested) {
                result.put(unescaped, unescapeKeys((Map<String, Object>) nested));
            } else if (value instanceof List<?> list) {
                result.put(unescaped, unescapeListItems(list));
            } else {
                result.put(unescaped, value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> unescapeListItems(List<?> list) {
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> nested) {
                result.add(unescapeKeys((Map<String, Object>) nested));
            } else if (item instanceof List<?> nestedList) {
                result.add(unescapeListItems(nestedList));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    private static String escapeKey(String key) {
        if (key.indexOf('.') < 0 && key.indexOf('$') < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                sb.append(DOT_ESCAPE);
            } else if (c == '$') {
                sb.append(DOLLAR_ESCAPE);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescapeKey(String key) {
        if (key.indexOf(DOT_ESCAPE) < 0 && key.indexOf(DOLLAR_ESCAPE) < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == DOT_ESCAPE) {
                sb.append('.');
            } else if (c == DOLLAR_ESCAPE) {
                sb.append('$');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static UpdateOptions upsert() {
        return new UpdateOptions().upsert(true);
    }
}
