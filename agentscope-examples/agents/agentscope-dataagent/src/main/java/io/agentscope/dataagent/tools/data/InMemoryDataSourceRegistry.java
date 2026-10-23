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
package io.agentscope.dataagent.tools.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link DataSourceRegistry} backed by an in-memory {@link LinkedHashMap}. Seeded once at
 * construction; operators can replace the bean with a JPA- or Nacos-backed implementation. The
 * collection is unmodifiable after construction — call sites that need to add entries dynamically
 * should provide a richer implementation rather than mutating this one.
 */
public final class InMemoryDataSourceRegistry implements DataSourceRegistry {

    private final Map<String, DataSource> byId;

    public InMemoryDataSourceRegistry(List<DataSource> seed) {
        Objects.requireNonNull(seed, "seed");
        Map<String, DataSource> m = new LinkedHashMap<>();
        for (DataSource ds : seed) {
            if (ds == null) continue;
            m.put(ds.id(), ds);
        }
        this.byId = Map.copyOf(m);
    }

    @Override
    public List<DataSource> list() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public Optional<DataSource> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.ofNullable(byId.get(id));
    }
}
