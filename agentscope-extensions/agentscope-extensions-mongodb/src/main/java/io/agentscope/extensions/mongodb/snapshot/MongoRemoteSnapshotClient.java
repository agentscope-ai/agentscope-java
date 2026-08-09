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
package io.agentscope.extensions.mongodb.snapshot;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.IndexOptions;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RemoteSnapshotClient} backed by a MongoDB collection.
 *
 * <p>Stores sandbox workspace tar archives as BSON Binary in a collection with documents of the
 * form {@code {_id: snapshotId, data: Binary, createdAt: Date}}.
 */
public class MongoRemoteSnapshotClient implements RemoteSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(MongoRemoteSnapshotClient.class);

    private static final String DEFAULT_COLLECTION = "agentscope_snapshots";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_CREATED_AT = "createdAt";
    // MongoDB BSON document size limit is 16 MB; cap at 15 MB to leave headroom for
    // metadata. For larger snapshots, use GridFS (not yet implemented).
    private static final int MAX_SNAPSHOT_BYTES = 15 * 1024 * 1024; // 15 MB

    private final MongoCollection<Document> collection;

    public MongoRemoteSnapshotClient(
            MongoClient mongoClient,
            String databaseName,
            String collectionName,
            boolean initializeSchema) {
        Objects.requireNonNull(mongoClient, "mongoClient");
        String coll = collectionName != null ? collectionName : DEFAULT_COLLECTION;
        MongoDatabase db =
                mongoClient.getDatabase(databaseName != null ? databaseName : "agentscope");
        this.collection = db.getCollection(coll);
        if (initializeSchema) {
            initSchema();
        }
    }

    private void initSchema() {
        try {
            collection.createIndex(Indexes.ascending(FIELD_CREATED_AT));
            collection.createIndex(
                    Indexes.ascending(FIELD_CREATED_AT),
                    new IndexOptions()
                            .expireAfter(7 * 24 * 3600L, TimeUnit.SECONDS));
        } catch (Exception e) {
            log.warn(
                    "Failed to initialize snapshot collection index '{}': {}",
                    collection.getNamespace(),
                    e.getMessage());
        }
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(data, "data");
        byte[] bytes = readAllBounded(data, MAX_SNAPSHOT_BYTES);
        Document doc =
                new Document(FIELD_DATA, new Binary(bytes)).append(FIELD_CREATED_AT, new Date());
        collection.replaceOne(Filters.eq(snapshotId), doc, new ReplaceOptions().upsert(true));
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Document doc =
                collection
                        .find(Filters.eq(snapshotId))
                        .projection(Projections.include(FIELD_DATA))
                        .first();
        if (doc == null) {
            throw new FileNotFoundException("Snapshot not found in MongoDB: " + snapshotId);
        }
        Binary binary = doc.get(FIELD_DATA, Binary.class);
        return new ByteArrayInputStream(binary.getData());
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        return collection
                        .find(Filters.eq(snapshotId))
                        .projection(Projections.include("_id"))
                        .first()
                != null;
    }

    /**
     * Deletes a snapshot from MongoDB.
     *
     * @param snapshotId the snapshot identifier
     * @return {@code true} if a document was deleted, {@code false} if no matching snapshot existed
     * @throws Exception if a MongoDB error occurs
     */
    public boolean delete(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        return collection.deleteOne(Filters.eq(snapshotId)).getDeletedCount() > 0;
    }

    private static byte[] readAllBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException(
                        "Snapshot size exceeds maximum allowed (" + maxBytes + " bytes)");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
