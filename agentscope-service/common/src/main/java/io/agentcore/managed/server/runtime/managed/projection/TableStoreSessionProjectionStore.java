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
package io.agentcore.managed.server.runtime.managed.projection;

import com.alicloud.openservices.tablestore.SyncClientInterface;
import com.alicloud.openservices.tablestore.TableStoreException;
import com.alicloud.openservices.tablestore.model.BatchGetRowRequest;
import com.alicloud.openservices.tablestore.model.BatchWriteRowRequest;
import com.alicloud.openservices.tablestore.model.ColumnValue;
import com.alicloud.openservices.tablestore.model.Condition;
import com.alicloud.openservices.tablestore.model.CreateTableRequest;
import com.alicloud.openservices.tablestore.model.DescribeTableRequest;
import com.alicloud.openservices.tablestore.model.Direction;
import com.alicloud.openservices.tablestore.model.GetRangeRequest;
import com.alicloud.openservices.tablestore.model.GetRowRequest;
import com.alicloud.openservices.tablestore.model.MultiRowQueryCriteria;
import com.alicloud.openservices.tablestore.model.PrimaryKey;
import com.alicloud.openservices.tablestore.model.PrimaryKeyBuilder;
import com.alicloud.openservices.tablestore.model.PrimaryKeyType;
import com.alicloud.openservices.tablestore.model.PrimaryKeyValue;
import com.alicloud.openservices.tablestore.model.PutRowRequest;
import com.alicloud.openservices.tablestore.model.RangeRowQueryCriteria;
import com.alicloud.openservices.tablestore.model.Row;
import com.alicloud.openservices.tablestore.model.RowExistenceExpectation;
import com.alicloud.openservices.tablestore.model.RowPutChange;
import com.alicloud.openservices.tablestore.model.RowUpdateChange;
import com.alicloud.openservices.tablestore.model.SingleRowQueryCriteria;
import com.alicloud.openservices.tablestore.model.TableMeta;
import com.alicloud.openservices.tablestore.model.TableOptions;
import com.alicloud.openservices.tablestore.model.UpdateRowRequest;
import com.alicloud.openservices.tablestore.model.condition.SingleColumnValueCondition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable query rows and one conditional visibility publication; no cross-table transaction. */
public final class TableStoreSessionProjectionStore implements SessionProjectionStore {
    private final SyncClientInterface client;
    private final ObjectMapper mapper;
    private final String prefix;
    // Only prefixes proven readable by a successful head read/CAS enter this bounded cache.
    // Rows are immutable and build identifiers cannot be reused after deletion.
    private final Map<Build, Progress> published = new ConcurrentHashMap<>();
    // Only confirmed immutable creation rows; never a cache of latest query state.
    private final Map<Identity, String> creations =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Identity, String> eldest) {
                    return size() > 65536;
                }
            };

    public TableStoreSessionProjectionStore(
            SyncClientInterface client, ObjectMapper mapper, String prefix) {
        if (prefix == null || !prefix.matches("[A-Za-z][A-Za-z0-9_]{0,60}"))
            throw new IllegalArgumentException("Invalid query table prefix");
        this.client = client;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.prefix = prefix;
    }

    /** Provision explicitly, never from an execution transaction. No TTL/automatic GC. */
    public void initialize() {
        for (String suffix :
                List.of(
                        "EventHistory",
                        "ResourceVersions",
                        "ResourceDirectory",
                        "ProjectionProgress",
                        "ResourceIndex")) {
            var meta = new TableMeta(table(suffix));
            meta.addPrimaryKeyColumn("partition", PrimaryKeyType.STRING);
            meta.addPrimaryKeyColumn(
                    "position",
                    (suffix.equals("ProjectionProgress") || suffix.equals("ResourceIndex"))
                            ? PrimaryKeyType.STRING
                            : PrimaryKeyType.INTEGER);
            if (suffix.equals("ResourceDirectory"))
                meta.addPrimaryKeyColumn("resource", PrimaryKeyType.STRING);
            try {
                client.createTable(new CreateTableRequest(meta, new TableOptions(-1, 1)));
            } catch (TableStoreException e) {
                if (!"OTSObjectAlreadyExist".equals(e.getErrorCode())) throw e;
                var actual = client.describeTable(new DescribeTableRequest(table(suffix)));
                if (!actual.getTableMeta().getPrimaryKeyMap().equals(meta.getPrimaryKeyMap())
                        || actual.getTableOptions().getTimeToLive() != -1
                        || actual.getTableOptions().getMaxVersions() != 1)
                    throw new IllegalStateException(
                            "Existing query table schema differs: " + suffix);
            }
        }
    }

    @Override
    public void putIndexes(Build b, List<IndexEntry> indexes) {
        var writes = new LinkedHashMap<Identity, Write>();
        for (var row : indexes)
            add(
                    writes,
                    new Write(
                            "ResourceIndex",
                            stringKey(partition(b, "index", row.kind()), row.position()),
                            json(row)));
        writeBatches(writes);
    }

    @Override
    public List<IndexEntry> index(
            Progress p, String kind, String after, boolean descending, int limit) {
        validate(p, 0, limit);
        var result = new ArrayList<IndexEntry>();
        String part = partition(p.build(), "index", kind);
        PrimaryKey cursor =
                stringKey(
                        part,
                        after == null
                                ? (descending ? PrimaryKeyValue.INF_MAX : PrimaryKeyValue.INF_MIN)
                                : PrimaryKeyValue.fromString(after));
        var end = stringKey(part, descending ? PrimaryKeyValue.INF_MIN : PrimaryKeyValue.INF_MAX);
        int scanned = 0;
        do {
            var criteria = new RangeRowQueryCriteria(table("ResourceIndex"));
            criteria.setMaxVersions(1);
            criteria.setInclusiveStartPrimaryKey(cursor);
            criteria.setExclusiveEndPrimaryKey(end);
            criteria.setDirection(descending ? Direction.BACKWARD : Direction.FORWARD);
            criteria.setLimit(limit - result.size() + 1);
            var response = client.getRange(new GetRangeRequest(criteria));
            for (var raw : response.getRows()) {
                var entry = parse(data(raw), IndexEntry.class);
                if (!entry.position().equals(after) && entry.sequence() <= p.visibleSequence())
                    result.add(entry);
                if (result.size() == limit) return List.copyOf(result);
            }
            cursor = response.getNextStartPrimaryKey();
            scanned += response.getRows().size();
            if (scanned > 8192)
                throw new ManagedStateException(
                        "projection_scan_limit", "Unpublished index suffix exceeds scan budget");
        } while (cursor != null);
        return List.copyOf(result);
    }

    private static PrimaryKey stringKey(String partition, String position) {
        return stringKey(partition, PrimaryKeyValue.fromString(position));
    }

    private static PrimaryKey stringKey(String partition, PrimaryKeyValue position) {
        return PrimaryKeyBuilder.createPrimaryKeyBuilder()
                .addPrimaryKeyColumn("partition", PrimaryKeyValue.fromString(partition))
                .addPrimaryKeyColumn("position", position)
                .build();
    }

    @Override
    public Progress open(Build b) {
        var key = progressKey(b);
        insert("ProjectionProgress", key, json(new Progress(b, 0, 0, 0)), false);
        return progress(b);
    }

    @Override
    public Progress progress(Build b) {
        var value = read("ProjectionProgress", progressKey(b));
        if (value == null)
            throw new ManagedStateException("projection_missing", "Projection build is not open");
        var p = parse(value, Progress.class);
        if (!b.equals(p.build()))
            throw new ManagedStateException(
                    "projection_layout_conflict", "Build layout is immutable");
        remember(p);
        return p;
    }

    private void remember(Progress p) {
        if (published.size() >= 65536 && !published.containsKey(p.build())) published.clear();
        published.compute(
                p.build(), (key, old) -> old == null || old.version() < p.version() ? p : old);
    }

    @Override
    public void putEvent(Build b, Event e) {
        insert(
                "EventHistory",
                key(
                        partition(b, "events", segment(e.sequence(), b.eventSegmentSpan())),
                        e.sequence()),
                json(e),
                true);
    }

    @Override
    public void putResource(Build b, Resource r) {
        String part = partition(b, "resource", r.kind(), r.id());
        // Position zero reserves the creation identity, including when versions arrive out of
        // order.
        insert("ResourceVersions", key(part, 0), Long.toString(r.createdSequence()), true);
        insert("ResourceVersions", key(part, r.sequence()), json(r), true);
        insert(
                "ResourceDirectory",
                directoryKey(b, r.kind(), r.createdSequence(), r.id()),
                json(new DirectoryEntry(r.createdSequence(), r.id())),
                true);
    }

    private record Write(String suffix, PrimaryKey key, String value) {}

    private record Identity(String suffix, PrimaryKey key) {}

    @Override
    public void putBatch(Build b, List<Event> events, List<Resource> resources) {
        var writes = new LinkedHashMap<Identity, Write>();
        for (var e : events)
            add(
                    writes,
                    new Write(
                            "EventHistory",
                            key(
                                    partition(
                                            b,
                                            "events",
                                            segment(e.sequence(), b.eventSegmentSpan())),
                                    e.sequence()),
                            json(e)));
        for (var r : resources) {
            String part = partition(b, "resource", r.kind(), r.id());
            add(
                    writes,
                    new Write(
                            "ResourceVersions", key(part, 0), Long.toString(r.createdSequence())));
            add(
                    writes,
                    new Write(
                            "ResourceDirectory",
                            directoryKey(b, r.kind(), r.createdSequence(), r.id()),
                            json(new DirectoryEntry(r.createdSequence(), r.id()))));
            add(writes, new Write("ResourceVersions", key(part, r.sequence()), json(r)));
        }
        writeBatches(writes);
    }

    private void writeBatches(Map<Identity, Write> writes) {
        var batch = new ArrayList<Write>();
        int bytes = 0;
        for (var write : writes.values()) {
            if (knownCreation(write)) continue;
            int size = write.value().getBytes(StandardCharsets.UTF_8).length + 2048;
            if (!batch.isEmpty() && (batch.size() >= 100 || bytes + size > 2 * 1024 * 1024)) {
                writeBatch(batch);
                batch.clear();
                bytes = 0;
            }
            batch.add(write);
            bytes += size;
        }
        if (!batch.isEmpty()) writeBatch(batch);
    }

    private static void add(Map<Identity, Write> writes, Write write) {
        var prior = writes.putIfAbsent(new Identity(write.suffix(), write.key()), write);
        if (prior != null && !prior.value().equals(write.value()))
            throw new ManagedStateException(
                    "projection_content_conflict", "Batch contains conflicting immutable rows");
    }

    private void writeBatch(List<Write> writes) {
        var request = new BatchWriteRowRequest();
        for (var write : writes) {
            var change = new RowPutChange(table(write.suffix()), write.key());
            change.addColumn("data", ColumnValue.fromString(write.value()));
            change.setCondition(new Condition(RowExistenceExpectation.EXPECT_NOT_EXIST));
            request.addRowChange(change);
        }
        var result = client.batchWriteRow(request);
        var existing = new LinkedHashMap<Identity, Write>();
        for (var failed : result.getFailedRows()) {
            var change =
                    (RowPutChange) request.getRowChange(failed.getTableName(), failed.getIndex());
            if (!"OTSConditionCheckFail".equals(failed.getError().getCode()))
                throw new IllegalStateException(
                        "Query batch unavailable: " + failed.getError().getCode());
            String suffix = failed.getTableName().substring(prefix.length());
            add(
                    existing,
                    new Write(
                            suffix,
                            change.getPrimaryKey(),
                            change.getColumnsToPut().get(0).getValue().asString()));
        }
        verifyExisting(existing);
        writes.forEach(this::rememberCreation);
    }

    private void verifyExisting(Map<Identity, Write> existing) {
        if (existing.isEmpty()) return;
        var criteria = new LinkedHashMap<String, MultiRowQueryCriteria>();
        for (var write : existing.values()) {
            var selected =
                    criteria.computeIfAbsent(
                            table(write.suffix()),
                            name -> {
                                var value = new MultiRowQueryCriteria(name);
                                value.setMaxVersions(1);
                                return value;
                            });
            selected.addRow(write.key());
        }
        var request = new BatchGetRowRequest();
        criteria.values().forEach(request::addMultiRowQueryCriteria);
        var result = client.batchGetRow(request);
        if (!result.isAllSucceed())
            throw new IllegalStateException("Query duplicate verification unavailable");
        var remaining = new LinkedHashMap<>(existing);
        for (var row : result.getSucceedRows()) {
            String suffix = row.getTableName().substring(prefix.length());
            var expected =
                    remaining.remove(
                            new Identity(
                                    suffix,
                                    request.getPrimaryKey(row.getTableName(), row.getIndex())));
            if (expected == null
                    || row.getRow() == null
                    || !expected.value().equals(data(row.getRow())))
                throw new ManagedStateException(
                        "projection_content_conflict", "Immutable batch row differs");
        }
        if (!remaining.isEmpty())
            throw new IllegalStateException("Query duplicate verification incomplete");
    }

    private static boolean creation(Write write) {
        return "ResourceDirectory".equals(write.suffix())
                || ("ResourceVersions".equals(write.suffix())
                        && write.key().getPrimaryKeyColumn("position").getValue().asLong() == 0);
    }

    private boolean knownCreation(Write write) {
        if (!creation(write)) return false;
        synchronized (creations) {
            var value = creations.get(new Identity(write.suffix(), write.key()));
            if (value == null) return false;
            if (!value.equals(write.value()))
                throw new ManagedStateException(
                        "projection_content_conflict", "Immutable creation row differs");
            return true;
        }
    }

    private void rememberCreation(Write write) {
        if (!creation(write)) return;
        synchronized (creations) {
            creations.put(new Identity(write.suffix(), write.key()), write.value());
        }
    }

    @Override
    public boolean advanceBatch(Progress p, List<Commit> commits) {
        SessionProjectionStore.validateCommits(p, commits);
        var last = commits.get(commits.size() - 1);
        long through = last.through(), commitVersion = last.version();
        var change = new RowUpdateChange(table("ProjectionProgress"), progressKey(p.build()));
        var condition = new Condition(RowExistenceExpectation.EXPECT_EXIST);
        var equal =
                new SingleColumnValueCondition(
                        "data",
                        SingleColumnValueCondition.CompareOperator.EQUAL,
                        ColumnValue.fromString(json(p)));
        equal.setPassIfMissing(false);
        equal.setLatestVersionsOnly(true);
        condition.setColumnCondition(equal);
        change.setCondition(condition);
        change.put(
                "data",
                ColumnValue.fromString(
                        json(new Progress(p.build(), through, commitVersion, p.version() + 1))));
        try {
            client.updateRow(new UpdateRowRequest(change));
            remember(new Progress(p.build(), through, commitVersion, p.version() + 1));
            return true;
        } catch (TableStoreException e) {
            if (conditional(e)) return false;
            throw e;
        }
    }

    @Override
    public List<Event> events(Progress p, long after, int limit) {
        validate(p, after, limit);
        var result = new ArrayList<Event>();
        var b = p.build();
        if (after == p.visibleSequence()) return result;
        long first = segment(after + 1, b.eventSegmentSpan()),
                last = segment(p.visibleSequence(), b.eventSegmentSpan());
        for (long seg = first; seg <= last && result.size() < limit; seg++) {
            scanBudget(first, seg);
            String part = partition(b, "events", seg);
            long low = Math.max(after + 1, seg * b.eventSegmentSpan() + 1);
            long high = Math.min(p.visibleSequence(), (seg + 1) * b.eventSegmentSpan());
            for (var row :
                    range(
                            "EventHistory",
                            key(part, low),
                            key(part, PrimaryKeyValue.INF_MAX),
                            Direction.FORWARD,
                            limit - result.size())) {
                var event = parse(data(row), Event.class);
                if (event.sequence() <= high) result.add(event);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public Resource resource(Progress p, String kind, String id) {
        validate(p, 0, 1);
        if (p.visibleSequence() == 0) return null;
        String part = partition(p.build(), "resource", kind, id);
        var rows =
                range(
                        "ResourceVersions",
                        key(part, p.visibleSequence()),
                        key(part, 0),
                        Direction.BACKWARD,
                        1);
        return rows.isEmpty() ? null : parse(data(rows.get(0)), Resource.class);
    }

    @Override
    public List<DirectoryEntry> directory(
            Progress p, String kind, long after, String afterId, int limit) {
        validate(p, after, limit);
        if (afterId == null) throw new IllegalArgumentException("Missing directory position");
        var result = new ArrayList<DirectoryEntry>();
        var b = p.build();
        if (p.visibleSequence() == 0) return result;
        long first = segment(Math.max(1, after), b.directorySegmentSpan()),
                last = segment(p.visibleSequence(), b.directorySegmentSpan());
        for (long seg = first; seg <= last && result.size() < limit; seg++) {
            scanBudget(first, seg);
            String part = partition(b, "directory", kind, seg);
            var start = directoryKey(part, Math.max(1, after), PrimaryKeyValue.fromString(afterId));
            var end = directoryKey(part, p.visibleSequence(), PrimaryKeyValue.INF_MAX);
            // The continuation is inclusive; request one extra to discard the previous row.
            for (var row :
                    range(
                            "ResourceDirectory",
                            start,
                            end,
                            Direction.FORWARD,
                            limit - result.size() + 1)) {
                var entry = parse(data(row), DirectoryEntry.class);
                if (entry.createdSequence() > after
                        || (entry.createdSequence() == after
                                && entry.id().compareTo(afterId) > 0)) {
                    result.add(entry);
                    if (result.size() == limit) break;
                }
            }
        }
        return List.copyOf(result);
    }

    private void validate(Progress p, long after, int limit) {
        if (after < 0 || after > p.visibleSequence() || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid query bounds");
        var current = published.get(p.build());
        if (current == null
                || p.visibleSequence() > current.visibleSequence()
                || p.commitVersion() > current.commitVersion()
                || p.version() > current.version()) current = progress(p.build());
        if (p.visibleSequence() > current.visibleSequence()
                || p.commitVersion() > current.commitVersion()
                || p.version() > current.version())
            throw new ManagedStateException(
                    "projection_snapshot_invalid", "Snapshot exceeds published prefix");
    }

    private static void scanBudget(long first, long last) {
        if (last - first > 4096)
            throw new ManagedStateException(
                    "projection_scan_limit", "Directory scan exceeds bounded segment budget");
    }

    private List<Row> range(
            String suffix, PrimaryKey start, PrimaryKey end, Direction direction, int limit) {
        var rows = new ArrayList<Row>();
        var cursor = start;
        do {
            var criteria = new RangeRowQueryCriteria(table(suffix));
            criteria.setMaxVersions(1);
            criteria.setInclusiveStartPrimaryKey(cursor);
            criteria.setExclusiveEndPrimaryKey(end);
            criteria.setDirection(direction);
            criteria.setLimit(limit - rows.size());
            var response = client.getRange(new GetRangeRequest(criteria));
            rows.addAll(response.getRows());
            cursor = response.getNextStartPrimaryKey();
        } while (cursor != null && rows.size() < limit);
        return rows;
    }

    private void insert(String suffix, PrimaryKey key, String value, boolean verify) {
        var write = new Write(suffix, key, value);
        if (verify && knownCreation(write)) return;
        var change = new RowPutChange(table(suffix), key);
        change.addColumn("data", ColumnValue.fromString(value));
        change.setCondition(new Condition(RowExistenceExpectation.EXPECT_NOT_EXIST));
        try {
            client.putRow(new PutRowRequest(change));
        } catch (TableStoreException e) {
            if (!conditional(e)) throw e;
            if (verify && !value.equals(read(suffix, key)))
                throw new ManagedStateException(
                        "projection_content_conflict", "Immutable row differs");
        }
        if (verify) rememberCreation(write);
    }

    private String read(String suffix, PrimaryKey key) {
        var criteria = new SingleRowQueryCriteria(table(suffix), key);
        criteria.setMaxVersions(1);
        var row = client.getRow(new GetRowRequest(criteria)).getRow();
        return row == null ? null : data(row);
    }

    private static String data(Row row) {
        return row.getLatestColumn("data").getValue().asString();
    }

    private static boolean conditional(TableStoreException e) {
        return "OTSConditionCheckFail".equals(e.getErrorCode());
    }

    private String table(String suffix) {
        return prefix + suffix;
    }

    private static long segment(long seq, int span) {
        return (seq - 1) / span;
    }

    private PrimaryKey progressKey(Build b) {
        return PrimaryKeyBuilder.createPrimaryKeyBuilder()
                .addPrimaryKeyColumn("partition", PrimaryKeyValue.fromString(canonical(b.scope())))
                .addPrimaryKeyColumn("position", PrimaryKeyValue.fromString(b.id()))
                .build();
    }

    private String partition(Build b, Object... rest) {
        var values = new ArrayList<Object>();
        values.add(b.scope());
        values.add(b.id());
        values.addAll(List.of(rest));
        return canonical(values);
    }

    private String canonical(Object value) {
        String encoded =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(json(value).getBytes(StandardCharsets.UTF_8));
        String key = ManagedScope.fingerprint(encoded).substring(0, 12) + ":" + encoded;
        if (key.length() > 1024)
            throw new IllegalArgumentException("Projection partition key exceeds 1024 bytes");
        return key;
    }

    private static PrimaryKey key(String part, long seq) {
        return key(part, PrimaryKeyValue.fromLong(seq));
    }

    private static PrimaryKey key(String part, PrimaryKeyValue position) {
        return PrimaryKeyBuilder.createPrimaryKeyBuilder()
                .addPrimaryKeyColumn("partition", PrimaryKeyValue.fromString(part))
                .addPrimaryKeyColumn("position", position)
                .build();
    }

    private PrimaryKey directoryKey(Build b, String kind, long seq, String id) {
        return directoryKey(
                partition(b, "directory", kind, segment(seq, b.directorySegmentSpan())),
                seq,
                PrimaryKeyValue.fromString(id));
    }

    private static PrimaryKey directoryKey(String part, long seq, PrimaryKeyValue id) {
        return PrimaryKeyBuilder.createPrimaryKeyBuilder()
                .addPrimaryKeyColumn("partition", PrimaryKeyValue.fromString(part))
                .addPrimaryKeyColumn("position", PrimaryKeyValue.fromLong(seq))
                .addPrimaryKeyColumn("resource", id)
                .build();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode projection", e);
        }
    }

    private <T> T parse(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new ManagedStateException(
                    "projection_content_invalid", "Cannot decode projection row");
        }
    }
}
