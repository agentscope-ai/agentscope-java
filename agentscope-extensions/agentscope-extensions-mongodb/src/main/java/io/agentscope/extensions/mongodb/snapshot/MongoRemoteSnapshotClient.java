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

import com.mongodb.MongoGridFSException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import io.agentscope.extensions.mongodb.MongoConstants;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RemoteSnapshotClient} backed by MongoDB GridFS.
 *
 * <p>Stores sandbox workspace tar archives in GridFS, which transparently chunks data into
 * 255 KB segments and supports files up to 16 GB — removing the 16 MB BSON document limit
 * that constrains single-document Binary storage.
 *
 * <p>Snapshots have no independent TTL — aligned with Postgres/JDBC/Redis which retain
 * snapshots indefinitely. Snapshots are cleaned up only via {@link #deleteBySessionId(String)}
 * when the owning session is explicitly deleted (cascade cleanup).
 *
 * <p>For backward compatibility, {@link #download} and {@link #exists} fall back to reading
 * from the legacy single-document collection if the file is not found in GridFS.
 */
public class MongoRemoteSnapshotClient implements RemoteSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(MongoRemoteSnapshotClient.class);

    private static final String META_SESSION_ID = "sessionId";
    private static final String META_CREATED_AT = "createdAt";
    private static final String LEGACY_FIELD_DATA = "data";
    private static final String LEGACY_FIELD_SESSION_ID = "sessionId";
    private static final String GRIDFS_FIELD_FILENAME = "filename";

    private final GridFSBucket gridFSBucket;
    // Legacy collection stores snapshots as single BSON documents {_id, data: Binary}.
    // GridFS uses <bucketName>.files and <bucketName>.chunks — the original collection
    // is distinct and must be checked for backward compatibility.
    private final MongoCollection<Document> legacyCollection;
    private final String legacyCollectionName;

    public MongoRemoteSnapshotClient(
            MongoClient mongoClient,
            String databaseName,
            String collectionName,
            boolean initializeSchema) {
        Objects.requireNonNull(mongoClient, "mongoClient");
        this.legacyCollectionName =
                collectionName != null ? collectionName : MongoConstants.SNAPSHOTS_COLLECTION;
        MongoDatabase db =
                mongoClient.getDatabase(
                        databaseName != null ? databaseName : MongoConstants.DEFAULT_DATABASE);
        this.gridFSBucket = GridFSBuckets.create(db, legacyCollectionName);
        this.legacyCollection = db.getCollection(legacyCollectionName);
    }

    /**
     * Package-private constructor for unit testing with pre-built dependencies.
     */
    MongoRemoteSnapshotClient(
            GridFSBucket gridFSBucket, MongoCollection<Document> legacyCollection) {
        this.gridFSBucket = gridFSBucket;
        this.legacyCollection = legacyCollection;
        this.legacyCollectionName = null;
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        upload(snapshotId, data, null);
    }

    /**
     * Uploads a snapshot to GridFS with an optional session association. When {@code sessionId}
     * is non-null, it is stored as GridFS metadata so that {@link #deleteBySessionId(String)}
     * can cascade-delete all snapshots for a given session.
     *
     * @param snapshotId the snapshot identifier (used as GridFS filename)
     * @param data the snapshot data stream
     * @param sessionId optional session identifier for cascade cleanup
     */
    public void upload(String snapshotId, InputStream data, String sessionId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(data, "data");

        // Delete any existing file with the same id (upsert semantics).
        deleteIfExists(snapshotId);

        Document metadata = new Document(META_CREATED_AT, new Date());
        if (sessionId != null) {
            metadata.append(META_SESSION_ID, sessionId);
        }
        GridFSUploadOptions options = new GridFSUploadOptions().metadata(metadata);
        gridFSBucket.uploadFromStream(snapshotId, data, options);
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        try {
            return gridFSBucket.openDownloadStream(snapshotId);
        } catch (MongoGridFSException e) {
            // Fall back to legacy single-document storage for backward compatibility.
            return downloadLegacy(snapshotId);
        }
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        // GridFS _id is auto-generated ObjectId; search by filename instead.
        GridFSFile file = gridFSBucket.find(Filters.eq(GRIDFS_FIELD_FILENAME, snapshotId)).first();
        if (file != null) {
            return true;
        }
        // Fall back to legacy single-document check.
        return existsLegacy(snapshotId);
    }

    /**
     * Deletes a snapshot from GridFS.
     *
     * @param snapshotId the snapshot identifier (stored as GridFS filename)
     * @return {@code true} if a file was deleted, {@code false} if no matching snapshot existed
     * @throws Exception if a MongoDB error occurs
     */
    public boolean delete(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        GridFSFile file = gridFSBucket.find(Filters.eq(GRIDFS_FIELD_FILENAME, snapshotId)).first();
        if (file == null) {
            return deleteLegacy(snapshotId);
        }
        gridFSBucket.delete(file.getId());
        // Also clean up legacy document if it exists (migration scenario).
        deleteLegacy(snapshotId);
        return true;
    }

    /**
     * Deletes all snapshots associated with the given session. Used for cascade cleanup when
     * a session is deleted. Searches both GridFS metadata and legacy documents.
     *
     * @param sessionId the session identifier
     * @return the number of snapshots deleted
     */
    public long deleteBySessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        long count = 0;
        // Delete from GridFS by metadata.
        List<GridFSFile> files =
                gridFSBucket
                        .find(Filters.eq("metadata." + META_SESSION_ID, sessionId))
                        .into(new ArrayList<>());
        for (GridFSFile file : files) {
            try {
                gridFSBucket.delete(file.getId());
                count++;
            } catch (Exception e) {
                log.warn(
                        "[mongo-snapshot] Failed to delete GridFS file '{}' for session {}",
                        file.getFilename(),
                        sessionId,
                        e);
            }
        }
        // Also clean up legacy documents.
        count +=
                legacyCollection
                        .deleteMany(Filters.eq(LEGACY_FIELD_SESSION_ID, sessionId))
                        .getDeletedCount();
        return count;
    }

    private void deleteIfExists(String snapshotId) {
        try {
            GridFSFile existing =
                    gridFSBucket.find(Filters.eq(GRIDFS_FIELD_FILENAME, snapshotId)).first();
            if (existing != null) {
                gridFSBucket.delete(existing.getId());
            }
        } catch (MongoGridFSException ignored) {
            // File does not exist — safe to proceed.
        }
    }

    // ────────────────── Legacy single-document fallback ──────────────────

    private InputStream downloadLegacy(String snapshotId) throws FileNotFoundException {
        Document doc =
                legacyCollection
                        .find(Filters.eq("_id", snapshotId))
                        .projection(Projections.include(LEGACY_FIELD_DATA))
                        .first();
        if (doc == null) {
            throw new FileNotFoundException("Snapshot not found in MongoDB: " + snapshotId);
        }
        Binary binary = doc.get(LEGACY_FIELD_DATA, Binary.class);
        if (binary == null) {
            throw new FileNotFoundException(
                    "Snapshot document has no data field in MongoDB: " + snapshotId);
        }
        return new ByteArrayInputStream(binary.getData());
    }

    private boolean existsLegacy(String snapshotId) {
        return legacyCollection
                        .find(Filters.eq("_id", snapshotId))
                        .projection(Projections.include("_id"))
                        .first()
                != null;
    }

    private boolean deleteLegacy(String snapshotId) {
        return legacyCollection.deleteOne(Filters.eq("_id", snapshotId)).getDeletedCount() > 0;
    }
}
