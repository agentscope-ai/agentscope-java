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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore.Resource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedProjectionReadCacheTest {
    @Test
    void absentAndExistingReadsAreReusedByUpdatesWithoutLosingCreationIdentity() {
        var store = mock(SessionProjectionStore.class);
        var progress = mock(SessionProjectionStore.Progress.class);
        when(store.resource(progress, "run", "existing"))
                .thenReturn(
                        new Resource("run", "existing", 4, 2, false, "{\"status\":\"running\"}"));
        var overlay = new LinkedHashMap<String, Resource>();
        var resources = new ArrayList<Resource>();
        var fact =
                new ManagedSessionProjections.Fact(8, "source", "turn.completed", "{}", "hash", 1);
        try (var projection =
                new ManagedProtocolProjection(
                        store,
                        new ObjectMapper(),
                        progress,
                        fact,
                        overlay,
                        resources,
                        new ArrayList<>())) {
            assertTrue(projection.value("run", "missing").isEmpty());
            assertTrue(projection.value("run", "missing").isEmpty());
            projection.value("run", "missing", Map.of("status", "completed"));
            assertEquals("running", projection.value("run", "existing").get("status"));
            projection.value("run", "existing", Map.of("status", "completed"));
            assertEquals("completed", projection.value("run", "existing").get("status"));
        }
        verify(store, times(1)).resource(progress, "run", "missing");
        verify(store, times(1)).resource(progress, "run", "existing");
        assertEquals(2, overlay.get("run/existing").createdSequence());
        assertEquals(8, overlay.get("run/missing").createdSequence());
        assertEquals(2, resources.size());
    }

    @Test
    void increasedConcurrencyStillHasAnUpperBound() {
        var projections = mock(ManagedSessionProjections.class);
        var bus = mock(ManagedMessageBus.class);
        var mapper = new ObjectMapper();
        try (var consumer = new ManagedProjectionConsumer(projections, bus, mapper, 64)) {
            assertEquals(0, consumer.pendingCount());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagedProjectionConsumer(projections, bus, mapper, 129));
    }

    @Test
    void immutableBundlesAreReusedAcrossCommitsButChangedReferencesStillValidate(
            @TempDir Path path) {
        var scope = new ManagedScope("t", "n", "s");
        var objects = spy(new FileRuntimeObjectStore("test", path));
        var mapper = new ObjectMapper();
        var bundles = new ManagedFactBundles(objects, mapper);
        var content = new ManagedFactBundles.Content("turn.running", "{}");
        var prepared = bundles.stage(scope, List.of(content)).get(content.hash());
        var ref = prepared.reference();
        var facts = new ManagedProjectionFacts(mock(DataSource.class), mapper, bundles);
        var cache = new LinkedHashMap<RuntimeObjectStore.Reference, ManagedFactBundles.Bundle>();
        for (int i = 1; i <= 2; i++) {
            var entry =
                    new ManagedProjectionFacts.Entry(
                            i,
                            "source" + i,
                            content.type(),
                            content.hash(),
                            1,
                            ref.key(),
                            prepared.ordinal());
            var manifest =
                    new ManagedProjectionFacts.Manifest(
                            i, i, i, Map.of(ref.key(), ref), List.of(entry), Map.of());
            assertEquals(i, facts.read(scope, manifest, cache).through());
        }
        verify(objects, times(1)).read(ref);
        var bad =
                new RuntimeObjectStore.Reference(
                        ref.backendId(),
                        ref.scope(),
                        ref.kind(),
                        ref.key(),
                        "0".repeat(64),
                        ref.size());
        var entry =
                new ManagedProjectionFacts.Entry(
                        3, "bad", content.type(), content.hash(), 1, bad.key(), prepared.ordinal());
        var manifest =
                new ManagedProjectionFacts.Manifest(
                        3, 3, 3, Map.of(bad.key(), bad), List.of(entry), Map.of());
        assertThrows(ManagedStateException.class, () -> facts.read(scope, manifest, cache));
    }
}
