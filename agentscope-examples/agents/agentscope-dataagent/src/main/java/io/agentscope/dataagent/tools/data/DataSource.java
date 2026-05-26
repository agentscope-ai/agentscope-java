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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Admin-curated catalog entry describing a named data source the agent may query. v1 is a thin
 * descriptor: a stable id, a human-readable label, the JDBC-style URL prefix (or other connector
 * hint), and an opaque {@code properties} map for connector-specific configuration.
 *
 * <p>Concrete connector implementations (JDBC, BigQuery, Hologres, OSS+Parquet) are explicitly out
 * of scope for v1 — the toolkit returns descriptors here so the agent can reason about which source
 * to use, and an upcoming connector module will implement the actual {@code run_sql_preview}.
 */
public record DataSource(
        String id,
        String label,
        String description,
        String kind,
        String urlHint,
        List<String> tags,
        Map<String, String> properties) {

    public DataSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        tags = tags == null ? List.of() : List.copyOf(tags);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
