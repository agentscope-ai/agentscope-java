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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alicloud.openservices.tablestore.SyncClientInterface;
import com.alicloud.openservices.tablestore.model.BatchGetRowRequest;
import com.alicloud.openservices.tablestore.model.BatchGetRowResponse;
import com.alicloud.openservices.tablestore.model.BatchWriteRowRequest;
import com.alicloud.openservices.tablestore.model.BatchWriteRowResponse;
import com.alicloud.openservices.tablestore.model.ConsumedCapacity;
import com.alicloud.openservices.tablestore.model.Error;
import com.alicloud.openservices.tablestore.model.PrimaryKey;
import com.alicloud.openservices.tablestore.model.Response;
import com.alicloud.openservices.tablestore.model.Row;
import com.alicloud.openservices.tablestore.model.RowPutChange;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Build;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Event;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Resource;
import io.agentcore.managed.server.runtime.managed.projection.TableStoreSessionProjectionStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TableStoreProjectionBatchTest {
    record Key(String table, PrimaryKey key) {}

    final Map<Key, Row> rows = new HashMap<>();
    final SyncClientInterface client = mock(SyncClientInterface.class);
    final AtomicBoolean unavailable = new AtomicBoolean();
    final Build build = new Build(new ManagedScope("t", "n", "s"), "v1", 64, 64);

    TableStoreProjectionBatchTest() {
        when(client.batchWriteRow(any()))
                .thenAnswer(
                        call -> {
                            BatchWriteRowRequest request = call.getArgument(0);
                            var result = new BatchWriteRowResponse(new Response());
                            request.getRowChange()
                                    .forEach(
                                            (table, changes) -> {
                                                for (int i = 0; i < changes.size(); i++) {
                                                    var put = (RowPutChange) changes.get(i);
                                                    var key = new Key(table, put.getPrimaryKey());
                                                    var old =
                                                            rows.putIfAbsent(
                                                                    key,
                                                                    new Row(
                                                                            put.getPrimaryKey(),
                                                                            put.getColumnsToPut()));
                                                    if (old == null)
                                                        result.addRowResult(
                                                                new BatchWriteRowResponse.RowResult(
                                                                        table,
                                                                        null,
                                                                        (ConsumedCapacity) null,
                                                                        i));
                                                    else
                                                        result.addRowResult(
                                                                new BatchWriteRowResponse.RowResult(
                                                                        table,
                                                                        null,
                                                                        new Error(
                                                                                "OTSConditionCheckFail",
                                                                                "exists"),
                                                                        i));
                                                }
                                            });
                            return result;
                        });
        when(client.batchGetRow(any()))
                .thenAnswer(
                        call -> {
                            BatchGetRowRequest request = call.getArgument(0);
                            var result = new BatchGetRowResponse(new Response());
                            request.getCriteriasByTable()
                                    .forEach(
                                            (table, criteria) -> {
                                                for (int i = 0; i < criteria.size(); i++) {
                                                    if (unavailable.get())
                                                        result.addResult(
                                                                new BatchGetRowResponse.RowResult(
                                                                        table,
                                                                        new Error(
                                                                                "OTSUnavailable",
                                                                                "retry"),
                                                                        i));
                                                    else
                                                        result.addResult(
                                                                new BatchGetRowResponse.RowResult(
                                                                        table,
                                                                        rows.get(
                                                                                new Key(
                                                                                        table,
                                                                                        criteria
                                                                                                .get(
                                                                                                        i))),
                                                                        null,
                                                                        i));
                                                }
                                            });
                            return result;
                        });
    }

    TableStoreSessionProjectionStore store() {
        return new TableStoreSessionProjectionStore(client, new ObjectMapper(), "Test");
    }

    Resource resource(long sequence, long created) {
        return new Resource("items", "item", sequence, created, false, "{}");
    }

    Event event(long sequence) {
        return new Event(sequence, "c" + sequence, 0, "e" + sequence, "item.completed", "{}", 1);
    }

    @Test
    void confirmedCreationIsNotRewrittenButNewVersionsRemainConditionalAndConflictsFail() {
        var store = store();
        store.putBatch(build, List.of(event(1)), List.of(resource(1, 1)));
        clearInvocations(client);
        store.putBatch(build, List.of(event(2)), List.of(resource(2, 1)));
        var written = ArgumentCaptor.forClass(BatchWriteRowRequest.class);
        verify(client).batchWriteRow(written.capture());
        assertEquals(2, written.getValue().getRowsCount());
        verify(client, never()).batchGetRow(any());
        verify(client, never()).getRow(any());
        var conflict =
                assertThrows(
                        ManagedStateException.class,
                        () -> store.putBatch(build, List.of(), List.of(resource(3, 2))));
        assertEquals("projection_content_conflict", conflict.code());
        verify(client, never()).updateRow(any());
    }

    @Test
    void coldReplayVerifiesAllDuplicateRowsInOneReadAndDetectsContentConflict() {
        var original = store();
        original.putBatch(build, List.of(event(1)), List.of(resource(1, 1)));
        clearInvocations(client);
        store().putBatch(build, List.of(event(1)), List.of(resource(1, 1)));
        verify(client).batchGetRow(any());
        verify(client, never()).getRow(any());
        var changed = new Resource("items", "item", 1, 1, false, "changed");
        assertEquals(
                "projection_content_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () -> store().putBatch(build, List.of(), List.of(changed)))
                        .code());
        verify(client, never()).updateRow(any());
    }

    @Test
    void failedVerificationCannotPopulateCacheOrAdvanceVisibility() {
        store().putBatch(build, List.of(event(1)), List.of(resource(1, 1)));
        var retry = store();
        unavailable.set(true);
        assertThrows(
                IllegalStateException.class,
                () -> retry.putBatch(build, List.of(event(1)), List.of(resource(1, 1))));
        unavailable.set(false);
        clearInvocations(client);
        retry.putBatch(build, List.of(event(1)), List.of(resource(1, 1)));
        var written = ArgumentCaptor.forClass(BatchWriteRowRequest.class);
        verify(client).batchWriteRow(written.capture());
        assertEquals(4, written.getValue().getRowsCount());
        verify(client).batchGetRow(any());
        verify(client, never()).updateRow(any());
    }
}
