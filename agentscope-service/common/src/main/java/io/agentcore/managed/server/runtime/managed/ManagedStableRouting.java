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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.sql.DataSource;

/** Immutable persisted route lookup. Bounded cache cannot change a Session's assignment. */
public final class ManagedStableRouting {
    private final ManagedStableRouteStore store;
    private final String cell;
    private final int layout;
    private final int shards;
    private final Map<String, ManagedStableRoute> cache = new LinkedHashMap<>(256, 0.75f, true);

    public ManagedStableRouting(DataSource source, String cell, int layout, int shards) {
        store = new ManagedStableRouteStore(source);
        this.cell = cell;
        this.layout = layout;
        this.shards = shards;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String envelopeScope(ManagedMessageBus.Message message) {
        try {
            return MAPPER.treeToValue(
                            MAPPER.readTree(message.body()).required("scope"), ManagedScope.class)
                    .messageGroup();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Stable route requires a scoped envelope", invalid);
        }
    }

    public List<String> destinations() {
        return IntStream.range(0, shards).mapToObj(i -> cell + "_v" + layout + "_s" + i).toList();
    }

    public boolean ownsDestination(String destination) {
        String prefix = cell + "_v" + layout + "_s";
        if (!destination.startsWith(prefix)) return false;
        try {
            int shard = Integer.parseInt(destination.substring(prefix.length()));
            return shard >= 0 && shard < shards && destination.equals(prefix + shard);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    public void assign(ManagedScope scope) {
        store.assign(scope, cell, layout, shards);
    }

    public ManagedStableRoute route(String scope) {
        synchronized (cache) {
            var value = cache.get(scope);
            if (value != null) return value;
        }
        var value = store.require(scope);
        synchronized (cache) {
            cache.put(scope, value);
            if (cache.size() > 65536) cache.remove(cache.keySet().iterator().next());
        }
        return value;
    }

    public String receive(String scope, String destination, String version) {
        if (scope == null || version == null)
            throw new IllegalArgumentException("Missing stable route envelope");
        var route = route(scope);
        if (!route.liteTopic().equals(destination)
                || route.layoutVersion() != Integer.parseInt(version))
            throw new IllegalArgumentException("Persisted Session route differs from delivery");
        return scope;
    }
}
