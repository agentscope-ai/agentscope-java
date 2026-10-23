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
import java.util.Optional;

/**
 * SPI for the admin-curated set of {@link DataSource} descriptors a DataAgent deployment exposes.
 * Implementations live behind a Spring {@code @Bean} so operators can swap the in-memory stub for
 * a JPA-, Nacos-, or service-discovery-backed implementation without changing the toolkit code.
 *
 * <p>The registry is read-only from the agent's perspective; admin write paths (CRUD UI, REST) are
 * out of scope for v1 — operators seed the registry through {@code agentscope.json} or a
 * dedicated Spring {@code @Bean}.
 */
public interface DataSourceRegistry {

    /** Lists all configured data sources. */
    List<DataSource> list();

    /** Finds a data source by id. */
    Optional<DataSource> findById(String id);
}
